using System.Runtime.InteropServices;

namespace AirClip.Platform.Windows.Interop;

/// <summary>Shell notification area, popup menu and icon-from-pixels interop.</summary>
internal static partial class NativeMethods
{
    private const string Shell32 = "shell32.dll";
    private const string Gdi32 = "gdi32.dll";

    internal const uint WM_NULL = 0x0000;
    internal const uint WM_USER = 0x0400;
    internal const uint WM_CONTEXTMENU = 0x007B;
    internal const uint WM_LBUTTONUP = 0x0202;
    internal const uint WM_LBUTTONDBLCLK = 0x0203;

    /// <summary>Callback message the shell sends us for tray events. Distinct from WM_AIRCLIP_INVOKE.</summary>
    internal const uint WM_AIRCLIP_TRAY = WM_APP + 0x20;

    internal const uint NIN_SELECT = WM_USER + 0;
    internal const uint NIN_KEYSELECT = WM_USER + 1;

    internal const uint NIM_ADD = 0x0000;
    internal const uint NIM_MODIFY = 0x0001;
    internal const uint NIM_DELETE = 0x0002;
    internal const uint NIM_SETVERSION = 0x0004;

    internal const uint NIF_MESSAGE = 0x0001;
    internal const uint NIF_ICON = 0x0002;
    internal const uint NIF_TIP = 0x0004;
    internal const uint NIF_INFO = 0x0010;
    internal const uint NIF_SHOWTIP = 0x0080;

    internal const uint NOTIFYICON_VERSION_4 = 4;
    internal const uint NIIF_INFO = 0x0001;

    internal const uint MF_STRING = 0x0000;
    internal const uint MF_GRAYED = 0x0001;
    internal const uint MF_CHECKED = 0x0008;
    internal const uint MF_SEPARATOR = 0x0800;

    internal const uint TPM_LEFTALIGN = 0x0000;
    internal const uint TPM_RIGHTBUTTON = 0x0002;
    internal const uint TPM_BOTTOMALIGN = 0x0020;
    internal const uint TPM_NONOTIFY = 0x0080;
    internal const uint TPM_RETURNCMD = 0x0100;

    internal const int SM_CXSMICON = 49;
    internal const int SM_CYSMICON = 50;

    /// <summary>
    /// NOTIFYICONDATAW laid out as a blittable struct so the LibraryImport generator accepts it;
    /// the string members are inline buffers rather than marshalled strings for the same reason.
    /// </summary>
    [StructLayout(LayoutKind.Sequential)]
    internal unsafe struct NOTIFYICONDATAW
    {
        internal uint cbSize;
        internal IntPtr hWnd;
        internal uint uID;
        internal uint uFlags;
        internal uint uCallbackMessage;
        internal IntPtr hIcon;
        internal fixed char szTip[128];
        internal uint dwState;
        internal uint dwStateMask;
        internal fixed char szInfo[256];
        internal uint uVersion;
        internal fixed char szInfoTitle[64];
        internal uint dwInfoFlags;
        internal Guid guidItem;
        internal IntPtr hBalloonIcon;

        /// <summary>Copies <paramref name="value"/> into an inline buffer, truncating and NUL-terminating.</summary>
        internal static void WriteInline(char* destination, int capacity, string? value)
        {
            int length = Math.Min(value?.Length ?? 0, capacity - 1);
            for (int i = 0; i < length; i++)
            {
                destination[i] = value![i];
            }

            destination[length] = '\0';
        }
    }

    [StructLayout(LayoutKind.Sequential)]
    internal struct ICONINFO
    {
        internal int fIcon;
        internal uint xHotspot;
        internal uint yHotspot;
        internal IntPtr hbmMask;
        internal IntPtr hbmColor;
    }

    [LibraryImport(Shell32, EntryPoint = "Shell_NotifyIconW", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    internal static partial bool Shell_NotifyIcon(uint dwMessage, ref NOTIFYICONDATAW lpData);

    [LibraryImport(User32, EntryPoint = "CreatePopupMenu", SetLastError = true)]
    internal static partial IntPtr CreatePopupMenu();

    [LibraryImport(User32, EntryPoint = "AppendMenuW", SetLastError = true, StringMarshalling = StringMarshalling.Utf16)]
    [return: MarshalAs(UnmanagedType.Bool)]
    internal static partial bool AppendMenu(IntPtr hMenu, uint uFlags, nuint uIDNewItem, string? lpNewItem);

    [LibraryImport(User32, EntryPoint = "TrackPopupMenuEx", SetLastError = true)]
    internal static partial int TrackPopupMenuEx(IntPtr hMenu, uint uFlags, int x, int y, IntPtr hwnd, IntPtr lptpm);

    [LibraryImport(User32, EntryPoint = "DestroyMenu", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    internal static partial bool DestroyMenu(IntPtr hMenu);

    [LibraryImport(User32, EntryPoint = "SetForegroundWindow")]
    [return: MarshalAs(UnmanagedType.Bool)]
    internal static partial bool SetForegroundWindow(IntPtr hWnd);

    [LibraryImport(User32, EntryPoint = "GetSystemMetrics")]
    internal static partial int GetSystemMetrics(int nIndex);

    [LibraryImport(User32, EntryPoint = "CreateIconIndirect", SetLastError = true)]
    internal static partial IntPtr CreateIconIndirect(ref ICONINFO piconinfo);

    [LibraryImport(User32, EntryPoint = "DestroyIcon", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    internal static partial bool DestroyIcon(IntPtr hIcon);

    [LibraryImport(Gdi32, EntryPoint = "CreateBitmap", SetLastError = true)]
    internal static partial IntPtr CreateBitmap(int nWidth, int nHeight, uint nPlanes, uint nBitCount, ReadOnlySpan<byte> lpBits);

    [LibraryImport(Gdi32, EntryPoint = "DeleteObject", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    internal static partial bool DeleteObject(IntPtr ho);
}
