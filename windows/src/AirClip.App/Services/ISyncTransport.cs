using AirClip.Core.Clipboard;

namespace AirClip.App.Services;

public enum PeerPlatform
{
    Unknown,
    Windows,
    Android,
}

public sealed record SyncPeer(
    string DeviceId,
    string DeviceName,
    PeerPlatform Platform,
    string Address,
    bool IsConnected,
    TimeSpan? RoundTrip,
    string? Status = null);

/// <summary>
/// Seam between the UI and the networking stack. The view models are written entirely against this
/// interface, which is why stage three could replace the whole implementation without touching them.
/// </summary>
public interface ISyncTransport : IDisposable
{
    IReadOnlyList<SyncPeer> Peers { get; }

    bool IsListening { get; }

    event EventHandler? PeersChanged;

    /// <summary>Clipboard content that arrived from a peer and should be applied locally.</summary>
    event EventHandler<ClipboardChangedEventArgs>? ContentReceived;

    /// <summary>
    /// Progress worth showing a user: what is being listened on, who was discovered, what was refused and
    /// why. Networking is the part of AirClip that fails for reasons outside the app — a wrong pairing
    /// code, a firewall, an interface with no multicast — and silence would leave the user guessing.
    /// </summary>
    event EventHandler<string>? Diagnostic;

    Task StartAsync(CancellationToken cancellationToken = default);

    Task StopAsync();

    /// <summary>Sends content to every connected peer and returns how many accepted it.</summary>
    Task<int> BroadcastAsync(ClipboardContent content, CancellationToken cancellationToken = default);
}
