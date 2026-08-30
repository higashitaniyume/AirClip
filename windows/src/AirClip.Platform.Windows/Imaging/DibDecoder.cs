using System.Buffers.Binary;
using System.IO;
using System.Windows.Media;
using System.Windows.Media.Imaging;

namespace AirClip.Platform.Windows.Imaging;

/// <summary>
/// Turns clipboard DIB blobs into pixels. A CF_DIB handle is a BITMAPINFO* with no
/// BITMAPFILEHEADER, so the header is synthesised before handing the buffer to WIC.
/// </summary>
internal static class DibDecoder
{
    internal const long MaxPixelBytes = 256L * 1024 * 1024;

    private const int FileHeaderSize = 14;
    private const int CoreHeaderSize = 12;
    private const int InfoHeaderSize = 40;
    private const uint BiBitfields = 3;

    internal static bool TryDecodeDib(byte[] dib, out Bgra32Image image)
    {
        image = default;
        if (dib.Length < 16)
        {
            return false;
        }

        int headerSize = BinaryPrimitives.ReadInt32LittleEndian(dib);
        if (headerSize is < CoreHeaderSize or > 4096 || headerSize >= dib.Length)
        {
            return false;
        }

        int bitCount;
        uint compression = 0;
        uint paletteEntries;
        int paletteEntrySize;

        if (headerSize == CoreHeaderSize)
        {
            bitCount = BinaryPrimitives.ReadInt16LittleEndian(dib.AsSpan(10));
            paletteEntries = bitCount <= 8 ? 1u << bitCount : 0;
            paletteEntrySize = 3;
        }
        else
        {
            bitCount = BinaryPrimitives.ReadInt16LittleEndian(dib.AsSpan(14));
            compression = BinaryPrimitives.ReadUInt32LittleEndian(dib.AsSpan(16));
            uint declared = BinaryPrimitives.ReadUInt32LittleEndian(dib.AsSpan(32));
            paletteEntries = bitCount <= 8 && declared == 0 ? 1u << bitCount : declared;
            paletteEntrySize = 4;
        }

        if (bitCount is not (1 or 4 or 8 or 16 or 24 or 32))
        {
            return false;
        }

        long extraBytes = (long)paletteEntries * paletteEntrySize;
        if (headerSize == InfoHeaderSize && compression == BiBitfields)
        {
            extraBytes += 12;
        }

        long fileSize = FileHeaderSize + (long)dib.Length;
        long pixelOffset = FileHeaderSize + headerSize + extraBytes;
        if (pixelOffset >= fileSize || fileSize > int.MaxValue)
        {
            return false;
        }

        byte[] bmp = new byte[dib.Length + FileHeaderSize];
        bmp[0] = (byte)'B';
        bmp[1] = (byte)'M';
        BinaryPrimitives.WriteUInt32LittleEndian(bmp.AsSpan(2), (uint)fileSize);
        BinaryPrimitives.WriteUInt32LittleEndian(bmp.AsSpan(10), (uint)pixelOffset);
        dib.CopyTo(bmp, FileHeaderSize);

        BitmapSource? frame = DecodeFirstFrame(bmp);
        if (frame is null || !Bgra32Image.TryFromBitmapSource(frame, MaxPixelBytes, out image))
        {
            return false;
        }

        if (bitCount == 32)
        {
            image = image.WithOpaqueAlphaIfFullyTransparent();
        }

        return true;
    }

    internal static bool TryDecodeEncoded(byte[] encoded, out Bgra32Image image)
    {
        image = default;
        BitmapSource? frame = DecodeFirstFrame(encoded);
        return frame is not null && Bgra32Image.TryFromBitmapSource(frame, MaxPixelBytes, out image);
    }

    private static BitmapSource? DecodeFirstFrame(byte[] bytes)
    {
        try
        {
            using var stream = new MemoryStream(bytes, writable: false);
            BitmapDecoder decoder = BitmapDecoder.Create(
                stream, BitmapCreateOptions.PreservePixelFormat, BitmapCacheOption.OnLoad);
            return decoder.Frames.Count > 0 ? decoder.Frames[0] : null;
        }
        catch (Exception ex) when (ex is FileFormatException or NotSupportedException or ArgumentException
                                      or OverflowException or IOException or OutOfMemoryException)
        {
            return null;
        }
    }
}
