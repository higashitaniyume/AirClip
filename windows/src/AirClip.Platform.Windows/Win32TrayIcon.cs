using System.Runtime.InteropServices;
using AirClip.Platform.Windows.Interop;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Logging.Abstractions;

namespace AirClip.Platform.Windows;

/// <summary>Top-down straight-alpha BGRA pixels for a tray icon, sized by the caller for the current DPI.</summary>
public sealed record TrayIconImage(int Width, int Height, byte[] Bgra);

public sealed record TrayMenuItem
{
    public static TrayMenuItem Separator { get; } = new() { Id = string.Empty, Text = string.Empty, IsSeparator = true };

    public required string Id { get; init; }

    public required string Text { get; init; }

    public bool IsChecked { get; init; }

    public bool IsEnabled { get; init; } = true;

    public bool IsSeparator { get; init; }

    public static TrayMenuItem Command(string id, string text, bool isChecked = false, bool isEnabled = true) =>
        new() { Id = id, Text = text, IsChecked = isChecked, IsEnabled = isEnabled };
}

/// <summary>
/// Notification-area icon built directly on Shell_NotifyIcon and a native popup menu, so the app
/// needs no WinForms or third-party tray dependency. Runs on its own message-pump thread; both
/// <see cref="Activated"/> and <see cref="CommandInvoked"/> are raised on that thread, so a UI
/// caller must marshal them onto its dispatcher.
/// </summary>
public sealed class Win32TrayIcon : IDisposable
{
    private const uint IconId = 1;

    private readonly ILogger _logger;
    private readonly MessageOnlyWindow _window;
    private readonly object _sync = new();
    private IReadOnlyList<TrayMenuItem> _menu = [];
    private TrayIconImage? _pendingIcon;
    private IntPtr _iconHandle;
    private string _tooltip = string.Empty;
    private bool _added;
    private bool _disposed;

    public Win32TrayIcon(ILogger<Win32TrayIcon>? logger = null)
    {
        _logger = logger ?? NullLogger<Win32TrayIcon>.Instance;
        _window = new MessageOnlyWindow("AirClip.Tray", OnMessage);
        _window.MessageLoopFailed += ex => _logger.LogError(ex, "Tray message loop reported a failure");
    }

    /// <summary>Left click or keyboard select on the icon.</summary>
    public event Action? Activated;

    /// <summary>Id of the context-menu item the user picked.</summary>
    public event Action<string>? CommandInvoked;

    public bool IsVisible => _added;

    public void Show(TrayIconImage icon, string tooltip)
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        _window.Start();
        _tooltip = tooltip;
        _pendingIcon = icon;
        _window.InvokeAsync(AddOrUpdate).GetAwaiter().GetResult();
    }

    public void SetIcon(TrayIconImage icon)
    {
        if (_disposed)
        {
            return;
        }

        _pendingIcon = icon;
        _window.Post(() => AddOrUpdate());
    }

    public void SetTooltip(string tooltip)
    {
        if (_disposed || string.Equals(_tooltip, tooltip, StringComparison.Ordinal))
        {
            return;
        }

        _tooltip = tooltip;
        _window.Post(() => AddOrUpdate());
    }

    public void SetMenu(IReadOnlyList<TrayMenuItem> items)
    {
        lock (_sync)
        {
            _menu = items;
        }
    }

    public void ShowBalloon(string title, string message)
    {
        if (_disposed || !_added)
        {
            return;
        }

        _window.Post(() =>
        {
            unsafe
            {
                NativeMethods.NOTIFYICONDATAW data = CreateData(NativeMethods.NIF_INFO);
                data.dwInfoFlags = NativeMethods.NIIF_INFO;
                NativeMethods.NOTIFYICONDATAW.WriteInline(data.szInfoTitle, 64, title);
                NativeMethods.NOTIFYICONDATAW.WriteInline(data.szInfo, 256, message);
                NativeMethods.Shell_NotifyIcon(NativeMethods.NIM_MODIFY, ref data);
            }
        });
    }

    /// <summary>Small-icon size for the current DPI, so the tray never scales a mismatched bitmap.</summary>
    public static (int Width, int Height) PreferredIconSize()
    {
        int width = NativeMethods.GetSystemMetrics(NativeMethods.SM_CXSMICON);
        int height = NativeMethods.GetSystemMetrics(NativeMethods.SM_CYSMICON);
        return (width > 0 ? width : 16, height > 0 ? height : 16);
    }

    public void Dispose()
    {
        if (_disposed)
        {
            return;
        }

        _disposed = true;

        if (_added)
        {
            _window.InvokeAsync(() =>
            {
                NativeMethods.NOTIFYICONDATAW data = CreateData(0);
                return NativeMethods.Shell_NotifyIcon(NativeMethods.NIM_DELETE, ref data);
            }).Wait(TimeSpan.FromSeconds(1));
            _added = false;
        }

        _window.Dispose();
        ReleaseIcon();
    }

    private bool AddOrUpdate()
    {
        TrayIconImage? icon = Interlocked.Exchange(ref _pendingIcon, null);
        if (icon is not null)
        {
            IntPtr handle = IconFactory.CreateIcon(icon.Width, icon.Height, icon.Bgra);
            if (handle != IntPtr.Zero)
            {
                ReleaseIcon();
                _iconHandle = handle;
            }
        }

        unsafe
        {
            NativeMethods.NOTIFYICONDATAW data = CreateData(
                NativeMethods.NIF_MESSAGE | NativeMethods.NIF_ICON | NativeMethods.NIF_TIP | NativeMethods.NIF_SHOWTIP);
            NativeMethods.NOTIFYICONDATAW.WriteInline(data.szTip, 128, _tooltip);

            if (_added)
            {
                return NativeMethods.Shell_NotifyIcon(NativeMethods.NIM_MODIFY, ref data);
            }

            if (!NativeMethods.Shell_NotifyIcon(NativeMethods.NIM_ADD, ref data))
            {
                _logger.LogError("Shell_NotifyIcon(NIM_ADD) failed; the tray icon will not appear");
                return false;
            }

            _added = true;

            // Version 4 gives us WM_CONTEXTMENU with cursor coordinates in wParam.
            NativeMethods.NOTIFYICONDATAW version = CreateData(0);
            version.uVersion = NativeMethods.NOTIFYICON_VERSION_4;
            NativeMethods.Shell_NotifyIcon(NativeMethods.NIM_SETVERSION, ref version);
            _logger.LogDebug("Tray icon added (hwnd 0x{Handle:X})", _window.Handle.ToInt64());
            return true;
        }
    }

    private NativeMethods.NOTIFYICONDATAW CreateData(uint flags) => new()
    {
        cbSize = (uint)Marshal.SizeOf<NativeMethods.NOTIFYICONDATAW>(),
        hWnd = _window.Handle,
        uID = IconId,
        uFlags = flags,
        uCallbackMessage = NativeMethods.WM_AIRCLIP_TRAY,
        hIcon = _iconHandle,
    };

    private void ReleaseIcon()
    {
        if (_iconHandle != IntPtr.Zero)
        {
            NativeMethods.DestroyIcon(_iconHandle);
            _iconHandle = IntPtr.Zero;
        }
    }

    private bool OnMessage(uint message, IntPtr wParam, IntPtr lParam)
    {
        if (message != NativeMethods.WM_AIRCLIP_TRAY)
        {
            return false;
        }

        uint notification = (uint)(lParam.ToInt64() & 0xFFFF);
        switch (notification)
        {
            case NativeMethods.NIN_SELECT:
            case NativeMethods.NIN_KEYSELECT:
            case NativeMethods.WM_LBUTTONUP:
            case NativeMethods.WM_LBUTTONDBLCLK:
                Activated?.Invoke();
                return true;

            case NativeMethods.WM_CONTEXTMENU:
                ShowMenu(SignedLoWord(wParam), SignedHiWord(wParam));
                return true;

            default:
                return true;
        }
    }

    private void ShowMenu(int x, int y)
    {
        IReadOnlyList<TrayMenuItem> items;
        lock (_sync)
        {
            items = _menu;
        }

        if (items.Count == 0)
        {
            return;
        }

        IntPtr menu = NativeMethods.CreatePopupMenu();
        if (menu == IntPtr.Zero)
        {
            return;
        }

        try
        {
            for (int i = 0; i < items.Count; i++)
            {
                TrayMenuItem item = items[i];
                if (item.IsSeparator)
                {
                    NativeMethods.AppendMenu(menu, NativeMethods.MF_SEPARATOR, 0, null);
                    continue;
                }

                uint flags = NativeMethods.MF_STRING;
                if (item.IsChecked)
                {
                    flags |= NativeMethods.MF_CHECKED;
                }

                if (!item.IsEnabled)
                {
                    flags |= NativeMethods.MF_GRAYED;
                }

                // Menu command ids are 1-based; 0 is what TrackPopupMenuEx returns for "cancelled".
                NativeMethods.AppendMenu(menu, flags, (nuint)(i + 1), item.Text);
            }

            // Without foreground ownership the menu would not dismiss when the user clicks elsewhere.
            NativeMethods.SetForegroundWindow(_window.Handle);

            int selected = NativeMethods.TrackPopupMenuEx(
                menu,
                NativeMethods.TPM_RETURNCMD | NativeMethods.TPM_NONOTIFY | NativeMethods.TPM_RIGHTBUTTON
                    | NativeMethods.TPM_LEFTALIGN | NativeMethods.TPM_BOTTOMALIGN,
                x,
                y,
                _window.Handle,
                IntPtr.Zero);

            // Documented workaround so a second right-click reopens the menu instead of being eaten.
            NativeMethods.PostMessage(_window.Handle, NativeMethods.WM_NULL, IntPtr.Zero, IntPtr.Zero);

            if (selected >= 1 && selected <= items.Count)
            {
                CommandInvoked?.Invoke(items[selected - 1].Id);
            }
        }
        finally
        {
            NativeMethods.DestroyMenu(menu);
        }
    }

    private static int SignedLoWord(IntPtr value) => (short)(value.ToInt64() & 0xFFFF);

    private static int SignedHiWord(IntPtr value) => (short)((value.ToInt64() >> 16) & 0xFFFF);
}
