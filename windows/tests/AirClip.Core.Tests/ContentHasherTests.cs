using AirClip.Core.Clipboard;
using Xunit;

namespace AirClip.Core.Tests;

public sealed class ContentHasherTests
{
    [Fact]
    public void TextHashIsPlainSha256OfUtf8Bytes()
    {
        Assert.Equal(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            ContentHasher.HashText("hello"));
    }

    [Fact]
    public void TextHashIsStableAndCaseSensitive()
    {
        Assert.Equal(ContentHasher.HashText("AirClip"), ContentHasher.HashText("AirClip"));
        Assert.NotEqual(ContentHasher.HashText("AirClip"), ContentHasher.HashText("airclip"));
    }

    [Fact]
    public void ImageHashCoversDimensionsAndPixels()
    {
        byte[] pixels = [0, 0, 0, 255, 255, 255, 255, 255];

        string twoByOne = ContentHasher.HashImagePixels(2, 1, pixels);
        string oneByTwo = ContentHasher.HashImagePixels(1, 2, pixels);

        Assert.Equal(twoByOne, ContentHasher.HashImagePixels(2, 1, pixels));
        Assert.NotEqual(twoByOne, oneByTwo);
        Assert.NotEqual(twoByOne, ContentHasher.HashImagePixels(2, 1, [0, 0, 0, 255, 0, 255, 255, 255]));
    }

    [Fact]
    public void ImageHashIsDomainSeparatedFromTextHash()
    {
        Assert.NotEqual(ContentHasher.HashBytes([1, 2, 3]), ContentHasher.HashImagePixels(1, 1, [1, 2, 3, 255]));
    }
}
