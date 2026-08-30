using System.Text.Json.Serialization;

namespace AirClip.Core.Protocol;

public static class ProtocolConstants
{
    public const string Version = "1.0";
    public const string TextMimeType = "text/plain";
    public const string ImageMimeType = "image/png";
    public const string Utf8Encoding = "utf-8";
    public const string Base64Encoding = "base64";

    /// <summary>
    /// Marks a payload whose <c>content</c> is base64 of <c>nonce || ciphertext || tag</c> instead of the
    /// clipboard data itself. It occupies the same <c>encoding</c> field as the cleartext values, so the
    /// wire schema is unchanged and a peer that does not know the value simply fails to read the message.
    /// </summary>
    public const string AesGcmEncoding = "aes-256-gcm";

}

public enum ClipMessageType
{
    Text,
    Image,
    Ping,
    Ack,
}

public sealed record ClipPayload
{
    [JsonPropertyName("content")]
    public string Content { get; init; } = string.Empty;

    [JsonPropertyName("mime_type")]
    public string MimeType { get; init; } = ProtocolConstants.TextMimeType;

    [JsonPropertyName("encoding")]
    public string Encoding { get; init; } = ProtocolConstants.Utf8Encoding;

    [JsonPropertyName("width")]
    public int? Width { get; init; }

    [JsonPropertyName("height")]
    public int? Height { get; init; }
}

public sealed record ClipMessage
{
    [JsonPropertyName("version")]
    public string Version { get; init; } = ProtocolConstants.Version;

    [JsonPropertyName("msg_id")]
    public string MessageId { get; init; } = Guid.NewGuid().ToString("D");

    [JsonPropertyName("device_id")]
    public string DeviceId { get; init; } = string.Empty;

    [JsonPropertyName("device_name")]
    public string DeviceName { get; init; } = string.Empty;

    [JsonPropertyName("timestamp")]
    public long Timestamp { get; init; }

    [JsonPropertyName("type")]
    public ClipMessageType Type { get; init; }

    /// <summary>
    /// Canonical content hash from <see cref="Clipboard.ContentHasher"/>, not a hash of the wire
    /// bytes: peers compare it against their own recently-seen hashes to break sync loops.
    /// </summary>
    [JsonPropertyName("hash")]
    public string Hash { get; init; } = string.Empty;

    [JsonPropertyName("payload")]
    public ClipPayload? Payload { get; init; }
}

public sealed record DeviceIdentity(string Id, string Name);
