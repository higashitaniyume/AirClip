using System.Buffers.Binary;
using System.Text;
using AirClip.Crypto;

namespace AirClip.Net;

public enum HandshakeFrameType : byte
{
    Hello = 1,
    Proof = 2,
    Reject = 3,
}

/// <summary>Who is on the other end, and the random half of the session key they contributed.</summary>
public sealed record HandshakeHello(
    string DeviceId, string DeviceName, string Platform, string Fingerprint, byte[] Challenge);

/// <summary>
/// The handshake's own binary frames, sent before any clipboard traffic. They are deliberately <em>not</em>
/// <c>ClipMessage</c> JSON: the protocol's message schema is fixed and has no room for a challenge or a
/// MAC, and bending <c>type</c> or <c>payload</c> to carry them would make AirClip's wire format subtly
/// different from the one it documents. A binary frame on the same socket keeps the JSON schema exact.
/// </summary>
public static class HandshakeCodec
{
    public const byte Version = 1;
    private const int MaxStringBytes = 256;
    private static readonly byte[] Magic = "ACLP"u8.ToArray();

    public static byte[] WriteHello(HandshakeHello hello)
    {
        ArgumentNullException.ThrowIfNull(hello);
        var frame = new List<byte>(160);
        frame.AddRange(Magic);
        frame.Add(Version);
        frame.Add((byte)HandshakeFrameType.Hello);
        WriteString(frame, hello.DeviceId);
        WriteString(frame, hello.DeviceName);
        WriteString(frame, hello.Platform);
        WriteString(frame, hello.Fingerprint);
        frame.AddRange(hello.Challenge);
        return [.. frame];
    }

    public static byte[] WriteProof(ReadOnlySpan<byte> mac)
    {
        var frame = new List<byte>(mac.Length + 7);
        frame.AddRange(Magic);
        frame.Add(Version);
        frame.Add((byte)HandshakeFrameType.Proof);
        frame.Add((byte)mac.Length);
        frame.AddRange(mac);
        return [.. frame];
    }

    public static byte[] WriteReject(string reason)
    {
        var frame = new List<byte>(64);
        frame.AddRange(Magic);
        frame.Add(Version);
        frame.Add((byte)HandshakeFrameType.Reject);
        WriteString(frame, reason);
        return [.. frame];
    }

    public static bool TryReadType(ReadOnlySpan<byte> frame, out HandshakeFrameType type)
    {
        type = default;
        if (frame.Length < 6 || !frame[..Magic.Length].SequenceEqual(Magic) || frame[4] != Version)
        {
            return false;
        }

        type = (HandshakeFrameType)frame[5];
        return true;
    }

    public static bool TryReadHello(ReadOnlySpan<byte> frame, out HandshakeHello? hello)
    {
        hello = null;
        if (!TryReadType(frame, out HandshakeFrameType type) || type != HandshakeFrameType.Hello)
        {
            return false;
        }

        int cursor = 6;
        if (!TryReadString(frame, ref cursor, out string? id)
            || !TryReadString(frame, ref cursor, out string? name)
            || !TryReadString(frame, ref cursor, out string? platform)
            || !TryReadString(frame, ref cursor, out string? fingerprint)
            || frame.Length - cursor != SessionCrypto.ChallengeSize)
        {
            return false;
        }

        hello = new HandshakeHello(id!, name!, platform!, fingerprint!, frame[cursor..].ToArray());
        return true;
    }

    public static bool TryReadProof(ReadOnlySpan<byte> frame, out byte[]? mac)
    {
        mac = null;
        if (!TryReadType(frame, out HandshakeFrameType type) || type != HandshakeFrameType.Proof
            || frame.Length < 7)
        {
            return false;
        }

        int length = frame[6];
        if (frame.Length - 7 != length)
        {
            return false;
        }

        mac = frame[7..].ToArray();
        return true;
    }

    public static bool TryReadReject(ReadOnlySpan<byte> frame, out string? reason)
    {
        reason = null;
        if (!TryReadType(frame, out HandshakeFrameType type) || type != HandshakeFrameType.Reject)
        {
            return false;
        }

        int cursor = 6;
        return TryReadString(frame, ref cursor, out reason);
    }

    private static void WriteString(List<byte> frame, string? value)
    {
        byte[] bytes = Encoding.UTF8.GetBytes(value ?? string.Empty);
        if (bytes.Length > MaxStringBytes)
        {
            // Trimmed back to a character boundary rather than to the byte limit: a device called
            // "办公室…" spends three bytes per character, and cutting one in half would put an invalid
            // UTF-8 sequence on the wire for the far end to render as a replacement glyph.
            int limit = MaxStringBytes;
            while (limit > 0 && (bytes[limit] & 0xC0) == 0x80)
            {
                limit--;
            }

            bytes = bytes[..limit];
        }

        Span<byte> length = stackalloc byte[sizeof(ushort)];
        BinaryPrimitives.WriteUInt16BigEndian(length, (ushort)bytes.Length);
        frame.AddRange(length);
        frame.AddRange(bytes);
    }

    private static bool TryReadString(ReadOnlySpan<byte> frame, ref int cursor, out string? value)
    {
        value = null;
        if (frame.Length - cursor < sizeof(ushort))
        {
            return false;
        }

        int length = BinaryPrimitives.ReadUInt16BigEndian(frame[cursor..]);
        cursor += sizeof(ushort);
        if (length > MaxStringBytes || frame.Length - cursor < length)
        {
            return false;
        }

        value = Encoding.UTF8.GetString(frame.Slice(cursor, length));
        cursor += length;
        return true;
    }
}
