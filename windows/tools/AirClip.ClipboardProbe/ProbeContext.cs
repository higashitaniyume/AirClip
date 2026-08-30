using System.Collections.Concurrent;
using System.IO;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using AirClip.Core.Clipboard;
using AirClip.Core.Sync;
using AirClip.Platform.Windows;
using Microsoft.Extensions.Logging;

namespace AirClip.ClipboardProbe;

internal sealed class ProbeContext : IDisposable
{
    private ProbeContext(Win32ClipboardHost host, Win32ClipboardMonitor monitor, Win32ClipboardWriter writer, ClipboardSyncEngine engine)
    {
        Host = host;
        Monitor = monitor;
        Writer = writer;
        Engine = engine;
        engine.LocalClipboardPublished += (_, e) => Published.Add(e.Content);
    }

    internal Win32ClipboardHost Host { get; }

    internal Win32ClipboardMonitor Monitor { get; }

    internal Win32ClipboardWriter Writer { get; }

    internal ClipboardSyncEngine Engine { get; }

    internal BlockingCollection<ClipboardContent> Published { get; } = new();

    internal static ProbeContext Create(LogLevel logLevel)
    {
        var options = new ClipboardOptions { DebounceInterval = TimeSpan.FromMilliseconds(80) };
        var host = new Win32ClipboardHost(new ConsoleLogger<Win32ClipboardHost>(logLevel));
        var monitor = new Win32ClipboardMonitor(host, options, logger: new ConsoleLogger<Win32ClipboardMonitor>(logLevel));
        var writer = new Win32ClipboardWriter(host, new ConsoleLogger<Win32ClipboardWriter>(logLevel));
        var guard = new LoopGuard(remoteWriteSuppression: TimeSpan.FromSeconds(2));
        var engine = new ClipboardSyncEngine(
            monitor, writer, guard, options, new ConsoleLogger<ClipboardSyncEngine>(logLevel));

        return new ProbeContext(host, monitor, writer, engine);
    }

    /// <summary>Builds an opaque gradient so a clipboard round-trip must reproduce the exact hash.</summary>
    internal static ClipboardContent CreateTestImage(int width, int height)
    {
        byte[] pixels = new byte[width * height * 4];
        for (int y = 0; y < height; y++)
        {
            for (int x = 0; x < width; x++)
            {
                int offset = ((y * width) + x) * 4;
                pixels[offset] = (byte)(x * 255 / Math.Max(1, width - 1));
                pixels[offset + 1] = (byte)(y * 255 / Math.Max(1, height - 1));
                pixels[offset + 2] = 0x40;
                pixels[offset + 3] = 255;
            }
        }

        BitmapSource source = BitmapSource.Create(width, height, 96, 96, PixelFormats.Bgra32, null, pixels, width * 4);
        var encoder = new PngBitmapEncoder();
        encoder.Frames.Add(BitmapFrame.Create(source));

        using var stream = new MemoryStream();
        encoder.Save(stream);

        return ClipboardContent.FromImage(
            new ClipboardImage(width, height, stream.ToArray(), ContentHasher.HashImagePixels(width, height, pixels)));
    }

    public void Dispose()
    {
        Engine.Dispose();
        Monitor.Dispose();
        Host.Dispose();
        Published.Dispose();
    }
}
