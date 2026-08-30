using System.IO;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using AirClip.App.Mvvm;
using AirClip.App.Services;
using AirClip.Core.Clipboard;

namespace AirClip.App.ViewModels;

public sealed class HistoryItemViewModel : ObservableObject
{
    private const int PreviewLength = 160;

    private ImageSource? _thumbnail;
    private bool _thumbnailResolved;

    public HistoryItemViewModel(ClipboardContent content, ClipDirection direction, DateTimeOffset timestamp)
    {
        Content = content;
        Direction = direction;
        Timestamp = timestamp;
    }

    public ClipboardContent Content { get; }

    public ClipDirection Direction { get; }

    public DateTimeOffset Timestamp { get; }

    public bool IsImage => Content.Kind == ClipboardContentKind.Image;

    public string TimeLabel => Timestamp.ToLocalTime().ToString("HH:mm:ss");

    public string DirectionLabel => Direction == ClipDirection.Local ? "本机复制" : "远端接收";

    public string KindLabel => IsImage ? "图片" : "文本";

    public string HashLabel => $"#{Content.Hash[..8]}";

    public string SizeLabel => FormatBytes(Content.ByteSize);

    public string Preview => IsImage
        ? $"{Content.Image!.Width} × {Content.Image.Height} PNG"
        : Shorten(Content.Text!);

    /// <summary>Decoded on first request so a long history does not hold hundreds of bitmaps.</summary>
    public ImageSource? Thumbnail
    {
        get
        {
            if (_thumbnailResolved)
            {
                return _thumbnail;
            }

            _thumbnailResolved = true;
            _thumbnail = IsImage ? TryDecode(Content.Image!.Png) : null;
            return _thumbnail;
        }
    }

    private static ImageSource? TryDecode(byte[] png)
    {
        try
        {
            var bitmap = new BitmapImage();
            bitmap.BeginInit();
            bitmap.CacheOption = BitmapCacheOption.OnLoad;
            bitmap.DecodePixelHeight = 40;
            bitmap.StreamSource = new MemoryStream(png);
            bitmap.EndInit();
            bitmap.Freeze();
            return bitmap;
        }
        catch (Exception ex) when (ex is NotSupportedException or FileFormatException or ArgumentException)
        {
            return null;
        }
    }

    private static string Shorten(string text)
    {
        string single = text.ReplaceLineEndings(" ⏎ ").Trim();
        return single.Length <= PreviewLength ? single : string.Concat(single.AsSpan(0, PreviewLength), "…");
    }

    private static string FormatBytes(int bytes) => bytes switch
    {
        < 1024 => $"{bytes} B",
        < 1024 * 1024 => $"{bytes / 1024.0:0.#} KB",
        _ => $"{bytes / (1024.0 * 1024.0):0.##} MB",
    };
}
