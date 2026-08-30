using System.Text.Json;
using AirClip.Core.Clipboard;
using AirClip.Core.Protocol;
using Xunit;

namespace AirClip.Core.Tests;

public sealed class ProtocolTests
{
    private static readonly DeviceIdentity Device = new("win-desktop-01", "My Windows PC");

    [Fact]
    public void SerializesTextMessageWithSnakeCaseWireFormat()
    {
        ClipMessage message = ClipMessageFactory.Create(ClipboardContent.FromText("hello"), Device);

        using JsonDocument document = JsonDocument.Parse(AirClipJson.Serialize(message));
        JsonElement root = document.RootElement;

        Assert.Equal("1.0", root.GetProperty("version").GetString());
        Assert.Equal("win-desktop-01", root.GetProperty("device_id").GetString());
        Assert.Equal("My Windows PC", root.GetProperty("device_name").GetString());
        Assert.Equal("text", root.GetProperty("type").GetString());
        Assert.Equal(ContentHasher.HashText("hello"), root.GetProperty("hash").GetString());
        Assert.Equal("hello", root.GetProperty("payload").GetProperty("content").GetString());
        Assert.Equal("text/plain", root.GetProperty("payload").GetProperty("mime_type").GetString());
        Assert.Equal("utf-8", root.GetProperty("payload").GetProperty("encoding").GetString());
        Assert.True(root.GetProperty("timestamp").GetInt64() > 0);
        Assert.True(Guid.TryParse(root.GetProperty("msg_id").GetString(), out _));
    }

    [Fact]
    public void RoundTripsTextThroughTheWire()
    {
        ClipboardContent original = ClipboardContent.FromText("多行\n文本 with unicode ✅");
        string json = AirClipJson.Serialize(ClipMessageFactory.Create(original, Device));

        ClipMessage? parsed = AirClipJson.Deserialize(json);
        Assert.NotNull(parsed);
        Assert.True(ClipMessageFactory.TryReadContent(parsed, out ClipboardContent? content, out string? error), error);

        Assert.Equal(ClipboardContentKind.Text, content!.Kind);
        Assert.Equal(original.Text, content.Text);
        Assert.Equal(original.Hash, content.Hash);
    }

    [Fact]
    public void RoundTripsImageThroughTheWire()
    {
        byte[] png = [1, 2, 3, 4, 5];
        var image = new ClipboardImage(4, 2, png, "deadbeef");
        string json = AirClipJson.Serialize(ClipMessageFactory.Create(ClipboardContent.FromImage(image), Device));

        ClipMessage? parsed = AirClipJson.Deserialize(json);
        Assert.NotNull(parsed);
        Assert.Equal(ClipMessageType.Image, parsed!.Type);
        Assert.True(ClipMessageFactory.TryReadContent(parsed, out ClipboardContent? content, out string? error), error);

        Assert.Equal(ClipboardContentKind.Image, content!.Kind);
        Assert.Equal(4, content.Image!.Width);
        Assert.Equal(2, content.Image.Height);
        Assert.Equal(png, content.Image.Png);
        Assert.Equal("deadbeef", content.Hash);
    }

    [Fact]
    public void RejectsTamperedTextHash()
    {
        ClipMessage message = ClipMessageFactory.Create(ClipboardContent.FromText("hello"), Device) with
        {
            Hash = new string('0', 64),
        };

        Assert.False(ClipMessageFactory.TryReadContent(message, out ClipboardContent? content, out string? error));
        Assert.Null(content);
        Assert.Equal("text hash mismatch", error);
    }

    [Fact]
    public void RejectsNonClipboardMessageTypes()
    {
        var ping = new ClipMessage { Type = ClipMessageType.Ping, Payload = new ClipPayload() };

        Assert.False(ClipMessageFactory.TryReadContent(ping, out _, out string? error));
        Assert.Contains("Ping", error);
    }
}
