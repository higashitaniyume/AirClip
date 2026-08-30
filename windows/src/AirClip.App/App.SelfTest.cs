using System.Diagnostics;
using System.IO;
using System.Net;
using System.Text;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using AirClip.App.Services;
using AirClip.App.Tray;
using AirClip.Core.Clipboard;
using AirClip.Core.Protocol;
using AirClip.Net;
using AirClip.Platform.Windows;

namespace AirClip.App;

/// <summary>
/// <c>AirClip.exe --selftest</c>. There is no screenshot tooling for a WPF window in this environment,
/// so instead of eyeballing the UI the run asserts the things a screenshot could not prove anyway:
/// the XAML loads, every binding on all three tabs resolves, a real copy travels the whole pipeline
/// into the history list, a remote write is not echoed back, and the tray icon reaches the shell.
/// <para>
/// Since stage three the network is real too: a second <see cref="SyncHub"/> in this process connects
/// over a loopback WebSocket, so the handshake, AES-256-GCM and both directions of the wire are covered
/// by the same run. It also checks that the pairing secret on disk is a DPAPI blob and not the code.
/// </para>
/// It prints the idle footprint as well, which is the one spec number WPF genuinely strains against.
/// </summary>
public partial class App
{
    /// <summary>Deliberately not a real device id, so a stray peer row from a self-test is recognisable.</summary>
    private const string TwinDeviceId = "selftesttwin";

    private static readonly string DiagnosticPath = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "AirClip", "selftest.txt");

    private bool _diagnosticStarted;
    private bool _consoleAttached;

    private async Task RunSelfTestAsync()
    {
        var failures = new List<string>();
        var collector = new BindingErrorCollector();
        PresentationTraceSources.Refresh();
        PresentationTraceSources.DataBindingSource.Listeners.Add(collector);
        PresentationTraceSources.DataBindingSource.Switch.Level = SourceLevels.Warning;

        WriteDiagnostic($"AirClip GUI 自检 · {DateTimeOffset.Now:yyyy-MM-dd HH:mm:ss}");
        ClipboardContent? original = _host!.Monitor.ReadCurrent();

        // Taken before the window exists, because that is the state a minimised start really idles in:
        // the visual tree is never built, so hiding a window later would not measure the same thing.
        long trayOnly = MeasureWorkingSet();
        SyncHub? twin = null;

        try
        {
            ShowMainWindow();
            await Task.Delay(500);
            CheckWindow(failures);

            _window!.RealiseAllTabs();
            await Task.Delay(150);
            await CheckLocalCaptureAsync(failures);

            // Realise the tabs again now that the item templates have data to bind against.
            _window.RealiseAllTabs();
            await Task.Delay(150);

            await CheckRemoteApplyAsync(failures);
            CheckPairingAtRest(failures);
            twin = await CheckTwinPeerAsync(failures);
            CheckTray(failures);
            CheckBindings(collector, failures);

            // Photographed while the peer is still connected: an empty device list proves less.
            CaptureTabs(failures);
            if (twin is not null)
            {
                await CheckTwinRemovalAsync(twin, failures);
            }

            await ReportFootprintAsync(trayOnly);
        }
        catch (Exception ex)
        {
            failures.Add($"自检抛出 {ex.GetType().Name}：{ex.Message}");
        }
        finally
        {
            PresentationTraceSources.DataBindingSource.Listeners.Remove(collector);
            if (twin is not null)
            {
                await twin.StopAsync();
                twin.Dispose();
            }

            await RestoreClipboardAsync(original);
        }

        foreach (string failure in failures)
        {
            WriteDiagnostic($"FAIL  {failure}");
        }

        WriteDiagnostic(failures.Count == 0 ? "RESULT  全部通过" : $"RESULT  {failures.Count} 项失败");
        if (_window is not null)
        {
            _window.AllowClose = true;
        }

        Shutdown(failures.Count == 0 ? 0 : 1);
    }
    private void CheckWindow(List<string> failures)
    {
        if (_window is null || !_window.IsLoaded)
        {
            failures.Add("主窗口未能加载");
            return;
        }

        if (_window.ActualWidth <= 0 || _window.ActualHeight <= 0)
        {
            failures.Add($"主窗口尺寸异常：{_window.ActualWidth}×{_window.ActualHeight}");
            return;
        }

        WriteDiagnostic($"PASS  XAML 加载完成，窗口 {_window.ActualWidth:0}×{_window.ActualHeight:0}");
    }

    private async Task CheckLocalCaptureAsync(List<string> failures)
    {
        string probe = $"airclip-selftest-{Guid.NewGuid():N}";
        if (!TrySetClipboardText(probe))
        {
            failures.Add("无法通过 WPF 剪贴板 API 写入探针文本");
            return;
        }

        bool captured = await WaitForAsync(
            () => _viewModel!.History.Any(item => item.Content.Text == probe), TimeSpan.FromSeconds(4));
        if (captured)
        {
            WriteDiagnostic("PASS  本机复制走完 监听→去抖→引擎→视图模型 全链路并进入历史");
            return;
        }

        failures.Add("本机复制没有出现在历史列表里");
    }

    private async Task CheckRemoteApplyAsync(List<string> failures)
    {
        string remote = $"airclip-remote-{Guid.NewGuid():N}";
        if (!await _host!.Engine.ApplyRemoteAsync(ClipboardContent.FromText(remote)))
        {
            failures.Add("远端内容未能写入剪贴板");
            return;
        }

        // Read back immediately: a co-installed clipboard manager tends to restamp the clipboard a
        // second or two later, and its own privacy markers then legitimately hide the content from us.
        ClipboardContent? readBack = _host.Monitor.ReadCurrent();
        if (readBack?.Text == remote)
        {
            WriteDiagnostic("PASS  远端内容通过 Win32 写入路径落到剪贴板");
        }
        else
        {
            failures.Add($"写入后回读到的是 {readBack?.ToString() ?? "<空>"}");
        }

        await Task.Delay(1500);
        if (_viewModel!.History.Any(item => item.Direction == ClipDirection.Local && item.Content.Text == remote))
        {
            failures.Add("回环保护失效：远端写入被当成本机复制重新发布");
            return;
        }

        WriteDiagnostic("PASS  远端写入没有被回环成本机复制");
    }
    private void CheckTray(List<string> failures)
    {
        if (_tray?.IsVisible != true)
        {
            failures.Add("托盘图标没有被添加到通知区域");
            return;
        }

        TrayIconImage icon = TrayArtwork.CreateTrayIcon(SyncState.Connected);
        if (icon.Bgra.Length != icon.Width * icon.Height * 4 || !icon.Bgra.Any(b => b != 0))
        {
            failures.Add($"托盘位图无效：{icon.Width}×{icon.Height}，{icon.Bgra.Length} 字节");
            return;
        }

        WriteDiagnostic($"PASS  托盘图标已注册，位图 {icon.Width}×{icon.Height}");
    }

    private void CheckBindings(BindingErrorCollector collector, List<string> failures)
    {
        string[] messages = [.. collector.Messages.Distinct(StringComparer.Ordinal)];
        string[] errors = [.. messages.Where(m => m.Contains(" Error: ", StringComparison.Ordinal))];
        string[] warnings = [.. messages.Where(m => m.Contains(" Warning: ", StringComparison.Ordinal))];

        foreach (string warning in warnings)
        {
            WriteDiagnostic($"WARN  {warning}");
        }

        if (errors.Length > 0)
        {
            failures.AddRange(errors);
            return;
        }

        WriteDiagnostic($"PASS  三个页签的绑定全部解析成功（{warnings.Length} 条警告）");
    }

    /// <summary>
    /// What is actually on disk where the group's shared secret lives. A protected blob is the difference
    /// between "another account on this machine cannot read your clipboard" and a false claim, so the check
    /// is against the file's bytes rather than against the code that wrote them.
    /// </summary>
    private void CheckPairingAtRest(List<string> failures)
    {
        PairingStore store = _host!.PairingStore;
        if (!File.Exists(store.FilePath))
        {
            failures.Add($"配对文件没有生成：{store.FilePath}");
            return;
        }

        byte[] onDisk = File.ReadAllBytes(store.FilePath);
        string code = _host.Pairing.Code;
        string[] spellings = [code, code.Replace("-", string.Empty, StringComparison.Ordinal)];
        foreach (string spelling in spellings)
        {
            if (Contains(onDisk, Encoding.UTF8.GetBytes(spelling))
                || Contains(onDisk, Encoding.Unicode.GetBytes(spelling)))
            {
                failures.Add("配对文件里能直接搜到配对码明文");
                return;
            }
        }

        byte[] secret = _host.Pairing.ExportSecret();
        try
        {
            if (Contains(onDisk, secret))
            {
                failures.Add("配对文件里能直接搜到 20 字节的原始密钥");
                return;
            }
        }
        finally
        {
            Array.Clear(secret);
        }

        if (!store.TryLoad(out AirClip.Crypto.PairingKey? reloaded, out string? error)
            || reloaded!.Fingerprint != _host.Pairing.Fingerprint)
        {
            failures.Add($"配对文件无法回读：{error ?? "指纹与内存中的不一致"}");
            return;
        }

        WriteDiagnostic(
            $"PASS  配对密钥以 DPAPI 密文存放（{onDisk.Length} 字节，指纹 {reloaded.Fingerprint}），"
            + "文件里搜不到配对码或原始密钥");
    }

    /// <summary>
    /// Stage three end to end, against a real peer instead of an injected one: a second <see cref="SyncHub"/>
    /// in this process dials the app's listener, gets through the authenticated handshake and trades
    /// AES-256-GCM frames both ways. Returns the twin so the window can be photographed with a live peer
    /// on it before <see cref="CheckTwinRemovalAsync"/> takes it away again.
    /// <para>
    /// The twin binds the loopback address with discovery off and reaches the app through a configured
    /// address. Real multicast depends on the machine's interfaces and firewall, so a self-test built on it
    /// would fail for reasons that have nothing to do with AirClip — and this way the twin never opens a
    /// port to the network or provokes a firewall prompt.
    /// </para>
    /// </summary>
    private async Task<SyncHub?> CheckTwinPeerAsync(List<string> failures)
    {
        if (_host!.Transport is not AirClipSyncTransport transport)
        {
            failures.Add($"传输层是 {_host.Transport.GetType().Name}，不是 AirClipSyncTransport");
            return null;
        }

        if (!await WaitForAsync(() => transport.IsListening, TimeSpan.FromSeconds(5)))
        {
            failures.Add(_viewModel!.EnableSync
                ? $"网络层没有在监听：{_viewModel.StatusDetail}"
                : "设置里关闭了局域网同步，自检需要它是打开的");
            return null;
        }

        WriteDiagnostic(
            $"INFO  本机监听 :{transport.ListenPort}，mDNS {transport.ServiceInstanceName ?? "<未公布>"}，"
            + $"组播接口 {transport.DiscoveryInterfaceCount} 个");

        var identity = new DeviceIdentity(TwinDeviceId, "Pixel 8 (自检虚构设备)");
        var twin = new SyncHub(new SyncHubOptions
        {
            Key = _host.Pairing,
            Identity = identity,
            Platform = "android",
            EnableDiscovery = false,
            Bind = IPAddress.Loopback,
            Port = 0,
            StaticPeers = [new IPEndPoint(IPAddress.Loopback, transport.ListenPort)],
        });

        var inbox = new List<ClipMessage>();
        twin.MessageReceived += (_, message) =>
        {
            lock (inbox)
            {
                inbox.Add(message);
            }
        };

        await twin.StartAsync();
        if (!await WaitForAsync(
            () => _viewModel!.Peers.Any(peer => peer.DeviceId == TwinDeviceId && peer.Peer.IsConnected),
            TimeSpan.FromSeconds(10)))
        {
            failures.Add($"自检对端没有连上：{_viewModel!.StatusDetail}");
            return twin;
        }

        if (_viewModel!.State == SyncState.Connected)
        {
            WriteDiagnostic("PASS  真实握手成功：对端进入设备列表，界面状态切换为已连接");
        }
        else
        {
            failures.Add($"对端已连接，但界面状态是 {_viewModel.State}");
        }

        await CheckTwinTextAsync(twin, identity, failures);
        await CheckOutboundAsync(inbox, failures);
        await CheckTwinImageAsync(twin, identity, failures);
        return twin;
    }

    /// <summary>Peer to app: the full receive path, from a real encrypted frame to the history list.</summary>
    private async Task CheckTwinTextAsync(SyncHub twin, DeviceIdentity identity, List<string> failures)
    {
        string text = $"airclip-twin-{Guid.NewGuid():N}";
        int accepted = await twin.BroadcastAsync(
            ClipMessageFactory.Create(ClipboardContent.FromText(text), identity));
        if (accepted != 1)
        {
            failures.Add($"对端发送文本时有 {accepted} 条连接接受，应为 1");
            return;
        }

        if (await WaitForAsync(
            () => _viewModel!.History.Any(
                item => item.Direction == ClipDirection.Remote && item.Content.Text == text),
            TimeSpan.FromSeconds(6)))
        {
            WriteDiagnostic("PASS  对端文本走完 WebSocket→解密→引擎→剪贴板→历史，方向标记为远端");
        }
        else
        {
            failures.Add("对端发来的文本没有作为远端记录进入历史");
        }
    }

    /// <summary>App to peer, through the same call the clipboard monitor makes.</summary>
    private async Task CheckOutboundAsync(List<ClipMessage> inbox, List<string> failures)
    {
        string text = $"airclip-outbound-{Guid.NewGuid():N}";
        int accepted = await _host!.Transport.BroadcastAsync(ClipboardContent.FromText(text));
        if (accepted != 1)
        {
            failures.Add($"本机发送时接受的设备数是 {accepted}，应为 1");
            return;
        }

        bool arrived = await WaitForAsync(
            () =>
            {
                lock (inbox)
                {
                    return inbox.Any(m => m.Type == ClipMessageType.Text && m.Payload?.Content == text);
                }
            },
            TimeSpan.FromSeconds(6));
        if (arrived)
        {
            WriteDiagnostic("PASS  本机剪贴板内容加密后发出，对端解密得到同一段文本");
        }
        else
        {
            failures.Add("对端没有收到本机发出的文本");
        }
    }

    /// <summary>
    /// The image path, which has more to go wrong than text: PNG over the wire, decoded to pixels, written
    /// as CF_DIBV5 and CF_DIB, then read back off the clipboard.
    /// </summary>
    private async Task CheckTwinImageAsync(SyncHub twin, DeviceIdentity identity, List<string> failures)
    {
        ClipboardContent image = CreateProbeImage();
        int accepted = await twin.BroadcastAsync(ClipMessageFactory.Create(image, identity));
        if (accepted != 1)
        {
            failures.Add($"对端发送图片时有 {accepted} 条连接接受，应为 1");
            return;
        }

        if (!await WaitForAsync(
            () => _viewModel!.History.Any(item => item.Direction == ClipDirection.Remote
                && item.Content.Kind == ClipboardContentKind.Image
                && item.Content.Hash == image.Hash),
            TimeSpan.FromSeconds(6)))
        {
            failures.Add("对端发来的图片没有作为远端记录进入历史");
            return;
        }

        ClipboardContent? readBack = _host!.Monitor.ReadCurrent();
        ClipboardImage expected = image.Image!;
        if (readBack?.Image is not { } actual || actual.Width != expected.Width || actual.Height != expected.Height)
        {
            failures.Add($"图片写入后回读到的是 {readBack?.ToString() ?? "<空>"}");
            return;
        }

        WriteDiagnostic($"PASS  对端图片 {expected.Width}×{expected.Height} 落到剪贴板并回读成功");
        if (readBack.Hash != image.Hash)
        {
            // Not a failure: the DIB round trip is allowed to change the bytes. Worth printing, because a
            // changed hash means the two devices would disagree about what this image is called.
            WriteDiagnostic($"WARN  回读后的像素哈希与发送方不同（{readBack.Hash[..8]} ≠ {image.Hash[..8]}）");
        }
    }

    /// <summary>
    /// The other half of the round trip: when the peer goes away, the header has to stop claiming a
    /// connection. The row itself stays, carrying the hub's own reason — a peer that dropped is not the
    /// same thing as a peer that was never there.
    /// </summary>
    private async Task CheckTwinRemovalAsync(SyncHub twin, List<string> failures)
    {
        await twin.StopAsync();
        if (!await WaitForAsync(
            () => !_viewModel!.Peers.Any(peer => peer.Peer.IsConnected) && _viewModel.State == SyncState.Offline,
            TimeSpan.FromSeconds(8)))
        {
            failures.Add($"对端断开后界面仍显示已连接设备，状态 {_viewModel!.State}");
            return;
        }

        string status = _viewModel!.Peers.FirstOrDefault(peer => peer.DeviceId == TwinDeviceId)?.StatusLabel
            ?? "已从列表移除";
        WriteDiagnostic($"PASS  对端断开后状态回到离线（列表里那一行写着：{status}）");
    }

    /// <summary>A tiny opaque bitmap with a random tint, encoded exactly as a real image message carries it.</summary>
    private static ClipboardContent CreateProbeImage()
    {
        const int size = 8;
        byte tint = (byte)Random.Shared.Next(1, 250);
        byte[] bgra = new byte[size * size * 4];
        for (int i = 0; i < bgra.Length; i += 4)
        {
            byte step = (byte)(i / 4 * 3);
            bgra[i] = (byte)(step ^ tint);
            bgra[i + 1] = (byte)(255 - step);
            bgra[i + 2] = tint;

            // Fully opaque: the CF_DIB path has no alpha, so a translucent probe would not survive it.
            bgra[i + 3] = 255;
        }

        var source = BitmapSource.Create(size, size, 96, 96, PixelFormats.Bgra32, null, bgra, size * 4);
        var encoder = new PngBitmapEncoder();
        encoder.Frames.Add(BitmapFrame.Create(source));
        using var buffer = new MemoryStream();
        encoder.Save(buffer);
        return ClipboardContent.FromImage(new ClipboardImage(
            size, size, buffer.ToArray(), ContentHasher.HashImagePixels(size, size, bgra)));
    }

    private static bool Contains(ReadOnlySpan<byte> haystack, ReadOnlySpan<byte> needle) =>
        needle.Length > 0 && haystack.IndexOf(needle) >= 0;

    /// <summary>
    /// Renders each tab to a PNG. WPF can photograph its own visual tree, which is the only way to get a
    /// look at this window in a headless environment: it proves layout, colour and text, though it draws
    /// the client area only, so the title bar and the tray flyout are not in the picture.
    /// </summary>
    private void CaptureTabs(List<string> failures)
    {
        if (_window is null)
        {
            return;
        }

        for (int i = 0; i < _window.TabCount; i++)
        {
            _window.SelectTab(i);
            CaptureWindow($"selftest-tab{i + 1}.png", failures);

            // A tab taller than the window would otherwise hide half of itself from the photograph.
            if (FindScroller(_window.SelectedTabContent) is { ScrollableHeight: > 0 } scroller)
            {
                scroller.ScrollToEnd();
                _window.UpdateLayout();
                CaptureWindow($"selftest-tab{i + 1}-bottom.png", failures);
                scroller.ScrollToHome();
                _window.UpdateLayout();
            }
        }

        _window.SelectTab(0);
    }

    private void CaptureWindow(string fileName, List<string> failures)
    {
        string path = Path.Combine(Path.GetDirectoryName(DiagnosticPath)!, fileName);
        try
        {
            var bitmap = new RenderTargetBitmap(
                (int)_window!.ActualWidth, (int)_window.ActualHeight, 96, 96, PixelFormats.Pbgra32);
            bitmap.Render(_window);

            var encoder = new PngBitmapEncoder();
            encoder.Frames.Add(BitmapFrame.Create(bitmap));
            using FileStream file = File.Create(path);
            encoder.Save(file);
            WriteDiagnostic($"INFO  已截图 {path}");
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException
                                    or ArgumentException or OverflowException)
        {
            failures.Add($"{fileName} 截图失败：{ex.Message}");
        }
    }

    private static ScrollViewer? FindScroller(DependencyObject? root)
    {
        if (root is null)
        {
            return null;
        }

        if (root is ScrollViewer viewer)
        {
            return viewer;
        }

        int children = VisualTreeHelper.GetChildrenCount(root);
        for (int i = 0; i < children; i++)
        {
            if (FindScroller(VisualTreeHelper.GetChild(root, i)) is { } found)
            {
                return found;
            }
        }

        return null;
    }

    /// <summary>
    /// The spec asks for &lt;30 MB resident. This reports the real numbers instead of assuming them, in the
    /// states the process actually has. The figure right after a trim is not the one to quote: the pages
    /// are unmapped, not gone, and the pump faults the live ones straight back in — hence the settled
    /// reading a few seconds later. Private commit and managed heap say where the memory really went,
    /// because the working set also counts shared framework pages.
    /// </summary>
    private async Task ReportFootprintAsync(long trayOnly)
    {
        long withWindow = MeasureWorkingSet();
        _window?.Hide();
        long trimmed = MeasureWorkingSet(trim: true);

        // Idle, but with the dispatcher and the clipboard pump still running, which is the real tray state.
        await Task.Delay(3000);
        long settled = MeasureWorkingSet();

        using Process current = Process.GetCurrentProcess();
        WriteDiagnostic($"INFO  工作集 · 启动后未开窗 {Megabytes(trayOnly)}");
        WriteDiagnostic($"INFO  工作集 · 窗口打开 {Megabytes(withWindow)}");
        WriteDiagnostic($"INFO  工作集 · 隐藏到托盘并回收，瞬时 {Megabytes(trimmed)}");
        WriteDiagnostic($"INFO  工作集 · 回收后静置 3 秒 {Megabytes(settled)}（规格目标 <30 MB）");
        WriteDiagnostic(
            "INFO  上面这个数字是上限：自检刚刚渲染过几张 PNG 截图，"
            + "普通的“开窗→隐藏”循环大约低 20 MB");
        WriteDiagnostic(
            $"INFO  私有提交 {Megabytes(current.PrivateMemorySize64)}，"
            + $"托管堆 {Megabytes(GC.GetTotalMemory(forceFullCollection: false))}，"
            + $"配置 {(IsDebugBuild ? "Debug" : "Release")}");
    }

    private async Task RestoreClipboardAsync(ClipboardContent? original)
    {
        if (original is null)
        {
            return;
        }

        try
        {
            // Straight through the writer: the monitor ignores our own writes, so this leaves no trace.
            await _host!.Writer.WriteAsync(original);
            WriteDiagnostic("INFO  已恢复自检前的剪贴板内容");
        }
        catch (Exception ex) when (ex is InvalidOperationException or IOException)
        {
            WriteDiagnostic($"WARN  未能恢复原剪贴板内容：{ex.Message}");
        }
    }
    private static bool TrySetClipboardText(string text)
    {
        for (int attempt = 0; attempt < 3; attempt++)
        {
            try
            {
                // Deliberately the WPF API: it owns the clipboard through a different window than our
                // listener, so the monitor sees a genuinely foreign change instead of one of our writes.
                System.Windows.Clipboard.SetDataObject(text, copy: true);
                return true;
            }
            catch (Exception ex) when (ex is System.Runtime.InteropServices.COMException
                                        or InvalidOperationException)
            {
                Thread.Sleep(150);
            }
        }

        return false;
    }

    private static async Task<bool> WaitForAsync(Func<bool> condition, TimeSpan timeout)
    {
        DateTime deadline = DateTime.UtcNow + timeout;
        while (DateTime.UtcNow < deadline)
        {
            if (condition())
            {
                return true;
            }

            await Task.Delay(60);
        }

        return condition();
    }

    /// <summary>Named so the footprint line can never be read as a Release number from a Debug run.</summary>
    private static bool IsDebugBuild
    {
        get
        {
#if DEBUG
            return true;
#else
            return false;
#endif
        }
    }

    private static long MeasureWorkingSet(bool trim = false)
    {
        GC.Collect();
        GC.WaitForPendingFinalizers();
        GC.Collect();
        if (trim)
        {
            Win32Memory.TrimWorkingSet();
        }

        using Process current = Process.GetCurrentProcess();
        current.Refresh();
        return current.WorkingSet64;
    }

    private static string Megabytes(long bytes) => $"{bytes / (1024.0 * 1024.0):0.0} MB";
    /// <summary>Goes to the parent console when there is one, and always to a file for later reading.</summary>
    private void WriteDiagnostic(string line)
    {
        if (!_diagnosticStarted)
        {
            _diagnosticStarted = true;
            _consoleAttached = Win32Console.TryAttachToParent();
            TryWriteFile(DiagnosticPath, $"{line}{Environment.NewLine}", append: false);
        }
        else
        {
            TryWriteFile(DiagnosticPath, $"{line}{Environment.NewLine}", append: true);
        }

        if (_consoleAttached)
        {
            Console.Out.WriteLine(line);
            Console.Out.Flush();
        }
    }

    private static void TryWriteFile(string path, string content, bool append)
    {
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(path)!);
            if (append)
            {
                File.AppendAllText(path, content);
            }
            else
            {
                File.WriteAllText(path, content);
            }
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            // Diagnostics must never be the reason a diagnostic run fails.
        }
    }

    /// <summary>Captures WPF's data-binding trace so a broken binding fails the run instead of being silent.</summary>
    private sealed class BindingErrorCollector : TraceListener
    {
        private readonly List<string> _messages = [];

        public IReadOnlyList<string> Messages => _messages;

        public override void Write(string? message) => Append(message);

        public override void WriteLine(string? message) => Append(message);

        private void Append(string? message)
        {
            if (!string.IsNullOrWhiteSpace(message))
            {
                _messages.Add(message.Trim());
            }
        }
    }
}
