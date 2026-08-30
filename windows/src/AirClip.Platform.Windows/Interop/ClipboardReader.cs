using System.Runtime.InteropServices;
using AirClip.Core.Clipboard;
using AirClip.Platform.Windows.Imaging;
using Microsoft.Extensions.Logging;

namespace AirClip.Platform.Windows.Interop;

/// <summary>Reads clipboard formats. Every method requires an already-open clipboard session.</summary>
internal sealed class ClipboardReader(ClipboardOptions options, ILogger logger)
{
    internal ClipboardContent? Read()
    {
        if (options.HonorSensitiveContentMarkers && ClipboardFormats.FindSensitivityMarker() is string marker)
        {
            logger.LogInformation("Skipped clipboard content its owner marked with {Marker}", marker);
            return null;
        }

        if (NativeMethods.IsClipboardFormatAvailable(NativeMethods.CF_UNICODETEXT))
        {
            string? text = ReadUnicodeText();
            return string.IsNullOrEmpty(text) ? null : ClipboardContent.FromText(text);
        }

        if (!options.SyncImages)
        {
            return null;
        }

        if (NativeMethods.IsClipboardFormatAvailable(NativeMethods.CF_DIBV5))
        {
            ClipboardContent? fromV5 = ReadDib(NativeMethods.CF_DIBV5);
            if (fromV5 is not null)
            {
                return fromV5;
            }
        }

        return NativeMethods.IsClipboardFormatAvailable(NativeMethods.CF_DIB)
            ? ReadDib(NativeMethods.CF_DIB)
            : null;
    }

    private string? ReadUnicodeText()
    {
        IntPtr handle = NativeMethods.GetClipboardData(NativeMethods.CF_UNICODETEXT);
        if (handle == IntPtr.Zero)
        {
            return null;
        }

        nuint size = NativeMethods.GlobalSize(handle);
        if (size < sizeof(char))
        {
            return null;
        }

        if (size > (nuint)options.MaxTextBytes + sizeof(char))
        {
            logger.LogWarning("Skipped clipboard text of {Bytes} bytes: over the configured limit", (ulong)size);
            return null;
        }

        IntPtr pointer = NativeMethods.GlobalLock(handle);
        if (pointer == IntPtr.Zero)
        {
            return null;
        }

        try
        {
            string raw = Marshal.PtrToStringUni(pointer, (int)(size / sizeof(char))) ?? string.Empty;
            int terminator = raw.IndexOf('\0');
            return terminator >= 0 ? raw[..terminator] : raw;
        }
        finally
        {
            NativeMethods.GlobalUnlock(handle);
        }
    }

    private ClipboardContent? ReadDib(uint format)
    {
        IntPtr handle = NativeMethods.GetClipboardData(format);
        if (handle == IntPtr.Zero)
        {
            return null;
        }

        nuint size = NativeMethods.GlobalSize(handle);
        if (size == 0 || size > int.MaxValue)
        {
            return null;
        }

        IntPtr pointer = NativeMethods.GlobalLock(handle);
        if (pointer == IntPtr.Zero)
        {
            return null;
        }

        byte[] dib = new byte[(int)size];
        try
        {
            Marshal.Copy(pointer, dib, 0, dib.Length);
        }
        finally
        {
            NativeMethods.GlobalUnlock(handle);
        }

        if (!DibDecoder.TryDecodeDib(dib, out Bgra32Image pixels))
        {
            logger.LogWarning("Could not decode clipboard bitmap (format {Format}, {Bytes} bytes)", format, dib.Length);
            return null;
        }

        ClipboardImage image = pixels.ToClipboardImage();
        if (image.Png.Length > options.MaxImageBytes)
        {
            logger.LogWarning("Skipped clipboard image of {Bytes} bytes: over the configured limit", image.Png.Length);
            return null;
        }

        return ClipboardContent.FromImage(image);
    }
}
