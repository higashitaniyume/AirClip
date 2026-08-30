using System.Text;
using AirClip.Crypto;
using Xunit;

namespace AirClip.Sync.Tests;

public class SessionCryptoTests
{
    private static readonly byte[] Header = Encoding.UTF8.GetBytes("1|msg|device|0|text|hash");

    [Fact]
    public void Both_directions_round_trip_under_one_pairing_code()
    {
        (SessionCrypto client, SessionCrypto server) = Pair();
        using (client)
        using (server)
        {
            byte[] up = client.Seal(Encoding.UTF8.GetBytes("你好，剪贴板"), Header);
            byte[] down = server.Seal(Encoding.UTF8.GetBytes("收到了"), Header);

            Assert.True(server.TryOpen(up, Header, out byte[]? atServer));
            Assert.True(client.TryOpen(down, Header, out byte[]? atClient));
            Assert.Equal("你好，剪贴板", Encoding.UTF8.GetString(atServer!));
            Assert.Equal("收到了", Encoding.UTF8.GetString(atClient!));
        }
    }

    [Fact]
    public void The_two_directions_do_not_share_a_key()
    {
        // Feeding a client-to-server frame back to the client must fail, or an attacker could echo a
        // message at its own author and have it accepted as if the far side had said it.
        (SessionCrypto client, SessionCrypto server) = Pair();
        using (client)
        using (server)
        {
            byte[] envelope = client.Seal(Encoding.UTF8.GetBytes("reflect me"), Header);
            Assert.False(client.TryOpen(envelope, Header, out byte[]? _));
        }
    }

    [Fact]
    public void A_different_pairing_code_cannot_read_the_frame()
    {
        byte[] clientChallenge = SessionCrypto.CreateChallenge();
        byte[] serverChallenge = SessionCrypto.CreateChallenge();
        using SessionCrypto sender = SessionCrypto.Establish(
            PairingKey.Create(), clientChallenge, serverChallenge, isServer: false);
        using SessionCrypto stranger = SessionCrypto.Establish(
            PairingKey.Create(), clientChallenge, serverChallenge, isServer: true);

        byte[] envelope = sender.Seal(Encoding.UTF8.GetBytes("secret"), Header);
        Assert.False(stranger.TryOpen(envelope, Header, out byte[]? plaintext));
        Assert.Null(plaintext);
    }

    [Theory]
    [InlineData(0)]
    [InlineData(SessionCrypto.NonceSize)]
    [InlineData(SessionCrypto.NonceSize + 3)]
    public void Flipping_any_byte_of_the_envelope_is_detected(int index)
    {
        (SessionCrypto client, SessionCrypto server) = Pair();
        using (client)
        using (server)
        {
            byte[] envelope = client.Seal(Encoding.UTF8.GetBytes("整整一段剪贴板文本"), Header);
            envelope[index] ^= 0x01;
            Assert.False(server.TryOpen(envelope, Header, out byte[]? _));
        }
    }

    [Fact]
    public void Rewriting_the_cleartext_header_is_detected()
    {
        // The whole point of binding the header as associated data: a peer cannot keep the ciphertext and
        // change the hash, the sender id or the timestamp that travel beside it in the clear.
        (SessionCrypto client, SessionCrypto server) = Pair();
        using (client)
        using (server)
        {
            byte[] envelope = client.Seal(Encoding.UTF8.GetBytes("hello"), Header);
            byte[] rewritten = Encoding.UTF8.GetBytes("1|msg|device|0|text|OTHERHASH");
            Assert.False(server.TryOpen(envelope, rewritten, out byte[]? _));
        }
    }

    [Fact]
    public void A_captured_frame_cannot_be_replayed()
    {
        (SessionCrypto client, SessionCrypto server) = Pair();
        using (client)
        using (server)
        {
            byte[] envelope = client.Seal(Encoding.UTF8.GetBytes("转账 100"), Header);

            Assert.True(server.TryOpen(envelope, Header, out byte[]? first));
            Assert.False(server.TryOpen(envelope, Header, out byte[]? second));
            Assert.NotNull(first);
            Assert.Null(second);
        }
    }

    [Fact]
    public void Frames_delivered_out_of_order_are_dropped_rather_than_reordered()
    {
        // Documents why sealing happens inside the send gate: the counter is the replay defence, so a
        // frame that arrives after a later one is indistinguishable from a replay and must be dropped.
        (SessionCrypto client, SessionCrypto server) = Pair();
        using (client)
        using (server)
        {
            byte[] first = client.Seal(Encoding.UTF8.GetBytes("一"), Header);
            byte[] second = client.Seal(Encoding.UTF8.GetBytes("二"), Header);

            Assert.True(server.TryOpen(second, Header, out byte[]? _));
            Assert.False(server.TryOpen(first, Header, out byte[]? _));
        }
    }

    [Fact]
    public void Every_frame_gets_its_own_nonce()
    {
        (SessionCrypto client, SessionCrypto server) = Pair();
        using (client)
        using (server)
        {
            var nonces = new HashSet<string>(StringComparer.Ordinal);
            for (int i = 0; i < 64; i++)
            {
                byte[] envelope = client.Seal(Encoding.UTF8.GetBytes($"帧 {i}"), Header);
                Assert.True(nonces.Add(Convert.ToHexString(envelope, 0, SessionCrypto.NonceSize)));
                Assert.True(server.TryOpen(envelope, Header, out byte[]? _));
            }
        }
    }

    [Theory]
    [InlineData(0)]
    [InlineData(SessionCrypto.Overhead - 1)]
    public void Envelopes_too_short_to_hold_a_tag_are_rejected(int length)
    {
        (SessionCrypto client, SessionCrypto server) = Pair();
        using (client)
        using (server)
        {
            Assert.False(server.TryOpen(new byte[length], Header, out byte[]? _));
        }
    }

    [Fact]
    public void An_empty_payload_still_seals_and_opens()
    {
        // Ping and ack carry a dummy payload precisely so their headers are covered; that path must work.
        (SessionCrypto client, SessionCrypto server) = Pair();
        using (client)
        using (server)
        {
            byte[] envelope = client.Seal(ReadOnlySpan<byte>.Empty, Header);
            Assert.Equal(SessionCrypto.Overhead, envelope.Length);
            Assert.True(server.TryOpen(envelope, Header, out byte[]? plaintext));
            Assert.Empty(plaintext!);
        }
    }

    [Fact]
    public void Proofs_are_bound_to_the_role_the_device_is_claiming()
    {
        PairingKey key = PairingKey.Create();
        byte[] clientChallenge = SessionCrypto.CreateChallenge();
        byte[] serverChallenge = SessionCrypto.CreateChallenge();
        byte[] clientProof = SessionCrypto.ComputeProof(
            key, clientChallenge, serverChallenge, isServer: false, "device-a");

        Assert.True(SessionCrypto.VerifyProof(
            key, clientChallenge, serverChallenge, isServer: false, "device-a", clientProof));

        // Reflected back as if the server had produced it, or claimed by a different device id.
        Assert.False(SessionCrypto.VerifyProof(
            key, clientChallenge, serverChallenge, isServer: true, "device-a", clientProof));
        Assert.False(SessionCrypto.VerifyProof(
            key, clientChallenge, serverChallenge, isServer: false, "device-b", clientProof));
        Assert.False(SessionCrypto.VerifyProof(
            PairingKey.Create(), clientChallenge, serverChallenge, isServer: false, "device-a", clientProof));
        Assert.False(SessionCrypto.VerifyProof(
            key, SessionCrypto.CreateChallenge(), serverChallenge, isServer: false, "device-a", clientProof));
    }

    [Fact]
    public void A_handshake_challenge_of_the_wrong_size_is_refused()
    {
        PairingKey key = PairingKey.Create();
        Assert.Throws<ArgumentException>(() =>
            SessionCrypto.Establish(key, new byte[8], new byte[SessionCrypto.ChallengeSize], false).Dispose());
        Assert.Throws<ArgumentException>(() =>
            SessionCrypto.Establish(key, new byte[SessionCrypto.ChallengeSize], new byte[8], false).Dispose());
    }

    private static (SessionCrypto Client, SessionCrypto Server) Pair(PairingKey? key = null)
    {
        key ??= PairingKey.Create();
        byte[] clientChallenge = SessionCrypto.CreateChallenge();
        byte[] serverChallenge = SessionCrypto.CreateChallenge();
        return (
            SessionCrypto.Establish(key, clientChallenge, serverChallenge, isServer: false),
            SessionCrypto.Establish(key, clientChallenge, serverChallenge, isServer: true));
    }
}
