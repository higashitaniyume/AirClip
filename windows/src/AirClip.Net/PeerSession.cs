using System.Diagnostics;
using System.Net;
using System.Net.WebSockets;
using System.Text;
using System.Text.Json;
using AirClip.Core.Protocol;
using AirClip.Crypto;

namespace AirClip.Net;

/// <summary>Who a session is talking to, as learned from the handshake rather than from discovery.</summary>
public sealed record PeerDescriptor(
    string DeviceId, string DeviceName, string Platform, string Fingerprint, IPEndPoint? Remote, bool IsServerSide);

/// <summary>Thrown when a connection is refused before it carries any clipboard data.</summary>
public sealed class PeerHandshakeException(string message) : Exception(message);

/// <summary>
/// One authenticated peer connection: the handshake, the encrypted message stream, and the heartbeat that
/// notices a peer that has gone away without saying so.
/// <para>
/// Every frame after the handshake is encrypted, including ping and ack. They carry a token nobody reads,
/// which sounds like waste but is not: sealing them means their headers are covered by GCM's associated
/// data and their nonce counter is checked, so round-trip timing cannot be forged and no frame at all can
/// be replayed. A payload-less message is rejected rather than accepted as an unencrypted heartbeat.
/// </para>
/// </summary>
public sealed class PeerSession : IDisposable
{
    public static readonly TimeSpan HeartbeatInterval = TimeSpan.FromSeconds(15);
    private static readonly TimeSpan HandshakeTimeout = TimeSpan.FromSeconds(5);
    private static readonly TimeSpan AckTimeout = TimeSpan.FromSeconds(10);

    /// <summary>Eight megabytes: a 4K screenshot as PNG fits, a peer trying to exhaust memory does not.</summary>
    public const int MaxFrameBytes = 8 * 1024 * 1024;

    private const int DedupeCapacity = 256;

    private readonly WebSocket _socket;
    private readonly SessionCrypto _crypto;
    private readonly DeviceIdentity _self;
    private readonly TimeProvider _time;
    private readonly SemaphoreSlim _sendGate = new(1, 1);
    private readonly Dictionary<string, TaskCompletionSource<bool>> _pending = new(StringComparer.Ordinal);
    private readonly HashSet<string> _seen = new(StringComparer.Ordinal);
    private readonly Queue<string> _seenOrder = new();
    private bool _disposed;

    private PeerSession(
        WebSocket socket, SessionCrypto crypto, DeviceIdentity self, PeerDescriptor peer, TimeProvider time)
    {
        _socket = socket;
        _crypto = crypto;
        _self = self;
        _time = time;
        Peer = peer;
    }

    /// <summary>A clipboard message that survived decryption, deduplication and the heartbeat filter.</summary>
    public event EventHandler<ClipMessage>? MessageReceived;

    /// <summary>Fires once, with a reason fit to show a user.</summary>
    public event EventHandler<string?>? Closed;

    /// <summary>Frames that were dropped and why — the diagnostic view of "nothing happened".</summary>
    public event EventHandler<string>? FrameRejected;

    public PeerDescriptor Peer { get; }

    public TimeSpan? RoundTrip { get; private set; }

    public bool IsOpen => _socket.State == WebSocketState.Open;

    /// <summary>Server side: the peer dialled us, so it speaks first and proves itself first.</summary>
    public static async Task<PeerSession> AcceptAsync(
        WebSocket socket,
        PairingKey key,
        DeviceIdentity self,
        string platform,
        IPEndPoint? remote,
        TimeProvider? time = null,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(socket);
        ArgumentNullException.ThrowIfNull(key);
        using CancellationTokenSource limit = Deadline(cancellationToken);

        byte[] frame = await ReadFrameAsync(socket, limit.Token).ConfigureAwait(false);
        if (!HandshakeCodec.TryReadHello(frame, out HandshakeHello? hello))
        {
            throw new PeerHandshakeException("对端没有发送合法的 AirClip 握手帧");
        }

        if (!FingerprintsAgree(hello!.Fingerprint, key.Fingerprint))
        {
            // Told plainly, on purpose: the fingerprint is public (it is in the mDNS record), and the
            // alternative is a user staring at a connection that fails for no stated reason.
            await SendFrameAsync(socket, HandshakeCodec.WriteReject("配对码不一致"), limit.Token)
                .ConfigureAwait(false);
            throw new PeerHandshakeException($"配对码不一致：对端 {Describe(hello.Fingerprint)}，本机 {key.Fingerprint}");
        }

        byte[] serverChallenge = SessionCrypto.CreateChallenge();
        var reply = new HandshakeHello(self.Id, self.Name, platform, key.Fingerprint, serverChallenge);
        await SendFrameAsync(socket, HandshakeCodec.WriteHello(reply), limit.Token).ConfigureAwait(false);

        SessionCrypto crypto = SessionCrypto.Establish(key, hello.Challenge, serverChallenge, isServer: true);
        try
        {
            byte[] proofFrame = await ReadFrameAsync(socket, limit.Token).ConfigureAwait(false);
            if (!HandshakeCodec.TryReadProof(proofFrame, out byte[]? mac)
                || !SessionCrypto.VerifyProof(
                    key, hello.Challenge, serverChallenge, isServer: false, hello.DeviceId, mac))
            {
                await SendFrameAsync(socket, HandshakeCodec.WriteReject("身份证明校验失败"), limit.Token)
                    .ConfigureAwait(false);
                throw new PeerHandshakeException("对端的身份证明校验失败");
            }

            byte[] own = SessionCrypto.ComputeProof(
                key, hello.Challenge, serverChallenge, isServer: true, self.Id);
            await SendFrameAsync(socket, HandshakeCodec.WriteProof(own), limit.Token).ConfigureAwait(false);
        }
        catch
        {
            crypto.Dispose();
            throw;
        }

        var peer = new PeerDescriptor(
            hello.DeviceId, hello.DeviceName, hello.Platform, hello.Fingerprint, remote, IsServerSide: true);
        return new PeerSession(socket, crypto, self, peer, time ?? TimeProvider.System);
    }

    /// <summary>Client side: we dialled, so we speak first, and we prove ourselves before being trusted.</summary>
    public static async Task<PeerSession> ConnectAsync(
        WebSocket socket,
        PairingKey key,
        DeviceIdentity self,
        string platform,
        IPEndPoint? remote,
        TimeProvider? time = null,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(socket);
        ArgumentNullException.ThrowIfNull(key);
        using CancellationTokenSource limit = Deadline(cancellationToken);

        byte[] clientChallenge = SessionCrypto.CreateChallenge();
        var greeting = new HandshakeHello(self.Id, self.Name, platform, key.Fingerprint, clientChallenge);
        await SendFrameAsync(socket, HandshakeCodec.WriteHello(greeting), limit.Token).ConfigureAwait(false);

        byte[] frame = await ReadFrameAsync(socket, limit.Token).ConfigureAwait(false);
        if (HandshakeCodec.TryReadReject(frame, out string? refusal))
        {
            throw new PeerHandshakeException($"对端拒绝连接：{refusal}");
        }

        if (!HandshakeCodec.TryReadHello(frame, out HandshakeHello? hello))
        {
            throw new PeerHandshakeException("对端没有回应合法的 AirClip 握手帧");
        }

        if (!FingerprintsAgree(hello!.Fingerprint, key.Fingerprint))
        {
            throw new PeerHandshakeException($"配对码不一致：对端 {Describe(hello.Fingerprint)}，本机 {key.Fingerprint}");
        }

        SessionCrypto crypto = SessionCrypto.Establish(key, clientChallenge, hello.Challenge, isServer: false);
        try
        {
            byte[] own = SessionCrypto.ComputeProof(
                key, clientChallenge, hello.Challenge, isServer: false, self.Id);
            await SendFrameAsync(socket, HandshakeCodec.WriteProof(own), limit.Token).ConfigureAwait(false);

            byte[] proofFrame = await ReadFrameAsync(socket, limit.Token).ConfigureAwait(false);
            if (HandshakeCodec.TryReadReject(proofFrame, out string? rejected))
            {
                throw new PeerHandshakeException($"对端拒绝连接：{rejected}");
            }

            if (!HandshakeCodec.TryReadProof(proofFrame, out byte[]? mac)
                || !SessionCrypto.VerifyProof(
                    key, clientChallenge, hello.Challenge, isServer: true, hello.DeviceId, mac))
            {
                throw new PeerHandshakeException("对端的身份证明校验失败");
            }
        }
        catch
        {
            crypto.Dispose();
            throw;
        }

        var peer = new PeerDescriptor(
            hello.DeviceId, hello.DeviceName, hello.Platform, hello.Fingerprint, remote, IsServerSide: false);
        return new PeerSession(socket, crypto, self, peer, time ?? TimeProvider.System);
    }

    /// <summary>
    /// Pumps the connection until it closes, ends, or fails, then reports why exactly once. The heartbeat
    /// runs alongside and shares the loop's cancellation, so a dead peer takes the whole session down.
    /// </summary>
    public async Task RunAsync(CancellationToken cancellationToken = default)
    {
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        Task heartbeat = HeartbeatLoopAsync(linked.Token);
        string? reason = null;
        try
        {
            while (!linked.IsCancellationRequested && IsOpen)
            {
                (WebSocketMessageType type, byte[] payload) =
                    await ReceiveAsync(_socket, linked.Token).ConfigureAwait(false);
                if (type == WebSocketMessageType.Close)
                {
                    reason = "对端关闭了连接";
                    break;
                }

                if (type != WebSocketMessageType.Text)
                {
                    // The handshake is over; anything binary now is a peer speaking a protocol we do not.
                    FrameRejected?.Invoke(this, "握手后收到二进制帧");
                    continue;
                }

                await HandleTextAsync(payload, linked.Token).ConfigureAwait(false);
            }
        }
        catch (OperationCanceledException)
        {
            reason ??= "本机停止了同步";
        }
        catch (WebSocketException ex)
        {
            reason = $"连接中断（{ex.WebSocketErrorCode}）";
        }
        catch (Exception ex) when (ex is IOException or InvalidDataException or ObjectDisposedException)
        {
            reason = "连接中断";
        }
        finally
        {
            await linked.CancelAsync().ConfigureAwait(false);
            try
            {
                await heartbeat.ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
            }

            Closed?.Invoke(this, reason);
        }
    }

    public async Task SendAsync(ClipMessage message, CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(message);
        await _sendGate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            // Sealing belongs inside the gate, not before it. The receiver only accepts a strictly
            // increasing nonce counter, so sealing two messages concurrently and then sending them in the
            // other order would make the loser indistinguishable from a replay and get it dropped.
            ClipMessage armoured = MessageProtector.Protect(message, _crypto);
            byte[] bytes = Encoding.UTF8.GetBytes(AirClipJson.Serialize(armoured));
            await _socket
                .SendAsync(bytes, WebSocketMessageType.Text, endOfMessage: true, cancellationToken)
                .ConfigureAwait(false);
        }
        finally
        {
            _sendGate.Release();
        }
    }

    /// <summary>Round trip to this peer, or null if the ack never came. Also refreshes <see cref="RoundTrip"/>.</summary>
    public async Task<TimeSpan?> PingAsync(CancellationToken cancellationToken = default)
    {
        ClipMessage ping = NewMessage(ClipMessageType.Ping, "ping", hash: string.Empty);
        var waiter = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
        lock (_pending)
        {
            _pending[ping.MessageId] = waiter;
        }

        long started = Stopwatch.GetTimestamp();
        try
        {
            await SendAsync(ping, cancellationToken).ConfigureAwait(false);
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
            timeout.CancelAfter(AckTimeout);
            await waiter.Task.WaitAsync(timeout.Token).ConfigureAwait(false);
            RoundTrip = Stopwatch.GetElapsedTime(started);
            return RoundTrip;
        }
        catch (Exception ex) when (ex is OperationCanceledException or WebSocketException
            or IOException or ObjectDisposedException)
        {
            return null;
        }
        finally
        {
            lock (_pending)
            {
                _pending.Remove(ping.MessageId);
            }
        }
    }

    public async Task CloseAsync(string? reason = null)
    {
        try
        {
            if (_socket.State == WebSocketState.Open)
            {
                // No description on the wire: close reasons are for this device's own log, not for a peer.
                await _socket
                    .CloseAsync(WebSocketCloseStatus.NormalClosure, null, CancellationToken.None)
                    .ConfigureAwait(false);
            }
        }
        catch (Exception ex) when (ex is WebSocketException or IOException or ObjectDisposedException
            or OperationCanceledException)
        {
        }
    }

    public void Dispose()
    {
        if (_disposed)
        {
            return;
        }

        _disposed = true;
        _crypto.Dispose();
        _socket.Dispose();
        _sendGate.Dispose();
    }

    private async Task HandleTextAsync(byte[] payload, CancellationToken cancellationToken)
    {
        ClipMessage? envelope;
        try
        {
            envelope = AirClipJson.Deserialize(Encoding.UTF8.GetString(payload));
        }
        catch (JsonException)
        {
            FrameRejected?.Invoke(this, "报文不是合法 JSON");
            return;
        }

        if (envelope is null)
        {
            FrameRejected?.Invoke(this, "报文为空");
            return;
        }

        if (envelope.Payload is null)
        {
            FrameRejected?.Invoke(this, "报文没有载荷：未加密的帧一律拒绝");
            return;
        }

        if (!MessageProtector.TryUnprotect(envelope, _crypto, out ClipMessage? message, out string? error))
        {
            FrameRejected?.Invoke(this, error ?? "解密失败");
            return;
        }

        if (!Remember(message!.MessageId))
        {
            FrameRejected?.Invoke(this, $"重复的 msg_id {message.MessageId}");
            return;
        }

        switch (message.Type)
        {
            case ClipMessageType.Ping:
                // The ack carries the ping's msg_id in its hash field. That is the only field the fixed
                // schema leaves free for correlation, and it is covered by the associated data, so it
                // cannot be rewritten in flight to make a ping look answered when it was not.
                await SendAsync(NewMessage(ClipMessageType.Ack, "ack", message.MessageId), cancellationToken)
                    .ConfigureAwait(false);
                break;

            case ClipMessageType.Ack:
                TaskCompletionSource<bool>? waiter;
                lock (_pending)
                {
                    _pending.TryGetValue(message.Hash, out waiter);
                }

                waiter?.TrySetResult(true);
                break;

            default:
                MessageReceived?.Invoke(this, message);
                break;
        }
    }

    private async Task HeartbeatLoopAsync(CancellationToken cancellationToken)
    {
        try
        {
            while (!cancellationToken.IsCancellationRequested)
            {
                await Task.Delay(HeartbeatInterval, _time, cancellationToken).ConfigureAwait(false);
                if (await PingAsync(cancellationToken).ConfigureAwait(false) is not null
                    || cancellationToken.IsCancellationRequested)
                {
                    continue;
                }

                // Nothing came back inside the ack window. Closing here is what turns a silently dead
                // wireless link into a reconnect attempt, instead of a peer that stays listed as connected.
                await CloseAsync().ConfigureAwait(false);
                return;
            }
        }
        catch (OperationCanceledException)
        {
        }
    }

    private ClipMessage NewMessage(ClipMessageType type, string token, string hash) => new()
    {
        DeviceId = _self.Id,
        DeviceName = _self.Name,
        Timestamp = _time.GetUtcNow().ToUnixTimeSeconds(),
        Type = type,
        Hash = hash,
        Payload = new ClipPayload
        {
            Content = token,
            MimeType = ProtocolConstants.TextMimeType,
            Encoding = ProtocolConstants.Utf8Encoding,
        },
    };

    /// <summary>
    /// Remembers a message id and reports whether it is new. The window is bounded because a session can
    /// run for days: the nonce counter already makes true replay impossible, so this only has to catch the
    /// honest duplicate — the same clipboard entry arriving from two peers that both heard it.
    /// </summary>
    private bool Remember(string messageId)
    {
        lock (_seen)
        {
            if (!_seen.Add(messageId))
            {
                return false;
            }

            _seenOrder.Enqueue(messageId);
            if (_seenOrder.Count > DedupeCapacity)
            {
                _seen.Remove(_seenOrder.Dequeue());
            }

            return true;
        }
    }

    /// <summary>
    /// A handshake gets five seconds. Without a deadline, a peer that connects and then says nothing holds
    /// a socket and a task open indefinitely, which is a denial of service that costs the attacker nothing.
    /// </summary>
    private static CancellationTokenSource Deadline(CancellationToken cancellationToken)
    {
        var limit = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        limit.CancelAfter(HandshakeTimeout);
        return limit;
    }

    /// <summary>
    /// Compares published fingerprints, which is a courtesy rather than a security check: the MAC exchange
    /// is what actually proves a shared secret. This exists so the common mistake — two devices in
    /// different groups — produces "配对码不一致" instead of a generic authentication failure.
    /// </summary>
    private static bool FingerprintsAgree(string? theirs, string ours) =>
        string.IsNullOrWhiteSpace(theirs) || string.Equals(theirs, ours, StringComparison.OrdinalIgnoreCase);

    private static string Describe(string? fingerprint) =>
        string.IsNullOrWhiteSpace(fingerprint) ? "未提供" : fingerprint;

    private static Task SendFrameAsync(WebSocket socket, byte[] frame, CancellationToken cancellationToken) =>
        socket.SendAsync(frame, WebSocketMessageType.Binary, endOfMessage: true, cancellationToken);

    private static async Task<byte[]> ReadFrameAsync(WebSocket socket, CancellationToken cancellationToken)
    {
        (WebSocketMessageType type, byte[] payload) =
            await ReceiveAsync(socket, cancellationToken).ConfigureAwait(false);
        if (type != WebSocketMessageType.Binary)
        {
            throw new PeerHandshakeException("握手阶段收到了非二进制帧");
        }

        return payload;
    }

    private static async Task<(WebSocketMessageType Type, byte[] Payload)> ReceiveAsync(
        WebSocket socket, CancellationToken cancellationToken)
    {
        byte[] chunk = new byte[16 * 1024];
        using var accumulated = new MemoryStream();
        WebSocketReceiveResult result;
        do
        {
            result = await socket.ReceiveAsync(chunk, cancellationToken).ConfigureAwait(false);
            if (result.MessageType == WebSocketMessageType.Close)
            {
                return (WebSocketMessageType.Close, []);
            }

            accumulated.Write(chunk, 0, result.Count);
            if (accumulated.Length > MaxFrameBytes)
            {
                throw new InvalidDataException($"帧超过 {MaxFrameBytes} 字节上限");
            }
        }
        while (!result.EndOfMessage);

        return (result.MessageType, accumulated.ToArray());
    }
}
