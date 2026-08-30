using AirClip.App.Mvvm;
using AirClip.App.Services;

namespace AirClip.App.ViewModels;

public sealed class PeerViewModel(SyncPeer peer) : ObservableObject
{
    public SyncPeer Peer { get; } = peer;

    public string DeviceName => Peer.DeviceName;

    public string DeviceId => Peer.DeviceId;

    public string Address => Peer.Address;

    public string PlatformLabel => Peer.Platform switch
    {
        PeerPlatform.Windows => "Windows",
        PeerPlatform.Android => "Android",
        _ => "未知平台",
    };

    /// <summary>
    /// The hub's own words when it has any — 「正在连接」, 「配对码不匹配」 and so on. It knows why a peer is
    /// not connected, and replacing that with a generic label would throw away the only diagnosis the user
    /// gets for a refused handshake.
    /// </summary>
    public string StatusLabel => Peer.Status ?? (Peer.IsConnected ? "已连接" : "已发现，未连接");

    public string LatencyLabel => Peer.RoundTrip is { } trip ? $"{trip.TotalMilliseconds:0} ms" : "—";
}
