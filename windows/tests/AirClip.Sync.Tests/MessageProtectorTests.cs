using System.Text.Json;
using AirClip.Core.Clipboard;
using AirClip.Core.Protocol;
using AirClip.Crypto;
using Xunit;

namespace AirClip.Sync.Tests;

public class MessageProtectorTests
{
    private static readonly DeviceIdentity Sender = new("device-a", "办公室台式机");

    [Fact]
    public void Text_survives_the_round_trip_and_the_wire_never_shows_it()
    {
        (SessionCrypto client, SessionCrypto server) = SessionPair();
        using (client)
        using (server)
        {
            ClipMessage original = ClipMessageFactory.Create(
                ClipboardContent.FromText("公司内部密码：hunter2"), Sender);
            ClipMessage armoured = MessageProtector.Protect(original, client);

            Assert.Equal(ProtocolConstants.AesGcmEncoding, armoured.Payload!.Encoding);
            Assert.DoesNotContain("hunter2", armoured.Payload.Content, StringComparison.Ordinal);
            Assert.DoesNotContain("hunter2", AirClipJson.Serialize(armoured), StringComparison.Ordinal);

            Assert.True(MessageProtector.TryUnprotect(
                armoured, server, out ClipMessage? plain, out string? error));
            Assert.Null(error);
            Assert.Equal(original.Payload, plain!.Payload);
            Assert.Equal(original, plain);
        }
    }

    [Fact]
    public void The_encrypted_message_still_matches_the_documented_schema()
    {
        (SessionCrypto client, SessionCrypto server) = SessionPair();
        using (client)
        using (server)
        {
            ClipMessage armoured = MessageProtector.Protect(
                ClipMessageFactory.Create(ClipboardContent.FromText("跨设备粘贴"), Sender), client);

            using JsonDocument document = JsonDocument.Parse(AirClipJson.Serialize(armoured));
            JsonElement root = document.RootElement;
            Assert.Equal("1.0", root.GetProperty("version").GetString());
            Assert.True(Guid.TryParse(root.GetProperty("msg_id").GetString(), out Guid _));
            Assert.Equal("device-a", root.GetProperty("device_id").GetString());
            Assert.Equal("办公室台式机", root.GetProperty("device_name").GetString());
            Assert.True(root.GetProperty("timestamp").GetInt64() > 0);
            Assert.Equal("text", root.GetProperty("type").GetString());
            Assert.Equal(64, root.GetProperty("hash").GetString()!.Length);

            JsonElement payload = root.GetProperty("payload");
            Assert.Equal("text/plain", payload.GetProperty("mime_type").GetString());
            Assert.Equal("aes-256-gcm", payload.GetProperty("encoding").GetString());
            Assert.NotEmpty(payload.GetProperty("content").GetString()!);

            // And what came off the wire is what decrypts, not just what was held in memory.
            ClipMessage? parsed = AirClipJson.Deserialize(AirClipJson.Serialize(armoured));
            Assert.True(MessageProtector.TryUnprotect(parsed!, server, out ClipMessage? plain, out string? _));
            Assert.Equal("跨设备粘贴", plain!.Payload!.Content);
            Assert.Equal(ProtocolConstants.Utf8Encoding, plain.Payload.Encoding);
        }
    }

    [Fact]
    public void Images_are_sealed_as_raw_bytes_rather_than_as_base64_text()
    {
        (SessionCrypto client, SessionCrypto server) = SessionPair();
        using (client)
        using (server)
        {
            byte[] png = new byte[4096];
            Random.Shared.NextBytes(png);
            ClipMessage original = ClipMessageFactory.Create(
                ClipboardContent.FromImage(new ClipboardImage(64, 32, png, ContentHasher.HashBytes(png))), Sender);
            ClipMessage armoured = MessageProtector.Protect(original, client);

            // The ciphertext is the length of the PNG plus GCM's overhead: had the base64 text been
            // encrypted instead, this would be a third larger for nothing.
            byte[] envelope = Convert.FromBase64String(armoured.Payload!.Content);
            Assert.Equal(png.Length + SessionCrypto.Overhead, envelope.Length);
            Assert.Equal(64, armoured.Payload.Width);
            Assert.Equal(32, armoured.Payload.Height);

            Assert.True(MessageProtector.TryUnprotect(
                armoured, server, out ClipMessage? plain, out string? _));
            Assert.Equal(ProtocolConstants.Base64Encoding, plain!.Payload!.Encoding);
            Assert.True(ClipMessageFactory.TryReadContent(plain, out ClipboardContent? content, out string? _));
            Assert.Equal(png, content!.Image!.Png);
            Assert.Equal(original.Hash, content.Hash);
        }
    }

    [Fact]
    public void Ping_and_ack_pass_through_untouched_when_they_carry_no_payload()
    {
        (SessionCrypto client, SessionCrypto server) = SessionPair();
        using (client)
        using (server)
        {
            var ping = new ClipMessage { DeviceId = Sender.Id, Type = ClipMessageType.Ping };
            Assert.Same(ping, MessageProtector.Protect(ping, client));
            Assert.True(MessageProtector.TryUnprotect(ping, server, out ClipMessage? plain, out string? _));
            Assert.Same(ping, plain);
        }
    }

    [Fact]
    public void A_cleartext_payload_is_refused_instead_of_being_trusted()
    {
        // Anyone on the LAN can open a socket; if omitting the encryption were enough to inject a
        // clipboard entry, the session key would be decoration.
        (SessionCrypto client, SessionCrypto server) = SessionPair();
        using (client)
        using (server)
        {
            ClipMessage injected = ClipMessageFactory.Create(ClipboardContent.FromText("rm -rf /"), Sender);

            Assert.False(MessageProtector.TryUnprotect(
                injected, server, out ClipMessage? plain, out string? error));
            Assert.Null(plain);
            Assert.Equal("载荷未加密，已拒绝", error);
        }
    }

    [Fact]
    public void Garbage_in_the_content_field_is_reported_rather_than_thrown()
    {
        (SessionCrypto client, SessionCrypto server) = SessionPair();
        using (client)
        using (server)
        {
            ClipMessage message = ClipMessageFactory.Create(ClipboardContent.FromText("x"), Sender);
            ClipMessage broken = message with
            {
                Payload = message.Payload! with
                {
                    Content = "not base64 at all!!",
                    Encoding = ProtocolConstants.AesGcmEncoding,
                },
            };

            Assert.False(MessageProtector.TryUnprotect(broken, server, out ClipMessage? _, out string? error));
            Assert.Equal("密文不是合法的 base64", error);
        }
    }

    [Theory]
    [InlineData("hash")]
    [InlineData("device")]
    [InlineData("timestamp")]
    [InlineData("id")]
    [InlineData("type")]
    public void Rewriting_a_cleartext_header_field_breaks_decryption(string field)
    {
        (SessionCrypto client, SessionCrypto server) = SessionPair();
        using (client)
        using (server)
        {
            ClipMessage armoured = MessageProtector.Protect(
                ClipMessageFactory.Create(ClipboardContent.FromText("原文"), Sender), client);
            ClipMessage tampered = field switch
            {
                "hash" => armoured with { Hash = new string('0', 64) },
                "device" => armoured with { DeviceId = "device-evil" },
                "timestamp" => armoured with { Timestamp = armoured.Timestamp + 1 },
                "id" => armoured with { MessageId = Guid.NewGuid().ToString("D") },
                _ => armoured with { Type = ClipMessageType.Image },
            };

            Assert.False(MessageProtector.TryUnprotect(tampered, server, out ClipMessage? _, out string? error));
            Assert.Equal("解密失败（密钥不符、报文被改动或重放）", error);
        }
    }

    [Fact]
    public void A_peer_with_a_different_pairing_code_cannot_read_anything()
    {
        byte[] clientChallenge = SessionCrypto.CreateChallenge();
        byte[] serverChallenge = SessionCrypto.CreateChallenge();
        using SessionCrypto mine = SessionCrypto.Establish(
            PairingKey.Create(), clientChallenge, serverChallenge, isServer: false);
        using SessionCrypto theirs = SessionCrypto.Establish(
            PairingKey.Create(), clientChallenge, serverChallenge, isServer: true);

        ClipMessage armoured = MessageProtector.Protect(
            ClipMessageFactory.Create(ClipboardContent.FromText("邻居看不到"), Sender), mine);

        Assert.False(MessageProtector.TryUnprotect(armoured, theirs, out ClipMessage? _, out string? error));
        Assert.Equal("解密失败（密钥不符、报文被改动或重放）", error);
    }

    [Fact]
    public void The_display_name_is_the_one_header_field_left_unbound()
    {
        // Documented on purpose rather than by accident: device_name is decoration in the peer list, so
        // it is deliberately outside the tag. Every field that decides *what happens* to the clipboard is
        // inside it, which the tampering test above proves field by field.
        (SessionCrypto client, SessionCrypto server) = SessionPair();
        using (client)
        using (server)
        {
            ClipMessage armoured = MessageProtector.Protect(
                ClipMessageFactory.Create(ClipboardContent.FromText("原文"), Sender), client);
            ClipMessage renamed = armoured with { DeviceName = "改了个名字" };

            Assert.True(MessageProtector.TryUnprotect(renamed, server, out ClipMessage? plain, out string? _));
            Assert.Equal("原文", plain!.Payload!.Content);
        }
    }

    private static (SessionCrypto Client, SessionCrypto Server) SessionPair()
    {
        PairingKey key = PairingKey.Create();
        byte[] clientChallenge = SessionCrypto.CreateChallenge();
        byte[] serverChallenge = SessionCrypto.CreateChallenge();
        return (
            SessionCrypto.Establish(key, clientChallenge, serverChallenge, isServer: false),
            SessionCrypto.Establish(key, clientChallenge, serverChallenge, isServer: true));
    }
}
