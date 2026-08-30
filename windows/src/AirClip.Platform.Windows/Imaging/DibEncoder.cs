using System.Buffers.Binary;

namespace AirClip.Platform.Windows.Imaging;

/// <summary>
/// Builds the DIB blobs published to the clipboard. Both CF_DIBV5 and CF_DIB are written: V5 keeps
/// real transparency, while the plain 24bpp DIB is understood by every consumer and avoids the
/// "all black" and "invisible image" bugs that undefined alpha in 32bpp BI_RGB causes.
/// </summary>
internal static class DibEncoder
{
    private const int InfoHeaderSize = 40;
    private const int V5HeaderSize = 124;
    private const uint BiRgb = 0;
    private const uint BiBitfields = 3;
    private const uint LcsSrgb = 0x7352_4742;
    private const uint LcsGmImages = 4;

    internal static byte[] EncodeDib24(Bgra32Image image)
    {
        int rowStride = ((image.Width * 3) + 3) & ~3;
        int imageSize = rowStride * image.Height;
        byte[] dib = new byte[InfoHeaderSize + imageSize];
        WriteInfoHeader(dib, image.Width, image.Height, 24, BiRgb, imageSize);

        for (int y = 0; y < image.Height; y++)
        {
            int source = y * image.Stride;
            int destination = InfoHeaderSize + ((image.Height - 1 - y) * rowStride);
            for (int x = 0; x < image.Width; x++)
            {
                byte alpha = image.Pixels[source + 3];
                dib[destination] = OverWhite(image.Pixels[source], alpha);
                dib[destination + 1] = OverWhite(image.Pixels[source + 1], alpha);
                dib[destination + 2] = OverWhite(image.Pixels[source + 2], alpha);
                source += 4;
                destination += 3;
            }
        }

        return dib;
    }

    internal static byte[] EncodeDibV5(Bgra32Image image)
    {
        int imageSize = image.Stride * image.Height;
        byte[] dib = new byte[V5HeaderSize + imageSize];
        WriteInfoHeader(dib, image.Width, image.Height, 32, BiBitfields, imageSize);

        BinaryPrimitives.WriteInt32LittleEndian(dib.AsSpan(0), V5HeaderSize);
        BinaryPrimitives.WriteUInt32LittleEndian(dib.AsSpan(40), 0x00FF0000u);
        BinaryPrimitives.WriteUInt32LittleEndian(dib.AsSpan(44), 0x0000FF00u);
        BinaryPrimitives.WriteUInt32LittleEndian(dib.AsSpan(48), 0x000000FFu);
        BinaryPrimitives.WriteUInt32LittleEndian(dib.AsSpan(52), 0xFF000000u);
        BinaryPrimitives.WriteUInt32LittleEndian(dib.AsSpan(56), LcsSrgb);
        BinaryPrimitives.WriteUInt32LittleEndian(dib.AsSpan(108), LcsGmImages);

        for (int y = 0; y < image.Height; y++)
        {
            int source = y * image.Stride;
            int destination = V5HeaderSize + ((image.Height - 1 - y) * image.Stride);
            image.Pixels.AsSpan(source, image.Stride).CopyTo(dib.AsSpan(destination, image.Stride));
        }

        return dib;
    }

    private static void WriteInfoHeader(byte[] target, int width, int height, short bitCount, uint compression, int imageSize)
    {
        BinaryPrimitives.WriteInt32LittleEndian(target.AsSpan(0), InfoHeaderSize);
        BinaryPrimitives.WriteInt32LittleEndian(target.AsSpan(4), width);
        BinaryPrimitives.WriteInt32LittleEndian(target.AsSpan(8), height);
        BinaryPrimitives.WriteInt16LittleEndian(target.AsSpan(12), 1);
        BinaryPrimitives.WriteInt16LittleEndian(target.AsSpan(14), bitCount);
        BinaryPrimitives.WriteUInt32LittleEndian(target.AsSpan(16), compression);
        BinaryPrimitives.WriteInt32LittleEndian(target.AsSpan(20), imageSize);
    }

    private static byte OverWhite(byte channel, byte alpha) => alpha == 255
        ? channel
        : (byte)((((channel * alpha) + (255 * (255 - alpha))) + 127) / 255);
}
