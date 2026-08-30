using AirClip.App.Services;
using AirClip.Core.Clipboard;
using AirClip.Core.Protocol;
using AirClip.Core.Sync;
using AirClip.Crypto;
using AirClip.Net;
using AirClip.Platform.Windows;
using Microsoft.Extensions.Logging;

namespace AirClip.App;

/// <summary>
/// Composition root: builds the clipboard stack from the persisted settings and owns its lifetime.
/// Deliberately hand-wired rather than DI-container based; the graph is small and fixed.
/// </summary>
public sealed class AppHost : IDisposable
{
    private bool _disposed;

    public AppHost(AppSettings settings, FileLogger logProvider, string? dataDirectory = null)
    {
        Settings = settings;
        LogProvider = logProvider;

        Options = new ClipboardOptions
        {
            DebounceInterval = TimeSpan.FromMilliseconds(settings.DebounceMs),
            SyncImages = settings.SyncImages,
            HonorSensitiveContentMarkers = settings.HonorSensitiveMarkers,
            MaxTextBytes = settings.MaxTextKb * 1024,
            MaxImageBytes = settings.MaxImageKb * 1024,
        };

        Identity = new DeviceIdentity(settings.DeviceId, settings.DeviceName);

        ClipboardHost = new Win32ClipboardHost(new FileLogger<Win32ClipboardHost>(logProvider));
        Monitor = new Win32ClipboardMonitor(
            ClipboardHost, Options, logger: new FileLogger<Win32ClipboardMonitor>(logProvider));
        Writer = new Win32ClipboardWriter(ClipboardHost, new FileLogger<Win32ClipboardWriter>(logProvider));
        LoopGuard = new LoopGuard(
            hashTtl: Options.HashTtl, remoteWriteSuppression: Options.RemoteWriteSuppression);
        Engine = new ClipboardSyncEngine(
            Monitor, Writer, LoopGuard, Options, new FileLogger<ClipboardSyncEngine>(logProvider));

        PairingStore = new PairingStore(dataDirectory);
        Pairing = PairingStore.LoadOrCreate(out string? notice);
        PairingNotice = notice;

        // BuildHubOptions rather than a fixed instance: the transport rebuilds its hub on every start, so
        // a changed port, service name or pairing code takes effect without restarting the process.
        Transport = new AirClipSyncTransport(
            BuildHubOptions, new FileLogger<AirClipSyncTransport>(logProvider));
    }

    public AppSettings Settings { get; }

    public FileLogger LogProvider { get; }

    public ClipboardOptions Options { get; }

    public DeviceIdentity Identity { get; }

    public Win32ClipboardHost ClipboardHost { get; }

    public Win32ClipboardMonitor Monitor { get; }

    public Win32ClipboardWriter Writer { get; }

    public LoopGuard LoopGuard { get; }

    public ClipboardSyncEngine Engine { get; }

    public PairingStore PairingStore { get; }

    /// <summary>The group's shared secret. Every peer has to hold the same one to get past the handshake.</summary>
    public PairingKey Pairing { get; private set; }

    /// <summary>Set when the stored key could not be used at startup, so the UI can explain the change.</summary>
    public string? PairingNotice { get; }

    public ISyncTransport Transport { get; }

    /// <summary>
    /// Swaps in a different pairing code and persists it, returning a message if it could not be stored.
    /// The caller has to restart the transport afterwards: a live hub holds the old key in its sessions.
    /// </summary>
    public string? ReplacePairing(PairingKey key)
    {
        ArgumentNullException.ThrowIfNull(key);
        Pairing = key;
        return PairingStore.Save(key);
    }

    public void Dispose()
    {
        if (_disposed)
        {
            return;
        }

        _disposed = true;
        Transport.Dispose();
        Engine.Dispose();
        Monitor.Dispose();
        ClipboardHost.Dispose();
        LogProvider.Dispose();
    }

    private SyncHubOptions BuildHubOptions() => new()
    {
        Key = Pairing,
        Identity = Identity,
        ServiceName = Settings.ServiceName,
        Port = Settings.ListenPort,
        Platform = "windows",
    };
}
