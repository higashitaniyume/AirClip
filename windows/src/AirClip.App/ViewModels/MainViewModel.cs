using System.Collections.ObjectModel;
using System.Diagnostics;
using System.IO;
using System.Net.Sockets;
using System.Runtime.CompilerServices;
using System.Windows.Threading;
using AirClip.App.Mvvm;
using AirClip.App.Services;
using AirClip.Core.Clipboard;
using AirClip.Crypto;

namespace AirClip.App.ViewModels;

/// <summary>
/// Everything the window and the tray menu bind to: the clipboard pipeline, the peer list coming out of
/// <see cref="ISyncTransport"/>, and the pairing code that decides which devices are in the same group.
/// </summary>
public sealed class MainViewModel : ObservableObject
{
    /// <summary>
    /// What stands in for the pairing code until the user asks for it. The code is the group's shared
    /// secret: anyone who reads it off a screenshot or a shoulder can join and read the clipboard, so the
    /// window shows the derived fingerprint by default and the code itself only on request.
    /// </summary>
    private static readonly string MaskedCode = string.Join('-', Enumerable.Repeat("••••", 8));

    private readonly AppHost _host;
    private readonly SettingsStore _store;
    private readonly Dispatcher _dispatcher;
    private SyncState _state = SyncState.Offline;
    private string _statusDetail = "正在监听本机剪贴板";
    private bool _isPaused;
    private bool _isPairingRevealed;
    private string _pairingCodeInput = string.Empty;

    public MainViewModel(AppHost host, SettingsStore store, Dispatcher dispatcher)
    {
        _host = host;
        _store = store;
        _dispatcher = dispatcher;

        TogglePauseCommand = new RelayCommand(() => IsPaused = !IsPaused);
        SendClipboardCommand = new RelayCommand(SendCurrentClipboard);
        ClearHistoryCommand = new RelayCommand(History.Clear);
        CopyItemCommand = new RelayCommand(parameter => _ = RecopyAsync(parameter as HistoryItemViewModel));
        SendItemCommand = new RelayCommand(parameter => _ = SendItemAsync(parameter as HistoryItemViewModel));
        RefreshPeersCommand = new RelayCommand(RefreshPeers);
        OpenLogCommand = new RelayCommand(OpenLog);
        OpenSettingsFolderCommand = new RelayCommand(() => Reveal(_store.Directory));
        TogglePairingRevealCommand = new RelayCommand(() => IsPairingRevealed = !IsPairingRevealed);
        CopyPairingCommand = new RelayCommand(() => _ = CopyPairingAsync());
        RegeneratePairingCommand = new RelayCommand(() => _ = RegeneratePairingAsync());
        ApplyPairingCommand = new RelayCommand(() => _ = ApplyPairingAsync());
        RestartNetworkCommand = new RelayCommand(() => _ = RestartNetworkAsync());

        _host.Engine.LocalClipboardPublished += OnLocalPublished;
        _host.Transport.ContentReceived += OnRemoteReceived;

        // BeginInvoke, not Invoke: these arrive on network threads, and a synchronous hop onto a busy UI
        // thread would stall the session that raised them.
        _host.Transport.PeersChanged += (_, _) => OnUi(RefreshPeers);
        _host.Transport.Diagnostic += (_, line) => OnUi(() => StatusDetail = line);
    }

    /// <summary>Raised when the tray icon and tooltip need to follow a state change.</summary>
    public event Action<SyncState>? StateChanged;

    public event Action<string, string>? BalloonRequested;

    public ObservableCollection<PeerViewModel> Peers { get; } = [];

    public ObservableCollection<HistoryItemViewModel> History { get; } = [];

    public RelayCommand TogglePauseCommand { get; }

    public RelayCommand SendClipboardCommand { get; }

    public RelayCommand ClearHistoryCommand { get; }

    public RelayCommand CopyItemCommand { get; }

    public RelayCommand SendItemCommand { get; }

    public RelayCommand RefreshPeersCommand { get; }

    public RelayCommand OpenLogCommand { get; }

    public RelayCommand OpenSettingsFolderCommand { get; }

    public RelayCommand TogglePairingRevealCommand { get; }

    public RelayCommand CopyPairingCommand { get; }

    public RelayCommand RegeneratePairingCommand { get; }

    public RelayCommand ApplyPairingCommand { get; }

    public RelayCommand RestartNetworkCommand { get; }

    public SyncState State
    {
        get => _state;
        private set
        {
            if (SetProperty(ref _state, value))
            {
                OnPropertyChanged(nameof(StateLabel));
                StateChanged?.Invoke(value);
            }
        }
    }

    public string StateLabel => State switch
    {
        SyncState.Connected => $"已连接 {Peers.Count} 台设备",
        SyncState.Paused => "已暂停",
        _ => EnableSync ? "离线 · 未发现设备" : "网络已关闭",
    };

    /// <summary>Second line of the header, and the tray tooltip's detail line.</summary>
    public string StatusDetail
    {
        get => _statusDetail;
        private set => SetProperty(ref _statusDetail, value);
    }

    public bool IsPaused
    {
        get => _isPaused;
        set
        {
            if (!SetProperty(ref _isPaused, value))
            {
                return;
            }

            OnPropertyChanged(nameof(PauseLabel));
            if (value)
            {
                _host.Engine.Stop();
                StatusDetail = "同步已暂停，剪贴板变化不会被读取";
            }
            else
            {
                _host.Engine.Start();
                StatusDetail = "正在监听本机剪贴板";
            }

            UpdateState();
        }
    }

    public string PauseLabel => IsPaused ? "恢复同步" : "暂停同步";

    /// <summary>
    /// Whether the network side runs. Turning it off tears the hub down — no listening socket, no mDNS
    /// announcement — while local capture and history keep working, which is what a user on a network
    /// they do not trust actually wants.
    /// </summary>
    public bool EnableSync
    {
        get => _host.Settings.EnableSync;
        set
        {
            if (value == _host.Settings.EnableSync)
            {
                return;
            }

            _host.Settings.EnableSync = value;
            _store.Save(_host.Settings);
            OnPropertyChanged();
            OnPropertyChanged(nameof(StateLabel));
            _ = value ? StartTransportAsync() : StopTransportAsync("网络已关闭，只在本机记录剪贴板");
        }
    }

    /// <summary>
    /// The four derived bytes both devices can compare out loud. Safe to show and to screenshot: it is a
    /// hash of the secret, not the secret, and it is what the mDNS record publishes anyway.
    /// </summary>
    public string PairingFingerprint => _host.Pairing.Fingerprint;

    /// <summary>Masked until the user asks, so the window is safe to screen-share by default.</summary>
    public bool IsPairingRevealed
    {
        get => _isPairingRevealed;
        set
        {
            if (SetProperty(ref _isPairingRevealed, value))
            {
                OnPropertyChanged(nameof(PairingCodeDisplay));
                OnPropertyChanged(nameof(PairingRevealLabel));
            }
        }
    }

    public string PairingCodeDisplay => IsPairingRevealed ? _host.Pairing.Code : MaskedCode;

    public string PairingRevealLabel => IsPairingRevealed ? "隐藏配对码" : "显示配对码";

    /// <summary>Where the code shown by another device gets typed or pasted, including an airclip:// URI.</summary>
    public string PairingCodeInput
    {
        get => _pairingCodeInput;
        set => SetProperty(ref _pairingCodeInput, value);
    }

    /// <summary>
    /// Shows the settings values, not <see cref="AppHost.Identity"/>: a rename is visible immediately but
    /// only reaches the wire protocol on the next start, and the header should not pretend otherwise.
    /// </summary>
    public string LocalEndpoint => $"{_host.Settings.DeviceName} · {_host.Settings.DeviceId} · :{_host.Settings.ListenPort}";

    public string LogPath => _host.LogProvider.FilePath;

    public string DeviceName
    {
        get => _host.Settings.DeviceName;
        set => Apply(_host.Settings.DeviceName, string.IsNullOrWhiteSpace(value) ? Environment.MachineName : value.Trim(),
            v => _host.Settings.DeviceName = v, nameof(LocalEndpoint));
    }

    /// <summary>
    /// Both of these are read when the hub is built, so a change only reaches the network on the next
    /// start. Saying so beats leaving the user to wonder why the port they typed is not the one in use.
    /// </summary>
    public string ServiceName
    {
        get => _host.Settings.ServiceName;
        set
        {
            if (Apply(_host.Settings.ServiceName, value, v => _host.Settings.ServiceName = v))
            {
                NoteRestartNeeded();
            }
        }
    }

    public int ListenPort
    {
        get => _host.Settings.ListenPort;
        set
        {
            if (Apply(_host.Settings.ListenPort, Math.Clamp(value, 1024, 65535),
                v => _host.Settings.ListenPort = v, nameof(LocalEndpoint)))
            {
                NoteRestartNeeded();
            }
        }
    }

    public bool SyncImages
    {
        get => _host.Settings.SyncImages;
        set => Apply(_host.Settings.SyncImages, value, v =>
        {
            _host.Settings.SyncImages = v;
            _host.Options.SyncImages = v;
        });
    }

    public bool HonorSensitiveMarkers
    {
        get => _host.Settings.HonorSensitiveMarkers;
        set => Apply(_host.Settings.HonorSensitiveMarkers, value, v =>
        {
            _host.Settings.HonorSensitiveMarkers = v;
            _host.Options.HonorSensitiveContentMarkers = v;
        });
    }
    public bool KeepHistory
    {
        get => _host.Settings.KeepHistory;
        set
        {
            Apply(_host.Settings.KeepHistory, value, v => _host.Settings.KeepHistory = v);
            if (!value)
            {
                History.Clear();
            }
        }
    }

    public int HistoryLimit
    {
        get => _host.Settings.HistoryLimit;
        set
        {
            Apply(_host.Settings.HistoryLimit, Math.Clamp(value, 10, 500), v => _host.Settings.HistoryLimit = v);
            TrimHistory();
        }
    }

    public int MaxTextKb
    {
        get => _host.Settings.MaxTextKb;
        set => Apply(_host.Settings.MaxTextKb, Math.Clamp(value, 1, 32 * 1024), v =>
        {
            _host.Settings.MaxTextKb = v;
            _host.Options.MaxTextBytes = v * 1024;
        });
    }

    public int MaxImageKb
    {
        get => _host.Settings.MaxImageKb;
        set => Apply(_host.Settings.MaxImageKb, Math.Clamp(value, 16, 64 * 1024), v =>
        {
            _host.Settings.MaxImageKb = v;
            _host.Options.MaxImageBytes = v * 1024;
        });
    }

    /// <summary>Read when the monitor starts, so a change only takes effect after a restart.</summary>
    public int DebounceMs
    {
        get => _host.Settings.DebounceMs;
        set => Apply(_host.Settings.DebounceMs, Math.Clamp(value, 20, 2000), v => _host.Settings.DebounceMs = v);
    }
    /// <summary>The HKCU Run value is the source of truth; a failed write snaps the checkbox back.</summary>
    public bool StartWithWindows
    {
        get => _host.Settings.StartWithWindows;
        set
        {
            if (value == _host.Settings.StartWithWindows)
            {
                return;
            }

            if (!AutoStart.TrySet(value))
            {
                StatusDetail = "无法写入开机自启注册表项，设置未生效";
                OnPropertyChanged();
                return;
            }

            _host.Settings.StartWithWindows = value;
            _store.Save(_host.Settings);
            OnPropertyChanged();
        }
    }

    public bool StartMinimised
    {
        get => _host.Settings.StartMinimised;
        set => Apply(_host.Settings.StartMinimised, value, v => _host.Settings.StartMinimised = v);
    }

    public bool NotifyOnReceive
    {
        get => _host.Settings.NotifyOnReceive;
        set => Apply(_host.Settings.NotifyOnReceive, value, v => _host.Settings.NotifyOnReceive = v);
    }

    /// <summary>Starts the clipboard pipeline and the transport. Safe to call once, from the UI thread.</summary>
    public void Start()
    {
        _host.Engine.Start();

        // The registry is authoritative for auto-start: the user may have removed the Run value by hand.
        bool registered = AutoStart.IsEnabled();
        if (registered != _host.Settings.StartWithWindows)
        {
            _host.Settings.StartWithWindows = registered;
            _store.Save(_host.Settings);
            OnPropertyChanged(nameof(StartWithWindows));
        }

        if (_host.PairingNotice is { } notice)
        {
            // A key that could not be read is the difference between "the network is broken" and "this
            // device left the group", and only one of those is worth the user's time.
            StatusDetail = notice;
        }

        if (EnableSync)
        {
            _ = StartTransportAsync();
        }

        RefreshPeers();
    }

    public async Task StopAsync()
    {
        _host.Engine.Stop();
        try
        {
            await _host.Transport.StopAsync().ConfigureAwait(true);
        }
        catch (Exception ex) when (ex is IOException or InvalidOperationException or ObjectDisposedException)
        {
            // Shutting down; a transport that refuses to close cleanly must not block exit.
        }
    }

    public void SendCurrentClipboard()
    {
        if (IsPaused)
        {
            IsPaused = false;
        }

        if (!_host.Engine.PublishCurrent())
        {
            StatusDetail = "剪贴板为空，或内容已被过滤";
        }
    }

    public void RefreshPeers()
    {
        Peers.Clear();
        foreach (SyncPeer peer in _host.Transport.Peers)
        {
            Peers.Add(new PeerViewModel(peer));
        }

        OnPropertyChanged(nameof(HasPeers));
        OnPropertyChanged(nameof(StateLabel));
        UpdateState();
    }

    public bool HasPeers => Peers.Count > 0;

    private async Task StartTransportAsync()
    {
        try
        {
            await _host.Transport.StartAsync().ConfigureAwait(true);
        }
        catch (Exception ex) when (ex is SocketException or IOException or InvalidOperationException)
        {
            // Nearly always another process already on the port, which is a setting the user can change.
            StatusDetail = $"网络层启动失败：{ex.Message}";
        }

        UpdateState();
    }

    private async Task StopTransportAsync(string? detail = null)
    {
        try
        {
            await _host.Transport.StopAsync().ConfigureAwait(true);
        }
        catch (Exception ex) when (ex is SocketException or IOException or InvalidOperationException
            or ObjectDisposedException)
        {
            StatusDetail = $"网络层关闭时出错：{ex.Message}";
        }

        if (detail is not null)
        {
            StatusDetail = detail;
        }

        RefreshPeers();
    }

    /// <summary>
    /// Rebuilds the hub, which is how a changed port, service name or pairing code takes effect: the
    /// options are read fresh on every start, and live sessions hold the key they were opened with.
    /// </summary>
    private async Task RestartNetworkAsync()
    {
        await StopTransportAsync().ConfigureAwait(true);
        if (EnableSync)
        {
            await StartTransportAsync().ConfigureAwait(true);
        }
        else
        {
            StatusDetail = "网络已关闭，只在本机记录剪贴板";
        }
    }

    /// <summary>
    /// Copies the group secret with the three registered opt-out markers set, keeping it out of Windows
    /// clipboard history, the cloud clipboard and any third-party manager. AirClip's own monitor ignores
    /// its own writes, so the code is never synced to the devices it exists to let in.
    /// </summary>
    private async Task CopyPairingAsync()
    {
        try
        {
            await _host.Writer.WriteSensitiveTextAsync(_host.Pairing.Code).ConfigureAwait(true);
            StatusDetail = "配对码已复制（不会进入剪贴板历史，也不会被同步）";
        }
        catch (Exception ex) when (ex is InvalidOperationException or System.ComponentModel.Win32Exception)
        {
            StatusDetail = $"复制配对码失败：{ex.Message}";
        }
    }

    private async Task RegeneratePairingAsync()
    {
        ReplacePairing(PairingKey.Create(), "已生成新的配对码");
        await RestartNetworkAsync().ConfigureAwait(true);
    }

    private async Task ApplyPairingAsync()
    {
        if (!PairingKey.TryParse(PairingCodeInput, out PairingKey? key))
        {
            StatusDetail = "配对码格式不正确：应为 8 组 4 位字符，或一条 airclip:// 邀请链接";
            return;
        }

        PairingCodeInput = string.Empty;
        ReplacePairing(key!, "已改用输入的配对码");
        await RestartNetworkAsync().ConfigureAwait(true);
    }

    /// <summary>
    /// Stores a new key and re-masks the display: the code that was on screen a moment ago is no longer
    /// the one in use, and leaving it next to a new fingerprint is how devices end up mis-paired.
    /// </summary>
    private void ReplacePairing(PairingKey key, string what)
    {
        string? error = _host.ReplacePairing(key);
        IsPairingRevealed = false;
        OnPropertyChanged(nameof(PairingFingerprint));
        OnPropertyChanged(nameof(PairingCodeDisplay));
        StatusDetail = error is null
            ? $"{what}（指纹 {key.Fingerprint}），其他设备需要使用同一个配对码"
            : $"{what}，但{error}";
    }

    private void NoteRestartNeeded()
    {
        if (_host.Transport.IsListening)
        {
            StatusDetail = "网络设置已保存，点击「重启网络」后生效";
        }
    }

    private void OnLocalPublished(object? sender, ClipboardChangedEventArgs e)
    {
        // The engine raises this on the clipboard pump thread.
        OnUi(() =>
        {
            AddHistory(e.Content, ClipDirection.Local);
            StatusDetail = $"已捕获{(e.Content.Kind == ClipboardContentKind.Image ? "图片" : "文本")}，等待发送";
        });

        _ = BroadcastAsync(e.Content);
    }

    private void OnRemoteReceived(object? sender, ClipboardChangedEventArgs e) => _ = ApplyRemoteAsync(e.Content);

    private async Task ApplyRemoteAsync(ClipboardContent content)
    {
        bool applied = await _host.Engine.ApplyRemoteAsync(content).ConfigureAwait(true);
        if (!applied)
        {
            return;
        }

        OnUi(() =>
        {
            AddHistory(content, ClipDirection.Remote);
            StatusDetail = "已写入远端内容到本机剪贴板";
            if (NotifyOnReceive)
            {
                BalloonRequested?.Invoke("AirClip 收到新内容", History.Count > 0 ? History[0].Preview : string.Empty);
            }
        });
    }

    private async Task BroadcastAsync(ClipboardContent content)
    {
        try
        {
            int accepted = await _host.Transport.BroadcastAsync(content).ConfigureAwait(true);
            OnUi(() => StatusDetail = accepted > 0
                ? $"已发送到 {accepted} 台设备"
                : "尚无已连接设备，内容仅记录在本机历史");
        }
        catch (Exception ex) when (ex is IOException or InvalidOperationException)
        {
            OnUi(() => StatusDetail = $"发送失败：{ex.Message}");
        }
    }

    /// <summary>Puts a history entry back on the clipboard without treating it as a new local change.</summary>
    private async Task RecopyAsync(HistoryItemViewModel? item)
    {
        if (item is null)
        {
            return;
        }

        await _host.Writer.WriteAsync(item.Content).ConfigureAwait(true);
        OnUi(() => StatusDetail = "已重新复制到剪贴板");
    }

    private async Task SendItemAsync(HistoryItemViewModel? item)
    {
        if (item is not null)
        {
            await BroadcastAsync(item.Content).ConfigureAwait(true);
        }
    }

    private void AddHistory(ClipboardContent content, ClipDirection direction)
    {
        if (!KeepHistory)
        {
            return;
        }

        History.Insert(0, new HistoryItemViewModel(content, direction, DateTimeOffset.Now));
        TrimHistory();
    }

    private void TrimHistory()
    {
        while (History.Count > HistoryLimit)
        {
            History.RemoveAt(History.Count - 1);
        }
    }
    private void UpdateState() => State = IsPaused
        ? SyncState.Paused
        : Peers.Any(p => p.Peer.IsConnected) ? SyncState.Connected : SyncState.Offline;

    /// <summary>
    /// Marshals onto the UI thread. Transport and clipboard callbacks arrive on their own threads, and
    /// the awaits in this class resume on whatever context they started on, which is often neither.
    /// </summary>
    private void OnUi(Action action) => _ = _dispatcher.BeginInvoke(action);

    private void OpenLog() => Reveal(_host.LogProvider.FilePath);

    private void Reveal(string path)
    {
        try
        {
            if (File.Exists(path) || Directory.Exists(path))
            {
                Process.Start(new ProcessStartInfo(path) { UseShellExecute = true })?.Dispose();
            }
            else
            {
                StatusDetail = "文件尚未创建";
            }
        }
        catch (Exception ex) when (ex is System.ComponentModel.Win32Exception or InvalidOperationException)
        {
            StatusDetail = $"无法打开 {path}";
        }
    }

    /// <summary>Persists a settings change and raises change notification for it plus any dependants.</summary>
    private bool Apply<T>(T current, T value, Action<T> assign, string? dependant = null,
        [CallerMemberName] string? propertyName = null)
    {
        if (EqualityComparer<T>.Default.Equals(current, value))
        {
            return false;
        }

        assign(value);
        _store.Save(_host.Settings);
        OnPropertyChanged(propertyName);
        if (dependant is not null)
        {
            OnPropertyChanged(dependant);
        }

        return true;
    }
}
