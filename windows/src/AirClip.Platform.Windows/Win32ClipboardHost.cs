using System.ComponentModel;
using System.Runtime.InteropServices;
using AirClip.Platform.Windows.Interop;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Logging.Abstractions;

namespace AirClip.Platform.Windows;

/// <summary>
/// Owns the message-only window that clipboard interop runs on and relays WM_CLIPBOARDUPDATE.
/// The monitor and the writer must share one host: a write makes this window the clipboard owner,
/// which is how the monitor recognises the resulting notification as self-inflicted.
/// </summary>
public sealed class Win32ClipboardHost : IDisposable
{
    private readonly ILogger _logger;
    private readonly object _sync = new();
    private MessageOnlyWindow? _window;
    private bool _listening;
    private bool _disposed;

    public Win32ClipboardHost(ILogger<Win32ClipboardHost>? logger = null) =>
        _logger = logger ?? NullLogger<Win32ClipboardHost>.Instance;

    internal event Action? ClipboardUpdated;

    public IntPtr Handle => _window?.Handle ?? IntPtr.Zero;

    public bool IsRunning => _window is not null;

    public void Start()
    {
        lock (_sync)
        {
            ObjectDisposedException.ThrowIf(_disposed, this);
            if (_window is not null)
            {
                return;
            }

            var window = new MessageOnlyWindow("AirClip.Clipboard", OnMessage);
            window.MessageLoopFailed += OnMessageLoopFailed;
            window.Start();
            _window = window;
            _logger.LogDebug("Clipboard message window ready (hwnd 0x{Handle:X})", window.Handle.ToInt64());
        }
    }

    public void Dispose()
    {
        MessageOnlyWindow? window;
        lock (_sync)
        {
            if (_disposed)
            {
                return;
            }

            _disposed = true;
            window = _window;
            _window = null;
        }

        if (window is null)
        {
            return;
        }

        if (_listening)
        {
            window.InvokeAsync(() => NativeMethods.RemoveClipboardFormatListener(window.Handle))
                .Wait(TimeSpan.FromSeconds(1));
            _listening = false;
        }

        window.MessageLoopFailed -= OnMessageLoopFailed;
        window.Dispose();
    }

    internal void AddClipboardListener()
    {
        Start();
        int error = Invoke(() =>
            NativeMethods.AddClipboardFormatListener(Handle) ? 0 : Marshal.GetLastWin32Error());

        if (error != 0)
        {
            throw new Win32Exception(error, "AddClipboardFormatListener failed.");
        }

        _listening = true;
    }

    internal void RemoveClipboardListener()
    {
        if (!_listening || _window is null)
        {
            return;
        }

        Invoke(() => NativeMethods.RemoveClipboardFormatListener(Handle));
        _listening = false;
    }

    internal void Post(Action action) => _window?.Post(action);

    /// <summary>Queues <paramref name="function"/> onto the message-pump thread.</summary>
    internal Task<T> InvokeAsync<T>(Func<T> function)
    {
        MessageOnlyWindow window = _window ?? throw new InvalidOperationException("Clipboard host is not started.");
        return window.InvokeAsync(function);
    }

    /// <summary>Runs <paramref name="function"/> on the message-pump thread and waits for the result.</summary>
    internal T Invoke<T>(Func<T> function) => InvokeAsync(function).GetAwaiter().GetResult();

    private bool OnMessage(uint message, IntPtr wParam, IntPtr lParam)
    {
        if (message != NativeMethods.WM_CLIPBOARDUPDATE)
        {
            return false;
        }

        ClipboardUpdated?.Invoke();
        return true;
    }

    private void OnMessageLoopFailed(Exception exception) =>
        _logger.LogError(exception, "Clipboard message loop reported a failure");
}
