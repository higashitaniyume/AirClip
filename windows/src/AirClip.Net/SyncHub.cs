using System.Net;
using System.Net.Sockets;
using System.Net.WebSockets;
using AirClip.Core.Protocol;
using AirClip.Crypto;
using AirClip.Discovery;

namespace AirClip.Net;

public sealed record SyncHubOptions
{
    public required PairingKey Key { get; init; }

    public required DeviceIdentity Identity { get; init; }

    public string ServiceName { get; init; } = "_airclip._tcp.local.";

    public int Port { get; init; } = 47653;

    public string Platform { get; init; } = "windows";

    public bool EnableDiscovery { get; init; } = true;

    /// <summary>Addresses to dial without waiting to discover them: a manual entry, or a test twin.</summary>
    public IReadOnlyList<IPEndPoint> StaticPeers { get; init; } = [];

    /// <summary>
    /// Which local address to accept on. The default is every interface, because that is the point of a
    /// LAN clipboard; a second hub inside one process wants <see cref="IPAddress.Loopback"/> instead, so
    /// that a test twin never opens a port to the network or provokes a firewall prompt.
    /// </summary>
    public IPAddress? Bind { get; init; }

    public TimeProvider? Time { get; init; }
}

/// <summary>One row of the peer list, flattened for the UI.</summary>
public sealed record PeerInfo(
    string DeviceId,
    string DeviceName,
    string Platform,
    string Address,
    bool IsConnected,
    TimeSpan? RoundTrip,
    string? Status);

/// <summary>
/// The whole networking layer behind one object: announce over mDNS, listen for peers, dial the ones we
/// hear about, keep them connected, and move clipboard messages across.
/// <para>
/// Both devices announce and both dial, so a peer is reachable whichever side noticed first. That would
/// produce two connections per pair, so a rule both sides can compute alone decides which to keep: the
/// device with the smaller id is the client. The other connection is closed by whoever notices it second,
/// and because the rule is symmetric, the two devices never disagree about which one survives.
/// </para>
/// <para>
/// Messages are not relayed between peers. On a LAN every device discovers every other one, so a mesh adds
/// only loops to prevent, and loop prevention is exactly what this project has already had to be careful
/// about once.
/// </para>
/// </summary>
public sealed class SyncHub : IDisposable
{
    private static readonly TimeSpan DialTimeout = TimeSpan.FromSeconds(5);
    private static readonly TimeSpan MaxBackoff = TimeSpan.FromSeconds(30);

    /// <summary>How long the device that should be the server waits before dialling anyway.</summary>
    private static readonly TimeSpan CourtesyDelay = TimeSpan.FromSeconds(3);

    private readonly object _gate = new();
    private readonly Dictionary<string, Link> _links = new(StringComparer.OrdinalIgnoreCase);
    private readonly SyncHubOptions _options;
    private readonly TimeProvider _time;
    private readonly AirClipListener _listener;
    private MdnsService? _mdns;
    private CancellationTokenSource? _cts;
    private bool _disposed;

    public SyncHub(SyncHubOptions options)
    {
        ArgumentNullException.ThrowIfNull(options);
        _options = options;
        _time = options.Time ?? TimeProvider.System;
        _listener = new AirClipListener(options.Port, OnAcceptedAsync, options.Bind);
    }

    /// <summary>Any change a peer list on screen would need to redraw for.</summary>
    public event EventHandler? PeersChanged;

    /// <summary>A decrypted clipboard message from a peer. Ping and ack never reach here.</summary>
    public event EventHandler<ClipMessage>? MessageReceived;

    /// <summary>Human-readable progress, for the log pane and the self-test.</summary>
    public event EventHandler<string>? Diagnostic;

    public bool IsListening => _listener.IsListening;

    public int Port => _listener.Port;

    public string Fingerprint => _options.Key.Fingerprint;

    public string? ServiceInstanceName { get; private set; }

    public int DiscoveryInterfaceCount { get; private set; }

    public IReadOnlyList<PeerInfo> Peers
    {
        get
        {
            lock (_gate)
            {
                return _links.Values.Select(ToInfo).ToList();
            }
        }
    }

    public async Task StartAsync(CancellationToken cancellationToken = default)
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        if (_cts is not null)
        {
            return;
        }

        _cts = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        _listener.Start(_cts.Token);
        Report($"正在监听 ws://0.0.0.0:{Port}{WebSocketUpgrade.Path}（配对指纹 {Fingerprint}）");

        foreach (IPEndPoint endpoint in _options.StaticPeers)
        {
            AddStatic(endpoint);
        }

        if (!_options.EnableDiscovery)
        {
            return;
        }

        // Built after the listener is up, because a profile has to advertise the port that was actually
        // bound — asking for port 0 and announcing 0 would publish a service nobody can connect to.
        var profile = ServiceProfile.Create(
            _options.ServiceName,
            _options.Identity.Id,
            _options.Identity.Name,
            Port,
            _options.Key.Fingerprint,
            _options.Platform);
        ServiceInstanceName = profile.InstanceName;

        var mdns = new MdnsService(profile, _time);
        mdns.ServiceFound += OnServiceFound;
        mdns.ServiceLost += OnServiceLost;
        _mdns = mdns;
        DiscoveryInterfaceCount = await mdns.StartAsync(_cts.Token).ConfigureAwait(false);
        Report(DiscoveryInterfaceCount > 0
            ? $"mDNS 已在 {DiscoveryInterfaceCount} 个接口上广播 {profile.InstanceName}"
            : "mDNS 没有可用的组播接口，只能靠手动地址连接");
    }

    public async Task StopAsync()
    {
        if (_cts is null)
        {
            return;
        }

        MdnsService? mdns = _mdns;
        _mdns = null;
        if (mdns is not null)
        {
            mdns.ServiceFound -= OnServiceFound;
            mdns.ServiceLost -= OnServiceLost;

            // Before anything else, so peers hear the goodbye while this device can still send it.
            await mdns.StopAsync().ConfigureAwait(false);
            mdns.Dispose();
        }

        List<Link> links;
        lock (_gate)
        {
            links = _links.Values.ToList();
            _links.Clear();
        }

        foreach (Link link in links)
        {
            if (link.Dialer is not null)
            {
                await link.Dialer.CancelAsync().ConfigureAwait(false);
            }

            if (link.Session is not null)
            {
                await link.Session.CloseAsync().ConfigureAwait(false);
            }
        }

        await _cts.CancelAsync().ConfigureAwait(false);
        foreach (Link link in links.Where(l => l.DialTask is not null))
        {
            try
            {
                await link.DialTask!.ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
            }
        }

        await _listener.StopAsync().ConfigureAwait(false);
        _cts.Dispose();
        _cts = null;
        PeersChanged?.Invoke(this, EventArgs.Empty);
        Report("同步已停止");
    }

    /// <summary>Sends to every connected peer and returns how many actually took it.</summary>
    public async Task<int> BroadcastAsync(ClipMessage message, CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(message);
        List<PeerSession> targets;
        lock (_gate)
        {
            targets = _links.Values
                .Select(link => link.Session)
                .Where(session => session is { IsOpen: true })
                .Select(session => session!)
                .ToList();
        }

        int sent = 0;
        foreach (PeerSession session in targets)
        {
            try
            {
                // One peer at a time: a clipboard group is two or three devices, and serialising the sends
                // keeps a slow peer from being handed a half-written frame while a fast one is mid-send.
                await session.SendAsync(message, cancellationToken).ConfigureAwait(false);
                sent++;
            }
            catch (Exception ex) when (ex is WebSocketException or IOException or ObjectDisposedException
                or OperationCanceledException)
            {
                Report($"发送到 {session.Peer.DeviceName} 失败，等待重连");
            }
        }

        return sent;
    }

    /// <summary>Pings every peer and refreshes the round-trip figures the UI shows.</summary>
    public async Task PingAllAsync(CancellationToken cancellationToken = default)
    {
        List<PeerSession> targets;
        lock (_gate)
        {
            targets = _links.Values
                .Select(link => link.Session)
                .Where(session => session is { IsOpen: true })
                .Select(session => session!)
                .ToList();
        }

        foreach (PeerSession session in targets)
        {
            await session.PingAsync(cancellationToken).ConfigureAwait(false);
        }

        if (targets.Count > 0)
        {
            PeersChanged?.Invoke(this, EventArgs.Empty);
        }
    }

    public void Dispose()
    {
        if (_disposed)
        {
            return;
        }

        _disposed = true;
        _cts?.Cancel();
        _mdns?.Dispose();
        _listener.Dispose();
        lock (_gate)
        {
            foreach (Link link in _links.Values)
            {
                link.Dialer?.Cancel();
                link.Session?.Dispose();
            }

            _links.Clear();
        }

        _cts?.Dispose();
        _cts = null;
    }

    private void AddStatic(IPEndPoint endpoint)
    {
        Link link;
        string key = $"static:{endpoint}";
        lock (_gate)
        {
            if (_links.ContainsKey(key))
            {
                return;
            }

            link = new Link { Key = key, DeviceName = endpoint.ToString(), Endpoint = endpoint, Status = "正在连接" };
            _links[key] = link;
        }

        StartDialer(link);
        PeersChanged?.Invoke(this, EventArgs.Empty);
    }

    private void OnServiceFound(object? sender, DiscoveredService service)
    {
        if (!service.IsDialable
            || string.Equals(service.DeviceId, _options.Identity.Id, StringComparison.OrdinalIgnoreCase))
        {
            return;
        }

        bool mismatch = !string.IsNullOrWhiteSpace(service.Fingerprint)
            && !string.Equals(service.Fingerprint, Fingerprint, StringComparison.OrdinalIgnoreCase);

        Link link;
        bool dial = false;
        lock (_gate)
        {
            string key = LinkKey(service);
            if (!_links.TryGetValue(key, out Link? existing))
            {
                existing = new Link { Key = key };
                _links[key] = existing;
            }

            existing.DeviceId = service.DeviceId;
            existing.DeviceName = service.DeviceName;
            existing.Platform = service.Platform;
            existing.Endpoint = service.EndPoint;
            if (mismatch)
            {
                // Do not dial: the fingerprints are published precisely so that a device in another group
                // can be listed and explained instead of being retried every thirty seconds forever.
                existing.Status = $"配对码不一致（对端 {service.Fingerprint}）";
            }
            else if (existing.Session is not { IsOpen: true } && !existing.IsDialing)
            {
                existing.Status = "正在连接";
                dial = true;
            }

            link = existing;
        }

        Report(mismatch
            ? $"发现 {service.DeviceName}，但配对码不一致，已跳过"
            : $"发现 {service.DeviceName} @ {service.EndPoint}");
        if (dial)
        {
            StartDialer(link);
        }

        PeersChanged?.Invoke(this, EventArgs.Empty);
    }

    private void OnServiceLost(object? sender, DiscoveredService service)
    {
        CancellationTokenSource? stopped = null;
        bool changed = false;
        lock (_gate)
        {
            if (_links.TryGetValue(LinkKey(service), out Link? link))
            {
                changed = true;
                if (link.Session is { IsOpen: true })
                {
                    // The mDNS record expired but the TCP connection is alive; the connection is the truth.
                    link.Status = "mDNS 记录已过期，连接仍在";
                }
                else
                {
                    stopped = link.Dialer;
                    _links.Remove(link.Key);
                }
            }
        }

        if (stopped is not null)
        {
            stopped.Cancel();
        }

        if (changed)
        {
            Report($"{service.DeviceName} 已从 mDNS 列表消失");
            PeersChanged?.Invoke(this, EventArgs.Empty);
        }
    }

    private string LinkKey(DiscoveredService service) =>
        string.IsNullOrEmpty(service.DeviceId) ? $"instance:{service.InstanceName}" : service.DeviceId;

    private void StartDialer(Link link)
    {
        CancellationTokenSource? root = _cts;
        if (root is null)
        {
            return;
        }

        var dialer = CancellationTokenSource.CreateLinkedTokenSource(root.Token);
        lock (_gate)
        {
            link.Dialer = dialer;
        }

        Task task = DialLoopAsync(link, dialer.Token);
        lock (_gate)
        {
            link.DialTask = task;
        }
    }

    /// <summary>
    /// Dials one peer and keeps dialling it: connect, run the session until it ends, back off, try again.
    /// The loop is per peer, so one unreachable device never delays another.
    /// </summary>
    private async Task DialLoopAsync(Link link, CancellationToken cancellationToken)
    {
        int attempt = 0;
        try
        {
            if (!ShouldDialFirst(link.DeviceId))
            {
                // This device is the one that should be the server for this pair. Waiting a moment lets the
                // peer's connection arrive first, so the normal case opens one socket instead of two.
                await Task.Delay(CourtesyDelay, _time, cancellationToken).ConfigureAwait(false);
            }

            while (!cancellationToken.IsCancellationRequested)
            {
                IPEndPoint? endpoint;
                lock (_gate)
                {
                    endpoint = link.Endpoint;
                    if (link.Session is { IsOpen: true })
                    {
                        // An inbound connection from this peer arrived while we were waiting. Nothing to do.
                        return;
                    }
                }

                if (endpoint is null)
                {
                    return;
                }

                try
                {
                    PeerSession session = await DialAsync(endpoint, cancellationToken).ConfigureAwait(false);
                    attempt = 0;
                    if (!TryAttach(session, out string? refusal, link))
                    {
                        await session.CloseAsync().ConfigureAwait(false);
                        session.Dispose();
                        Report(refusal!);
                        return;
                    }

                    Report($"已连接 {session.Peer.DeviceName}（{endpoint}）");
                    await RunSessionAsync(session, cancellationToken).ConfigureAwait(false);
                }
                catch (PeerHandshakeException ex)
                {
                    SetStatus(link, ex.Message);
                    Report($"{endpoint} 握手失败：{ex.Message}");
                }
                catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
                {
                    SetStatus(link, "连接超时");
                }
                catch (Exception ex) when (ex is WebSocketException or SocketException or IOException
                    or ObjectDisposedException or InvalidDataException)
                {
                    SetStatus(link, "无法连接");
                }

                await Task.Delay(Backoff(attempt++), _time, cancellationToken).ConfigureAwait(false);
            }
        }
        catch (OperationCanceledException)
        {
        }
    }

    private async Task<PeerSession> DialAsync(IPEndPoint endpoint, CancellationToken cancellationToken)
    {
        var client = new ClientWebSocket();
        client.Options.KeepAliveInterval = TimeSpan.FromSeconds(30);
        var uri = new UriBuilder("ws", endpoint.Address.ToString(), endpoint.Port, WebSocketUpgrade.Path).Uri;
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        timeout.CancelAfter(DialTimeout);
        try
        {
            await client.ConnectAsync(uri, timeout.Token).ConfigureAwait(false);
            return await PeerSession
                .ConnectAsync(
                    client, _options.Key, _options.Identity, _options.Platform, endpoint, _time, cancellationToken)
                .ConfigureAwait(false);
        }
        catch
        {
            client.Dispose();
            throw;
        }
    }

    private async Task OnAcceptedAsync(WebSocket socket, IPEndPoint? remote, CancellationToken cancellationToken)
    {
        PeerSession session;
        try
        {
            session = await PeerSession
                .AcceptAsync(
                    socket, _options.Key, _options.Identity, _options.Platform, remote, _time, cancellationToken)
                .ConfigureAwait(false);
        }
        catch (PeerHandshakeException ex)
        {
            Report($"拒绝了来自 {remote} 的连接：{ex.Message}");
            socket.Dispose();
            return;
        }
        catch
        {
            socket.Dispose();
            throw;
        }

        if (!TryAttach(session, out string? refusal))
        {
            await session.CloseAsync().ConfigureAwait(false);
            session.Dispose();
            Report(refusal!);
            return;
        }

        Report($"接受了 {session.Peer.DeviceName} 的连接（{remote}）");
        await RunSessionAsync(session, cancellationToken).ConfigureAwait(false);
    }

    /// <summary>
    /// Files a freshly handshaken session under its peer's device id, or refuses it because a better
    /// connection to the same peer already exists. Both devices apply the same rule to the same pair of
    /// ids, so they always keep the same one of the two connections.
    /// <para>
    /// <paramref name="dialled"/> is the row the dial loop was working on. A statically configured address
    /// has no device id until the handshake produces one, so without the hint it would be left behind as a
    /// second row that says "正在连接" forever while the real connection lives somewhere else in the table.
    /// </para>
    /// </summary>
    private bool TryAttach(PeerSession session, out string? refusal, Link? dialled = null)
    {
        refusal = null;
        PeerSession? replaced = null;
        lock (_gate)
        {
            string key = session.Peer.DeviceId;
            Link? link = _links.Values.FirstOrDefault(candidate =>
                string.Equals(candidate.DeviceId, key, StringComparison.OrdinalIgnoreCase));
            link ??= dialled;
            if (link is null && !_links.TryGetValue(key, out link))
            {
                link = new Link { Key = key };
                _links[key] = link;
            }

            if (link.Session is { IsOpen: true } existing)
            {
                bool weAreClient = !session.Peer.IsServerSide;
                if (weAreClient != ShouldDialFirst(key))
                {
                    refusal = $"{session.Peer.DeviceName} 已经有一条连接，关闭重复的这条";
                    return false;
                }

                replaced = existing;
            }

            link.DeviceId = key;
            link.DeviceName = session.Peer.DeviceName;
            link.Platform = session.Peer.Platform;

            // The remote endpoint of an accepted connection is deliberately not stored as the dial target:
            // it is the peer's source port, not the port it listens on, so reconnecting to it would fail
            // forever. It is recorded separately, because it is still the honest answer to "from where?".
            link.Remote = session.Peer.Remote;
            link.Session = session;
            link.Status = null;
        }

        if (replaced is not null)
        {
            Report($"用新连接替换了到 {session.Peer.DeviceName} 的旧连接");
            _ = replaced.CloseAsync();
        }

        PeersChanged?.Invoke(this, EventArgs.Empty);
        return true;
    }

    private async Task RunSessionAsync(PeerSession session, CancellationToken cancellationToken)
    {
        void OnMessage(object? sender, ClipMessage message) => MessageReceived?.Invoke(this, message);
        void OnRejected(object? sender, string reason) =>
            Report($"{session.Peer.DeviceName}：丢弃了一帧（{reason}）");
        void OnClosed(object? sender, string? reason) => SetStatusFor(session, reason);

        session.MessageReceived += OnMessage;
        session.FrameRejected += OnRejected;
        session.Closed += OnClosed;
        try
        {
            await session.RunAsync(cancellationToken).ConfigureAwait(false);
        }
        finally
        {
            session.MessageReceived -= OnMessage;
            session.FrameRejected -= OnRejected;
            session.Closed -= OnClosed;
            Detach(session);
            session.Dispose();
        }
    }

    private void Detach(PeerSession session)
    {
        Link? orphan = null;
        lock (_gate)
        {
            foreach (Link link in _links.Values)
            {
                if (!ReferenceEquals(link.Session, session))
                {
                    continue;
                }

                link.Session = null;
                link.Status ??= "已断开";

                // Restart the dialler only for a link that has none running — an outbound session is already
                // inside its own loop and will reconnect by itself — and only for one with a dialable
                // address. A peer that merely connected to us is left to come back or to be announced
                // again, because the source port it arrived from is not somewhere anyone can be reached.
                if (link.Endpoint is not null && !link.IsDialing)
                {
                    orphan = link;
                }
            }
        }

        if (orphan is not null)
        {
            StartDialer(orphan);
        }

        PeersChanged?.Invoke(this, EventArgs.Empty);
    }

    /// <summary>The device with the smaller id is the client. Symmetric, so both sides agree without asking.</summary>
    private bool ShouldDialFirst(string peerDeviceId) =>
        string.IsNullOrEmpty(peerDeviceId)
        || string.CompareOrdinal(_options.Identity.Id, peerDeviceId) < 0;

    private void SetStatus(Link link, string? status)
    {
        lock (_gate)
        {
            link.Status = status;
        }

        PeersChanged?.Invoke(this, EventArgs.Empty);
    }

    private void SetStatusFor(PeerSession session, string? status)
    {
        lock (_gate)
        {
            foreach (Link link in _links.Values.Where(l => ReferenceEquals(l.Session, session)))
            {
                link.Status = status;
            }
        }

        PeersChanged?.Invoke(this, EventArgs.Empty);
    }

    private static TimeSpan Backoff(int attempt) =>
        TimeSpan.FromSeconds(Math.Min(MaxBackoff.TotalSeconds, Math.Pow(2, Math.Min(attempt, 5))));

    private static PeerInfo ToInfo(Link link) => new(
        link.DeviceId,
        string.IsNullOrWhiteSpace(link.DeviceName) ? link.Where?.ToString() ?? "未知设备" : link.DeviceName,
        link.Platform,
        link.Where?.ToString() ?? string.Empty,
        link.Session is { IsOpen: true },
        link.Session?.RoundTrip,
        link.Status);

    private void Report(string line) => Diagnostic?.Invoke(this, line);

    private sealed class Link
    {
        public required string Key { get; init; }

        public string DeviceId { get; set; } = string.Empty;

        public string DeviceName { get; set; } = string.Empty;

        public string Platform { get; set; } = "unknown";

        /// <summary>Where this peer can be dialled: from mDNS or from configuration, never from a socket.</summary>
        public IPEndPoint? Endpoint { get; set; }

        /// <summary>Where the live connection actually comes from, which is all an inbound peer tells us.</summary>
        public IPEndPoint? Remote { get; set; }

        /// <summary>What to show as this peer's address, whichever of the two is known.</summary>
        public IPEndPoint? Where => Endpoint ?? Remote;

        public PeerSession? Session { get; set; }

        public string? Status { get; set; }

        public CancellationTokenSource? Dialer { get; set; }

        public Task? DialTask { get; set; }

        /// <summary>A dial loop that is still running. A completed task is not one, and must not block a retry.</summary>
        public bool IsDialing => DialTask is { IsCompleted: false };
    }
}
