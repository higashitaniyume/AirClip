using System.Windows.Media;
using System.Windows.Media.Imaging;
using ZXing;
using ZXing.Common;
using ZXing.QrCode;

namespace AirClip.App.Views;

/// <summary>
/// Draws a pairing invite as a QR code for the phone's camera. The encoder is ZXing, which is the same
/// library — the same port of the same code — that the Android app decodes with, so anything produced here
/// is by construction something the other end can read.
/// <para>
/// Two of the choices below are about the camera rather than about looks. The bitmap is an <em>integer</em>
/// number of pixels per module and is displayed unscaled: a code resampled to fit a pixel budget puts module
/// edges on half pixels, which is what makes a QR fail to scan at an angle or in poor light. And the quiet
/// zone is painted white into the bitmap itself instead of being left to the window, because the window is a
/// dark theme, and a code whose four-module margin is dark is a code missing its margin.
/// </para>
/// </summary>
internal static class QrArtwork
{
    /// <summary>Modules of white margin the format asks for; without it a scanner may not find the code.</summary>
    private const int QuietZoneModules = 4;

    private const byte Paper = 0xFF;
    private const byte Ink = 0x00;

    /// <summary>
    /// The text as a frozen <see cref="PixelFormats.Gray8"/> bitmap, or <see langword="null"/> if it is
    /// blank or too long for any QR version. The result is at most <paramref name="targetPixels"/> across:
    /// the module size is rounded down to whole pixels, so a big payload renders smaller rather than blurry.
    /// </summary>
    internal static BitmapSource? TryRender(string? text, int targetPixels = 280)
    {
        if (string.IsNullOrWhiteSpace(text))
        {
            return null;
        }

        BitMatrix? matrix = TryEncode(text);
        if (matrix is null)
        {
            return null;
        }

        // Width and Height are left at zero on the options, which is what makes ZXing hand back the matrix
        // at one cell per module with the quiet zone already around it, rather than fitted to a pixel box.
        int scale = Math.Max(1, targetPixels / matrix.Width);
        int side = matrix.Width * scale;
        byte[] pixels = new byte[side * side];
        Array.Fill(pixels, Paper);

        for (int y = 0; y < matrix.Height; y++)
        {
            for (int x = 0; x < matrix.Width; x++)
            {
                if (!matrix[x, y])
                {
                    continue;
                }

                for (int dy = 0; dy < scale; dy++)
                {
                    Array.Fill(pixels, Ink, (((y * scale) + dy) * side) + (x * scale), scale);
                }
            }
        }

        BitmapSource bitmap = BitmapSource.Create(side, side, 96, 96, PixelFormats.Gray8, null, pixels, side);

        // Frozen: the view model hands this straight to a binding, and a frozen bitmap needs no further
        // marshalling and cannot be mutated by whoever holds it next.
        bitmap.Freeze();
        return bitmap;
    }

    /// <summary>
    /// Error correction stays at M. The code is read off a screen from a few centimetres away, not off a
    /// printed sticker, so the higher levels would buy robustness nobody needs by making the code denser —
    /// and a denser code is a code with fewer pixels per module at the same size on screen.
    /// </summary>
    private static BitMatrix? TryEncode(string text)
    {
        var writer = new BarcodeWriterGeneric
        {
            Format = BarcodeFormat.QR_CODE,
            Options = new QrCodeEncodingOptions
            {
                ErrorCorrection = ZXing.QrCode.Internal.ErrorCorrectionLevel.M,
                CharacterSet = "UTF-8",

                // The invite is percent-escaped ASCII, so the ECI header announcing UTF-8 would be four
                // wasted bits and one more thing for an older scanner to trip over.
                DisableECI = true,
                Margin = QuietZoneModules,
            },
        };

        try
        {
            return writer.Encode(text);
        }
        catch (Exception ex) when (ex is WriterException or ArgumentException)
        {
            // Too long for version 40, or empty. Either way there is no code to show; the caller falls back
            // to the pairing code as text, which is the whole reason that row is still on screen.
            return null;
        }
    }
}
