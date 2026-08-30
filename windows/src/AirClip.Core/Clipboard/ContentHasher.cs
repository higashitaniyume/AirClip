using System.Buffers.Binary;
using System.Security.Cryptography;
using System.Text;

namespace AirClip.Core.Clipboard;

/// <summary>
/// Canonical SHA-256 content hashes. Both platforms must produce identical hashes for identical
/// content, otherwise the loop guard cannot recognise an echo of data it just sent.
/// </summary>
public static class ContentHasher
{
    /// <summary>Domain separator + version tag; bump when the canonical image form changes.</summary>
    private const string ImageDomain = "airclip/img1";

    public static string HashText(string text)
    {
        ArgumentNullException.ThrowIfNull(text);
        return ToHex(SHA256.HashData(Encoding.UTF8.GetBytes(text)));
    }

    public static string HashBytes(ReadOnlySpan<byte> data) => ToHex(SHA256.HashData(data));

    /// <summary>
    /// Hashes raw pixels rather than encoded bytes: PNG encoders differ per platform, pixels do not.
    /// Canonical form is top-down rows of straight-alpha BGRA quads.
    /// </summary>
    public static string HashImagePixels(int width, int height, ReadOnlySpan<byte> bgra32TopDown)
    {
        using var hash = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
        hash.AppendData(Encoding.ASCII.GetBytes(ImageDomain));

        Span<byte> dimensions = stackalloc byte[8];
        BinaryPrimitives.WriteInt32LittleEndian(dimensions[..4], width);
        BinaryPrimitives.WriteInt32LittleEndian(dimensions[4..], height);
        hash.AppendData(dimensions);

        hash.AppendData(bgra32TopDown);
        return ToHex(hash.GetHashAndReset());
    }

    private static string ToHex(ReadOnlySpan<byte> value) => Convert.ToHexString(value).ToLowerInvariant();
}
