using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using AirClip.App.Services;
using AirClip.Platform.Windows;

namespace AirClip.App.Tray;

/// <summary>
/// Draws the tray and window icons at runtime instead of shipping .ico assets: one vector definition
/// covers every DPI, and the glyph is tinted by sync state so the tray shows status at a glance.
/// Must be called from the UI thread because it uses WPF drawing primitives.
/// </summary>
public static class TrayArtwork
{
    private static readonly Brush Offline = Frozen(0xFF, 0x6B, 0x72, 0x80);
    private static readonly Brush Connected = Frozen(0xFF, 0x3B, 0x82, 0xF6);
    private static readonly Brush Paused = Frozen(0xFF, 0xF5, 0x9E, 0x0B);
    private static readonly Brush Glyph = Frozen(0xFF, 0xFF, 0xFF, 0xFF);

    /// <summary>BGRA pixels sized for the notification area at the current DPI.</summary>
    public static TrayIconImage CreateTrayIcon(SyncState state)
    {
        (int width, int height) = Win32TrayIcon.PreferredIconSize();
        RenderTargetBitmap rendered = Render(width, height, state);

        // RenderTargetBitmap is premultiplied; CreateIconIndirect expects straight alpha.
        var straight = new FormatConvertedBitmap(rendered, PixelFormats.Bgra32, null, 0);
        byte[] pixels = new byte[width * height * 4];
        straight.CopyPixels(pixels, width * 4, 0);
        return new TrayIconImage(width, height, pixels);
    }

    /// <summary>Icon for the window chrome, task bar and Alt+Tab.</summary>
    public static ImageSource CreateWindowIcon(SyncState state = SyncState.Connected)
    {
        RenderTargetBitmap rendered = Render(64, 64, state);
        rendered.Freeze();
        return rendered;
    }

    private static RenderTargetBitmap Render(int width, int height, SyncState state)
    {
        var visual = new DrawingVisual();
        using (DrawingContext context = visual.RenderOpen())
        {
            double w = width;
            double h = height;
            Brush background = state switch
            {
                SyncState.Connected => Connected,
                SyncState.Paused => Paused,
                _ => Offline,
            };

            // Rounded plate, then a clipboard tab and two "lines of content" that stay legible at 16px.
            context.DrawRoundedRectangle(
                background, null, new Rect(w * 0.05, h * 0.05, w * 0.90, h * 0.90), w * 0.24, h * 0.24);
            context.DrawRoundedRectangle(
                Glyph, null, new Rect(w * 0.34, h * 0.15, w * 0.32, h * 0.13), w * 0.05, h * 0.05);
            context.DrawRoundedRectangle(
                Glyph, null, new Rect(w * 0.27, h * 0.44, w * 0.46, h * 0.10), w * 0.05, h * 0.05);
            context.DrawRoundedRectangle(
                Glyph, null, new Rect(w * 0.27, h * 0.64, w * 0.31, h * 0.10), w * 0.05, h * 0.05);
        }

        var bitmap = new RenderTargetBitmap(width, height, 96, 96, PixelFormats.Pbgra32);
        bitmap.Render(visual);
        return bitmap;
    }

    private static Brush Frozen(byte a, byte r, byte g, byte b)
    {
        var brush = new SolidColorBrush(Color.FromArgb(a, r, g, b));
        brush.Freeze();
        return brush;
    }
}
