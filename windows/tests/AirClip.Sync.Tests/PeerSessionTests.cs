using System.Net;
using System.Net.WebSockets;
using System.Text;
using AirClip.Core.Clipboard;
using AirClip.Core.Protocol;
using AirClip.Crypto;
using AirClip.Net;
using Xunit;

namespace AirClip.Sync.Tests;

/// <summary>
/// End-to-end over loopback TCP: the real listener, the real WebSocket upgrade, the real handshake. These
/// are the tests that would have caught every bug that only appears once two processes are talking.
/// </summary>
public class PeerSessionTests
{
    private static readonly TimeSpan Patience = TimeSpan.FromSeconds(10);

    [Fact]
    public async Task Both_sides_learn_who_they_are_talking_to_from_the_handshake()
    {
        PairingKey key = PairingKey.Create();
        await using Loopback pair = await Loopback.ConnectAsync(key);

        Assert.True(pair.Client.IsOpen);
        Assert.True(pair.Server.IsOpen);

        Assert.Equal(Loopback.ServerIdentity.Id, pair.Client.Peer.DeviceId);
        Assert.Equal(Loopback.ServerIdentity.Name, pair.Client.Peer.DeviceName);
        Assert.False(pair.Client.Peer.IsServerSide);

        Assert.Equal(Loopback.ClientIdentity.Id, pair.Server.Peer.DeviceId);
        Assert.Equal(Loopback.ClientIdentity.Name, pair.Server.Peer.DeviceName);
        Assert.True(pair.Server.Peer.IsServerSide);

        // Both published the same fingerprint, and the server knows where the client dialled from.
        Assert.Equal(key.Fingerprint, pair.Client.Peer.Fingerprint);
        Assert.Equal(key.Fingerprint, pair.Server.Peer.Fingerprint);
        Assert.Equal(IPAddress.Loopback, pair.Server.Peer.Remote!.Address);
    }

    [Fact]
    public async Task Text_arrives_decrypted_and_unchanged()
    {
        await using Loopback pair = await Loopback.ConnectAsync(PairingKey.Create());
        ClipMessage sent = ClipMessageFactory.Create(
            ClipboardContent.FromText("跨设备粘贴：https://例子.测试/路径?q=1"), Loopback.ClientIdentity);

        ClipMessage received = await NextMessageAsync(pair.Server, () => pair.Client.SendAsync(sent));

        Assert.Equal(ClipMessageType.Text, received.Type);
        Assert.Equal("跨设备粘贴：https://例子.测试/路径?q=1", received.Payload!.Content);
        Assert.Equal(ProtocolConstants.Utf8Encoding, received.Payload.Encoding);
        Assert.Equal(sent.Hash, received.Hash);
        Assert.Equal(sent.MessageId, received.MessageId);
        Assert.True(ClipMessageFactory.TryReadContent(received, out ClipboardContent? content, out string? _));
        Assert.Equal(sent.Hash, content!.Hash);
    }

    [Fact]
    public async Task An_image_arrives_byte_for_byte()
    {
        await using Loopback pair = await Loopback.ConnectAsync(PairingKey.Create());
        byte[] png = new byte[512 * 1024];
        Random.Shared.NextBytes(png);
        ClipMessage sent = ClipMessageFactory.Create(
            ClipboardContent.FromImage(new ClipboardImage(1920, 1080, png, ContentHasher.HashBytes(png))),
            Loopback.ClientIdentity);

        ClipMessage received = await NextMessageAsync(pair.Server, () => pair.Client.SendAsync(sent));

        Assert.Equal(ClipMessageType.Image, received.Type);
        Assert.True(ClipMessageFactory.TryReadContent(received, out ClipboardContent? content, out string? _));
        Assert.Equal(png, content!.Image!.Png);
        Assert.Equal(1920, content.Image.Width);
        Assert.Equal(1080, content.Image.Height);
    }

    [Fact]
    public async Task A_ping_is_answered_and_the_round_trip_is_measured()
    {
        await using Loopback pair = await Loopback.ConnectAsync(PairingKey.Create());

        TimeSpan? roundTrip = await pair.Client.PingAsync().WaitAsync(Patience);

        Assert.NotNull(roundTrip);
        Assert.Equal(roundTrip, pair.Client.RoundTrip);
        Assert.True(roundTrip > TimeSpan.Zero);
        Assert.True(roundTrip < Patience);
    }

    [Fact]
    public async Task The_same_message_twice_is_delivered_once()
    {
        // Two peers that both heard the same clipboard entry will forward the same msg_id. The nonce
        // counter cannot catch that — it is a genuinely new frame — so the dedupe window has to.
        await using Loopback pair = await Loopback.ConnectAsync(PairingKey.Create());
        ClipMessage message = ClipMessageFactory.Create(
            ClipboardContent.FromText("只应该出现一次"), Loopback.ClientIdentity);

        ClipMessage first = await NextMessageAsync(pair.Server, () => pair.Client.SendAsync(message));
        string rejection = await NextRejectionAsync(pair.Server, () => pair.Client.SendAsync(message));

        Assert.Equal("只应该出现一次", first.Payload!.Content);
        Assert.Equal($"重复的 msg_id {message.MessageId}", rejection);
    }

    [Fact]
    public async Task A_cleartext_frame_injected_onto_the_socket_is_refused()
    {
        // The attack this closes: an authenticated-looking peer that simply omits the encryption. Nothing
        // about the JSON is malformed, so only the encoding check stands between it and the clipboard.
        await using Loopback pair = await Loopback.ConnectAsync(PairingKey.Create());
        ClipMessage cleartext = ClipMessageFactory.Create(
            ClipboardContent.FromText("rm -rf /"), Loopback.ClientIdentity);

        string rejection = await NextRejectionAsync(
            pair.Server, () => pair.SendRawTextAsync(AirClipJson.Serialize(cleartext)));

        Assert.Equal("载荷未加密，已拒绝", rejection);
    }

    [Fact]
    public async Task A_frame_with_no_payload_at_all_is_refused()
    {
        await using Loopback pair = await Loopback.ConnectAsync(PairingKey.Create());
        var bare = new ClipMessage { DeviceId = "evil", Type = ClipMessageType.Ping };

        string rejection = await NextRejectionAsync(
            pair.Server, () => pair.SendRawTextAsync(AirClipJson.Serialize(bare)));

        Assert.Equal("报文没有载荷：未加密的帧一律拒绝", rejection);
    }

    [Theory]
    [InlineData("{not json", "报文不是合法 JSON")]
    [InlineData("null", "报文为空")]
    public async Task Malformed_frames_are_reported_and_do_not_kill_the_session(string text, string expected)
    {
        await using Loopback pair = await Loopback.ConnectAsync(PairingKey.Create());

        string rejection = await NextRejectionAsync(pair.Server, () => pair.SendRawTextAsync(text));

        Assert.Equal(expected, rejection);
        Assert.True(pair.Server.IsOpen);
        Assert.True(pair.Client.IsOpen);
    }

    [Fact]
    public async Task A_binary_frame_after_the_handshake_is_refused()
    {
        await using Loopback pair = await Loopback.ConnectAsync(PairingKey.Create());

        string rejection = await NextRejectionAsync(
            pair.Server, () => pair.SendRawBinaryAsync(HandshakeCodec.WriteReject("再握一次手")));

        Assert.Equal("握手后收到二进制帧", rejection);
        Assert.True(pair.Server.IsOpen);
    }

    [Fact]
    public async Task A_peer_with_a_different_pairing_code_is_told_exactly_that()
    {
        PairingKey mine = PairingKey.Create();
        PairingKey theirs = PairingKey.Create();

        PeerHandshakeException failure = await Assert.ThrowsAsync<PeerHandshakeException>(
            () => Loopback.ConnectAsync(mine, theirs));

        // Said plainly: the fingerprint is already public in the mDNS record, and "配对码不一致" is the one
        // diagnosis a user can actually act on.
        Assert.Contains("对端拒绝连接", failure.Message, StringComparison.Ordinal);
        Assert.Contains("配对码不一致", failure.Message, StringComparison.Ordinal);
    }

    [Fact]
    public async Task Claiming_the_right_fingerprint_without_the_secret_still_fails()
    {
        // The fingerprint is a courtesy check, not the security boundary. This peer publishes the correct
        // one — it is public — and still cannot produce the MAC, which is what actually gates the session.
        PairingKey real = PairingKey.Create();
        PairingKey fake = PairingKey.Create();
        var ready = new TaskCompletionSource<PeerSession>(TaskCreationOptions.RunContinuationsAsynchronously);
        var hold = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        using AirClipListener listener = Loopback.StartServer(real, ready, hold);

        using var socket = new ClientWebSocket();
        await socket.ConnectAsync(Loopback.EndpointFor(listener.Port), CancellationToken.None);
        byte[] challenge = SessionCrypto.CreateChallenge();
        await socket.SendAsync(
            HandshakeCodec.WriteHello(new HandshakeHello("evil", "黑客", "linux", real.Fingerprint, challenge)),
            WebSocketMessageType.Binary,
            endOfMessage: true,
            CancellationToken.None);

        byte[] reply = await ReceiveAsync(socket);
        Assert.True(HandshakeCodec.TryReadHello(reply, out HandshakeHello? server));
        await socket.SendAsync(
            HandshakeCodec.WriteProof(
                SessionCrypto.ComputeProof(fake, challenge, server!.Challenge, isServer: false, "evil")),
            WebSocketMessageType.Binary,
            endOfMessage: true,
            CancellationToken.None);

        byte[] verdict = await ReceiveAsync(socket);
        Assert.True(HandshakeCodec.TryReadReject(verdict, out string? reason));
        Assert.Equal("身份证明校验失败", reason);

        PeerHandshakeException failure = await Assert.ThrowsAsync<PeerHandshakeException>(() => ready.Task);
        Assert.Equal("对端的身份证明校验失败", failure.Message);
        hold.TrySetResult();
        await listener.StopAsync();
    }

    [Fact]
    public async Task A_client_that_speaks_something_else_entirely_never_becomes_a_session()
    {
        PairingKey key = PairingKey.Create();
        var ready = new TaskCompletionSource<PeerSession>(TaskCreationOptions.RunContinuationsAsynchronously);
        var hold = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        using AirClipListener listener = Loopback.StartServer(key, ready, hold);

        using var socket = new ClientWebSocket();
        await socket.ConnectAsync(Loopback.EndpointFor(listener.Port), CancellationToken.None);
        await socket.SendAsync(
            Encoding.UTF8.GetBytes("{\"type\":\"text\"}"),
            WebSocketMessageType.Text,
            endOfMessage: true,
            CancellationToken.None);

        PeerHandshakeException failure = await Assert.ThrowsAsync<PeerHandshakeException>(
            () => ready.Task.WaitAsync(Patience));
        Assert.Equal("对端跳过握手直接发送了报文，可能仍在运行旧版本", failure.Message);
        hold.TrySetResult();
        await listener.StopAsync();
    }

    [Fact]
    public async Task A_peer_that_hangs_up_without_answering_is_reported_as_having_hung_up()
    {
        // The failure a user actually meets: the far side refuses the connection before saying anything —
        // it is not paired, so it has nothing to say — and closes. That has to read as a close, because
        // "非二进制帧" sends whoever is holding the log looking for a protocol bug that is not there.
        PairingKey key = PairingKey.Create();
        var ready = new TaskCompletionSource<PeerSession>(TaskCreationOptions.RunContinuationsAsynchronously);
        var hold = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        using AirClipListener listener = Loopback.StartServer(key, ready, hold);

        using var socket = new ClientWebSocket();
        await socket.ConnectAsync(Loopback.EndpointFor(listener.Port), CancellationToken.None);
        await socket.CloseAsync(WebSocketCloseStatus.NormalClosure, null, CancellationToken.None);

        PeerHandshakeException failure = await Assert.ThrowsAsync<PeerHandshakeException>(
            () => ready.Task.WaitAsync(Patience));
        Assert.Equal("对端在握手完成前关闭了连接", failure.Message);
        hold.TrySetResult();
        await listener.StopAsync();
    }

    private static async Task<ClipMessage> NextMessageAsync(PeerSession session, Func<Task> action)
    {
        var seen = new TaskCompletionSource<ClipMessage>(TaskCreationOptions.RunContinuationsAsynchronously);
        void Handler(object? sender, ClipMessage message) => seen.TrySetResult(message);

        session.MessageReceived += Handler;
        try
        {
            await action();
            return await seen.Task.WaitAsync(Patience);
        }
        finally
        {
            session.MessageReceived -= Handler;
        }
    }

    private static async Task<string> NextRejectionAsync(PeerSession session, Func<Task> action)
    {
        var seen = new TaskCompletionSource<string>(TaskCreationOptions.RunContinuationsAsynchronously);
        void Handler(object? sender, string reason) => seen.TrySetResult(reason);

        session.FrameRejected += Handler;
        try
        {
            await action();
            return await seen.Task.WaitAsync(Patience);
        }
        finally
        {
            session.FrameRejected -= Handler;
        }
    }

    private static async Task<byte[]> ReceiveAsync(WebSocket socket)
    {
        byte[] buffer = new byte[4096];
        using var cts = new CancellationTokenSource(Patience);
        WebSocketReceiveResult result = await socket.ReceiveAsync(buffer, cts.Token);
        return buffer[..result.Count];
    }
}
