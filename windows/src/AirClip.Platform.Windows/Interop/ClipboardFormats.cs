using System.Buffers.Binary;
using System.Runtime.InteropServices;

namespace AirClip.Platform.Windows.Interop;

/// <summary>
/// Registered clipboard formats that let an application opt its content out of clipboard managers.
/// Password managers set these, so honouring them keeps credentials off the network.
/// </summary>
internal static class ClipboardFormats
{
    private static readonly Lazy<uint> ExcludeFromMonitorProcessing =
        new(() => NativeMethods.RegisterClipboardFormat("ExcludeClipboardContentFromMonitorProcessing"));

    private static readonly Lazy<uint> CanIncludeInClipboardHistory =
        new(() => NativeMethods.RegisterClipboardFormat("CanIncludeInClipboardHistory"));

    private static readonly Lazy<uint> CanUploadToCloudClipboard =
        new(() => NativeMethods.RegisterClipboardFormat("CanUploadToCloudClipboard"));

    /// <summary>
    /// The same three markers, for writing. AirClip sets them on the one thing it copies that must not
    /// travel anywhere: the pairing code. Its own monitor already ignores its own writes, but Windows
    /// clipboard history, the cloud clipboard and any third-party manager on the machine do not.
    /// </summary>
    internal static uint ExcludeFromMonitorProcessingFormat => ExcludeFromMonitorProcessing.Value;

    internal static uint CanIncludeInClipboardHistoryFormat => CanIncludeInClipboardHistory.Value;

    internal static uint CanUploadToCloudClipboardFormat => CanUploadToCloudClipboard.Value;

    /// <summary>
    /// Returns the marker that opts the current clipboard content out of clipboard managers, or
    /// <see langword="null"/> when the content is safe to sync. Requires an open clipboard session.
    /// </summary>
    /// <remarks>
    /// "Clipboard Viewer Ignore" is intentionally absent: clipboard managers (Ditto, rdpclip, VM guest
    /// tools) stamp it on their own writes purely as loop prevention, so treating it as sensitive would
    /// stop AirClip from syncing anything on a machine that runs one of them.
    /// </remarks>
    internal static string? FindSensitivityMarker()
    {
        if (IsPresent(ExcludeFromMonitorProcessing.Value))
        {
            return "ExcludeClipboardContentFromMonitorProcessing";
        }

        if (ReadFlag(CanIncludeInClipboardHistory.Value) == 0)
        {
            return "CanIncludeInClipboardHistory=0";
        }

        return ReadFlag(CanUploadToCloudClipboard.Value) == 0 ? "CanUploadToCloudClipboard=0" : null;
    }

    private static bool IsPresent(uint format) => format != 0 && NativeMethods.IsClipboardFormatAvailable(format);

    private static uint? ReadFlag(uint format)
    {
        if (!IsPresent(format))
        {
            return null;
        }

        IntPtr handle = NativeMethods.GetClipboardData(format);
        if (handle == IntPtr.Zero || NativeMethods.GlobalSize(handle) < sizeof(uint))
        {
            return null;
        }

        IntPtr pointer = NativeMethods.GlobalLock(handle);
        if (pointer == IntPtr.Zero)
        {
            return null;
        }

        try
        {
            byte[] bytes = new byte[sizeof(uint)];
            Marshal.Copy(pointer, bytes, 0, bytes.Length);
            return BinaryPrimitives.ReadUInt32LittleEndian(bytes);
        }
        finally
        {
            NativeMethods.GlobalUnlock(handle);
        }
    }
}
