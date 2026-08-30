using System.Text;

namespace AirClip.Core.Clipboard;

public enum ClipboardContentKind
{
    Text,
    Image,
}

/// <summary>PNG bytes for transport; <paramref name="PixelHash"/> is the platform-neutral content hash.</summary>
public sealed record ClipboardImage(int Width, int Height, byte[] Png, string PixelHash);

public sealed class ClipboardContent
{
    private ClipboardContent(ClipboardContentKind kind, string? text, ClipboardImage? image, string hash)
    {
        Kind = kind;
        Text = text;
        Image = image;
        Hash = hash;
    }

    public ClipboardContentKind Kind { get; }

    public string? Text { get; }

    public ClipboardImage? Image { get; }

    public string Hash { get; }

    public int ByteSize => Kind == ClipboardContentKind.Text
        ? Encoding.UTF8.GetByteCount(Text!)
        : Image!.Png.Length;

    public static ClipboardContent FromText(string text)
    {
        ArgumentNullException.ThrowIfNull(text);
        return new ClipboardContent(ClipboardContentKind.Text, text, null, ContentHasher.HashText(text));
    }

    public static ClipboardContent FromImage(ClipboardImage image)
    {
        ArgumentNullException.ThrowIfNull(image);
        return new ClipboardContent(ClipboardContentKind.Image, null, image, image.PixelHash);
    }

    public override string ToString() => Kind == ClipboardContentKind.Text
        ? $"text {ByteSize}B #{Hash[..8]}"
        : $"image {Image!.Width}x{Image.Height} {ByteSize}B #{Hash[..8]}";
}
