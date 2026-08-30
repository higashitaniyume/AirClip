using AirClip.Core.Clipboard;
using AirClip.Core.Protocol;
using AirClip.Net;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Logging.Abstractions;

namespace AirClip.App.Services;

/// <summary>
/// The networking stage behind the UI's seam: mDNS discovery, the WebSocket listener, the authenticated
/// handshake and AES-256-GCM, all of it inside <see cref="SyncHub"/>.
/// <para>
/// The hub is built at <see cref="StartAsync"/> and destroyed at <see cref="StopAsync"/> rather than living
/// for the process's lifetime. That is what lets the user turn sync off, change the port or the pairing
/// code and turn it back on without restarting AirClip: the options are read afresh every time, and a
/// listening socket is never left bound to a port the settings no longer name.
/// </para>
/// </summary>
public sealed class AirClipSyncTransport : ISyncTransport
{
    /// <summary>
    /// How often the peer list is republished while a peer is connected. The round-trip figure is refreshed
    /// by <see cref="PeerSession"/>'s own heartbeat, which has no reason to notify a UI; without this tick
    /// the latency column would show whatever it happened to say when the peer last connected.
    /// </summary>
    private static readonly TimeSpan LatencyRefresh = TimeSpan.FromSeconds(10);

    private readonly Func<SyncHubOptions> _options;
    private readonly ILogger _logger;
    private readonly Timer _ticker;
    private readonly SemaphoreSlim _gate = new(1, 1);
    private SyncHub? _hub;
    private bool _disposed;

    public AirClipSyncTransport(Func<SyncHubOptions> options, ILogger<AirClipSyncTransport>? logger = null)
    {
        ArgumentNullException.ThrowIfNull(options);
        _options = options;
        _logger = logger ?? NullLogger<AirClipSyncTransport>.Instance;
        _ticker = new Timer(_ => Tick(), null, LatencyRefresh, LatencyRefresh);
    }

    public event EventHandler? PeersChanged;

    public event EventHandler<ClipboardChangedEventArgs>? ContentReceived;

    public event EventHandler<string>? Diagnostic;

    public IReadOnlyList<SyncPeer> Peers =>
        _hub is { } hub ? [.. hub.Peers.Select(ToPeer)] : [];

    public bool IsListening => _hub?.IsListening == true;

    /// <summary>The port actually bound, which is only known once the hub is up. Zero while sync is off.</summary>
    public int ListenPort => _hub?.Port ?? 0;

    /// <summary>The mDNS instance this device is announcing, or null while sync is off.</summary>
    public string? ServiceInstanceName => _hub?.ServiceInstanceName;

    /// <summary>How many interfaces the announcement went out on. Zero means "manual addresses only".</summary>
    public int DiscoveryInterfaceCount => _hub?.DiscoveryInterfaceCount ?? 0;

    public async Task StartAsync(CancellationToken cancellationToken = default)
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        await _gate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            if (_hub is not null)
            {
                return;
            }

            var hub = new SyncHub(_options());
            hub.PeersChanged += OnPeersChanged;
            hub.MessageReceived += OnMessageReceived;
            hub.Diagnostic += OnDiagnostic;
            try
            {
                await hub.StartAsync(cancellationToken).ConfigureAwait(false);
            }
            catch
            {
                // A hub that could not bind its port must not be left in place: the next Start would see a
                // non-null field, return early, and the app would look like it was syncing when it was not.
                hub.PeersChanged -= OnPeersChanged;
                hub.MessageReceived -= OnMessageReceived;
                hub.Diagnostic -= OnDiagnostic;
                await hub.StopAsync().ConfigureAwait(false);
                hub.Dispose();
                throw;
            }

            _hub = hub;
        }
        finally
        {
            _gate.Release();
        }

        PeersChanged?.Invoke(this, EventArgs.Empty);
    }

    public async Task StopAsync()
    {
        SyncHub? hub;
        await _gate.WaitAsync().ConfigureAwait(false);
        try
        {
            hub = _hub;
            _hub = null;
        }
        finally
        {
            _gate.Release();
        }

        if (hub is null)
        {
            return;
        }

        hub.PeersChanged -= OnPeersChanged;
        hub.MessageReceived -= OnMessageReceived;
        hub.Diagnostic -= OnDiagnostic;
        await hub.StopAsync().ConfigureAwait(false);
        hub.Dispose();

        // Raised after the field is cleared, so a handler that reads Peers sees the empty list.
        PeersChanged?.Invoke(this, EventArgs.Empty);
    }

    public Task<int> BroadcastAsync(ClipboardContent content, CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(content);
        if (_hub is not { } hub)
        {
            return Task.FromResult(0);
        }

        SyncHubOptions options = _options();
        ClipMessage message = ClipMessageFactory.Create(content, options.Identity);
        return hub.BroadcastAsync(message, cancellationToken);
    }

    public void Dispose()
    {
        if (_disposed)
        {
            return;
        }

        _disposed = true;
        _ticker.Dispose();

        SyncHub? hub = _hub;
        _hub = null;
        if (hub is not null)
        {
            hub.PeersChanged -= OnPeersChanged;
            hub.MessageReceived -= OnMessageReceived;
            hub.Diagnostic -= OnDiagnostic;
            hub.Dispose();
        }

        _gate.Dispose();
    }

    private void OnPeersChanged(object? sender, EventArgs e) => PeersChanged?.Invoke(this, EventArgs.Empty);

    private void OnDiagnostic(object? sender, string line)
    {
        _logger.LogInformation("Sync: {Line}", line);
        Diagnostic?.Invoke(this, line);
    }

    /// <summary>
    /// A decrypted, deduplicated clipboard message on its way to the local clipboard. Ping and ack never
    /// reach here, and anything that survived the session but cannot be turned back into clipboard content
    /// is reported rather than dropped in silence: it means the two ends disagree about the payload.
    /// </summary>
    private void OnMessageReceived(object? sender, ClipMessage message)
    {
        if (ClipMessageFactory.TryReadContent(message, out ClipboardContent? content, out string? error))
        {
            ContentReceived?.Invoke(this, new ClipboardChangedEventArgs(content!));
            return;
        }

        _logger.LogWarning(
            "Discarded a {Type} message from {Device}: {Error}", message.Type, message.DeviceId, error);
        Diagnostic?.Invoke(this, $"{message.DeviceName} 发来的内容无法解析：{error}");
    }

    private void Tick()
    {
        if (_hub is { } hub && hub.Peers.Any(peer => peer.IsConnected))
        {
            PeersChanged?.Invoke(this, EventArgs.Empty);
        }
    }

    private static SyncPeer ToPeer(PeerInfo peer) => new(
        peer.DeviceId,
        peer.DeviceName,
        peer.Platform switch
        {
            "windows" => PeerPlatform.Windows,
            "android" => PeerPlatform.Android,
            _ => PeerPlatform.Unknown,
        },
        peer.Address,
        peer.IsConnected,
        peer.RoundTrip,
        peer.Status);
}
