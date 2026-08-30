using System.IO;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using AirClip.Core.Clipboard;

namespace AirClip.Platform.Windows.Imaging;

/// <summary>
/// Top-down, straight-alpha BGRA pixels: the canonical form AirClip hashes and transports. Hashing
/// pixels rather than encoded bytes keeps hashes stable across platform PNG encoders.
/// </summary>
internal readonly record struct Bgra32Image(int Width, int Height, byte[] Pixels)
{
    internal int Stride => Width * 4;

    internal static bool TryFromBitmapSource(BitmapSource source, long maxPixelBytes, out Bgra32Image image)
    {
        image = default;

        int width = source.PixelWidth;
        int height = source.PixelHeight;
        if (width <= 0 || height <= 0)
        {
            return false;
        }

        long stride = (long)width * 4;
        if (stride * height > maxPixelBytes)
        {
            return false;
        }

        BitmapSource converted = source.Format == PixelFormats.Bgra32
            ? source
            : new FormatConvertedBitmap(source, PixelFormats.Bgra32, null, 0);

        byte[] pixels = new byte[stride * height];
        converted.CopyPixels(pixels, (int)stride, 0);
        image = new Bgra32Image(width, height, pixels);
        return true;
    }

    /// <summary>Rescues 32bpp sources whose producer left the alpha channel zeroed.</summary>
    internal Bgra32Image WithOpaqueAlphaIfFullyTransparent()
    {
        for (int i = 3; i < Pixels.Length; i += 4)
        {
            if (Pixels[i] != 0)
            {
                return this;
            }
        }

        byte[] opaque = (byte[])Pixels.Clone();
        for (int i = 3; i < opaque.Length; i += 4)
        {
            opaque[i] = 255;
        }

        return new Bgra32Image(Width, Height, opaque);
    }

    internal ClipboardImage ToClipboardImage() =>
        new(Width, Height, EncodePng(), ContentHasher.HashImagePixels(Width, Height, Pixels));

    private byte[] EncodePng()
    {
        BitmapSource source = BitmapSource.Create(Width, Height, 96, 96, PixelFormats.Bgra32, null, Pixels, Stride);
        var encoder = new PngBitmapEncoder();
        encoder.Frames.Add(BitmapFrame.Create(source));

        using var stream = new MemoryStream();
        encoder.Save(stream);
        return stream.ToArray();
    }
}
