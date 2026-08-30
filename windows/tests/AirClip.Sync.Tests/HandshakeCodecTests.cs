using System.Text;
using AirClip.Crypto;
using AirClip.Net;
using Xunit;

namespace AirClip.Sync.Tests;

public class HandshakeCodecTests
{
    private static readonly byte[] Challenge = SessionCrypto.CreateChallenge();

    [Fact]
    public void A_hello_round_trips_including_a_chinese_device_name()
    {
        var hello = new HandshakeHello("1a2b3c4d", "办公室台式机", "windows", "A1B2C3D4", Challenge);

        Assert.True(HandshakeCodec.TryReadHello(HandshakeCodec.WriteHello(hello), out HandshakeHello? parsed));
        Assert.Equal("1a2b3c4d", parsed!.DeviceId);
        Assert.Equal("办公室台式机", parsed.DeviceName);
        Assert.Equal("windows", parsed.Platform);
        Assert.Equal("A1B2C3D4", parsed.Fingerprint);
        Assert.Equal(Challenge, parsed.Challenge);
    }

    [Fact]
    public void A_hello_with_empty_fields_is_still_readable()
    {
        // A peer that publishes no fingerprint is allowed; the session still has to be able to read it.
        var hello = new HandshakeHello(string.Empty, string.Empty, string.Empty, string.Empty, Challenge);

        Assert.True(HandshakeCodec.TryReadHello(HandshakeCodec.WriteHello(hello), out HandshakeHello? parsed));
        Assert.Equal(string.Empty, parsed!.Fingerprint);
        Assert.Equal(Challenge, parsed.Challenge);
    }

    [Fact]
    public void An_absurdly_long_name_is_truncated_on_a_character_boundary()
    {
        var hello = new HandshakeHello("id", new string('测', 200), "android", "A1B2C3D4", Challenge);

        Assert.True(HandshakeCodec.TryReadHello(HandshakeCodec.WriteHello(hello), out HandshakeHello? parsed));

        // Never a replacement character: the truncation stops before a half-written character rather than
        // at the byte limit, so what arrives is shorter but still exactly what the user typed.
        Assert.DoesNotContain('�', parsed!.DeviceName);
        Assert.Equal(new string('测', parsed.DeviceName.Length), parsed.DeviceName);
        Assert.True(Encoding.UTF8.GetByteCount(parsed.DeviceName) <= 256);
        Assert.Equal("android", parsed.Platform);
    }

    [Fact]
    public void A_proof_round_trips()
    {
        byte[] mac = new byte[32];
        Random.Shared.NextBytes(mac);

        Assert.True(HandshakeCodec.TryReadProof(HandshakeCodec.WriteProof(mac), out byte[]? parsed));
        Assert.Equal(mac, parsed);
    }

    [Fact]
    public void A_rejection_carries_its_reason_back()
    {
        Assert.True(HandshakeCodec.TryReadReject(HandshakeCodec.WriteReject("配对码不一致"), out string? reason));
        Assert.Equal("配对码不一致", reason);
    }

    [Fact]
    public void Frame_types_do_not_answer_for_each_other()
    {
        byte[] hello = HandshakeCodec.WriteHello(
            new HandshakeHello("id", "name", "windows", "A1B2C3D4", Challenge));
        byte[] proof = HandshakeCodec.WriteProof(new byte[32]);
        byte[] reject = HandshakeCodec.WriteReject("nope");

        Assert.True(HandshakeCodec.TryReadType(hello, out HandshakeFrameType type));
        Assert.Equal(HandshakeFrameType.Hello, type);
        Assert.False(HandshakeCodec.TryReadHello(proof, out HandshakeHello? _));
        Assert.False(HandshakeCodec.TryReadProof(hello, out byte[]? _));
        Assert.False(HandshakeCodec.TryReadReject(proof, out string? _));
        Assert.True(HandshakeCodec.TryReadReject(reject, out string? _));
    }

    [Fact]
    public void A_challenge_of_the_wrong_length_is_refused()
    {
        // The challenge is half the session key's salt. A short one would silently weaken every message
        // that follows, so it is a parse failure rather than something to pad out.
        byte[] frame = HandshakeCodec.WriteHello(
            new HandshakeHello("id", "name", "windows", "A1B2C3D4", new byte[16]));

        Assert.False(HandshakeCodec.TryReadHello(frame, out HandshakeHello? _));
    }

    [Fact]
    public void A_frame_from_another_protocol_is_refused_rather_than_misread()
    {
        Assert.False(HandshakeCodec.TryReadType(Encoding.ASCII.GetBytes("GET / HTTP/1.1"), out _));
        Assert.False(HandshakeCodec.TryReadType([], out _));
        Assert.False(HandshakeCodec.TryReadType([0x41, 0x43, 0x4C, 0x50, 1], out _));
    }

    [Fact]
    public void A_frame_from_a_future_version_is_refused()
    {
        byte[] frame = HandshakeCodec.WriteHello(
            new HandshakeHello("id", "name", "windows", "A1B2C3D4", Challenge));
        frame[4] = HandshakeCodec.Version + 1;

        Assert.False(HandshakeCodec.TryReadType(frame, out _));
        Assert.False(HandshakeCodec.TryReadHello(frame, out HandshakeHello? _));
    }

    [Theory]
    [InlineData(1)]
    [InlineData(7)]
    [InlineData(20)]
    public void A_truncated_frame_is_refused(int keep)
    {
        byte[] frame = HandshakeCodec.WriteHello(
            new HandshakeHello("1a2b3c4d", "办公室台式机", "windows", "A1B2C3D4", Challenge));

        Assert.False(HandshakeCodec.TryReadHello(frame.AsSpan(0, keep), out HandshakeHello? _));
    }

    [Fact]
    public void A_string_length_that_runs_past_the_frame_is_refused()
    {
        // Hand-built rather than produced by the writer: this is the shape a hostile peer would send, a
        // length prefix that promises more than the frame holds.
        byte[] frame = [0x41, 0x43, 0x4C, 0x50, HandshakeCodec.Version, (byte)HandshakeFrameType.Hello, 0xFF, 0xFF];

        Assert.False(HandshakeCodec.TryReadHello(frame, out HandshakeHello? _));
        Assert.False(HandshakeCodec.TryReadReject(frame, out string? _));
    }
}
