using System.Collections.Concurrent;
using System.ComponentModel;
using System.Runtime.InteropServices;

namespace AirClip.Platform.Windows.Interop;

/// <summary>
/// A HWND_MESSAGE window with its own dedicated STA thread and message loop. Clipboard APIs are
/// thread-affine (<c>OpenClipboard</c> requires a window owned by the calling thread), so every
/// clipboard read and write is marshalled onto this thread via <see cref="Post"/>.
/// </summary>
internal sealed class MessageOnlyWindow : IDisposable
{
    private readonly NativeMethods.WndProcDelegate _wndProc;
    private readonly Func<uint, IntPtr, IntPtr, bool> _messageHandler;
    private readonly ConcurrentQueue<Action> _pending = new();
    private readonly TaskCompletionSource _ready = new(TaskCreationOptions.RunContinuationsAsynchronously);
    private readonly Thread _thread;
    private readonly string _className;
    private IntPtr _classNamePtr;
    private IntPtr _instanceHandle;
    private IntPtr _hwnd;
    private ushort _classAtom;
    private int _started;
    private int _disposed;

    internal MessageOnlyWindow(string name, Func<uint, IntPtr, IntPtr, bool> messageHandler)
    {
        _messageHandler = messageHandler;
        _wndProc = WndProc;
        _className = $"{name}.{Guid.NewGuid():N}";
        _thread = new Thread(PumpThread)
        {
            IsBackground = true,
            Name = $"{name}.MessagePump",
        };
        _thread.SetApartmentState(ApartmentState.STA);
    }

    internal event Action<Exception>? MessageLoopFailed;

    internal IntPtr Handle => _hwnd;

    internal bool IsOnPumpThread => Environment.CurrentManagedThreadId == _thread.ManagedThreadId;

    internal void Start()
    {
        if (Interlocked.Exchange(ref _started, 1) != 0)
        {
            return;
        }

        _thread.Start();
        _ready.Task.GetAwaiter().GetResult();
    }

    internal void Post(Action action)
    {
        if (Volatile.Read(ref _disposed) != 0)
        {
            return;
        }

        _pending.Enqueue(action);

        IntPtr hwnd = _hwnd;
        if (hwnd != IntPtr.Zero)
        {
            NativeMethods.PostMessage(hwnd, NativeMethods.WM_AIRCLIP_INVOKE, IntPtr.Zero, IntPtr.Zero);
        }
    }

    internal Task<T> InvokeAsync<T>(Func<T> function)
    {
        if (IsOnPumpThread)
        {
            return Task.FromResult(function());
        }

        var completion = new TaskCompletionSource<T>(TaskCreationOptions.RunContinuationsAsynchronously);
        Post(() =>
        {
            try
            {
                completion.TrySetResult(function());
            }
            catch (Exception ex)
            {
                completion.TrySetException(ex);
            }
        });
        return completion.Task;
    }

    public void Dispose()
    {
        if (Interlocked.Exchange(ref _disposed, 1) != 0)
        {
            return;
        }

        IntPtr hwnd = _hwnd;
        if (hwnd != IntPtr.Zero)
        {
            NativeMethods.PostMessage(hwnd, NativeMethods.WM_CLOSE, IntPtr.Zero, IntPtr.Zero);
        }

        if (Volatile.Read(ref _started) != 0 && !IsOnPumpThread)
        {
            _thread.Join(TimeSpan.FromSeconds(2));
        }
    }

    private void PumpThread()
    {
        try
        {
            _instanceHandle = NativeMethods.GetModuleHandle(null);
            _classNamePtr = Marshal.StringToHGlobalUni(_className);

            var windowClass = new NativeMethods.WNDCLASSEX
            {
                cbSize = (uint)Marshal.SizeOf<NativeMethods.WNDCLASSEX>(),
                lpfnWndProc = Marshal.GetFunctionPointerForDelegate(_wndProc),
                hInstance = _instanceHandle,
                lpszClassName = _classNamePtr,
            };

            _classAtom = NativeMethods.RegisterClassEx(ref windowClass);
            if (_classAtom == 0)
            {
                throw new Win32Exception(Marshal.GetLastWin32Error(), "RegisterClassEx failed.");
            }

            _hwnd = NativeMethods.CreateWindowEx(
                0, _className, _className, 0, 0, 0, 0, 0,
                NativeMethods.HwndMessage, IntPtr.Zero, _instanceHandle, IntPtr.Zero);

            if (_hwnd == IntPtr.Zero)
            {
                throw new Win32Exception(Marshal.GetLastWin32Error(), "CreateWindowEx failed.");
            }

            _ready.TrySetResult();
            DrainPending();

            while (true)
            {
                int result = NativeMethods.GetMessage(out NativeMethods.MSG message, IntPtr.Zero, 0, 0);
                if (result == 0)
                {
                    break;
                }

                if (result == -1)
                {
                    throw new Win32Exception(Marshal.GetLastWin32Error(), "GetMessage failed.");
                }

                NativeMethods.TranslateMessage(ref message);
                NativeMethods.DispatchMessage(ref message);
            }
        }
        catch (Exception ex)
        {
            if (!_ready.TrySetException(ex))
            {
                MessageLoopFailed?.Invoke(ex);
            }
        }
        finally
        {
            Cleanup();
            _ready.TrySetResult();
        }
    }

    private IntPtr WndProc(IntPtr hwnd, uint msg, IntPtr wParam, IntPtr lParam)
    {
        if (msg == NativeMethods.WM_AIRCLIP_INVOKE)
        {
            DrainPending();
            return IntPtr.Zero;
        }

        if (msg == NativeMethods.WM_DESTROY)
        {
            _hwnd = IntPtr.Zero;
            NativeMethods.PostQuitMessage(0);
            return IntPtr.Zero;
        }

        try
        {
            if (_messageHandler(msg, wParam, lParam))
            {
                return IntPtr.Zero;
            }
        }
        catch (Exception ex)
        {
            // A managed exception must never unwind into user32's dispatch frame.
            MessageLoopFailed?.Invoke(ex);
        }

        return NativeMethods.DefWindowProc(hwnd, msg, wParam, lParam);
    }

    private void DrainPending()
    {
        while (_pending.TryDequeue(out Action? action))
        {
            try
            {
                action();
            }
            catch (Exception ex)
            {
                MessageLoopFailed?.Invoke(ex);
            }
        }
    }

    private void Cleanup()
    {
        if (_hwnd != IntPtr.Zero)
        {
            NativeMethods.DestroyWindow(_hwnd);
            _hwnd = IntPtr.Zero;
        }

        if (_classAtom != 0)
        {
            NativeMethods.UnregisterClass(_classNamePtr, _instanceHandle);
            _classAtom = 0;
        }

        if (_classNamePtr != IntPtr.Zero)
        {
            Marshal.FreeHGlobal(_classNamePtr);
            _classNamePtr = IntPtr.Zero;
        }
    }
}
