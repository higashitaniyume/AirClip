using System.Windows;
using System.Windows.Threading;
using AirClip.App.Services;
using AirClip.App.Tray;
using AirClip.App.ViewModels;
using AirClip.App.Views;
using AirClip.Platform.Windows;
using Microsoft.Extensions.Logging;

namespace AirClip.App;

/// <summary>
/// Process shell: single instance, tray icon, lazily created window. The window is only built when it
/// is first shown, so a machine that boots straight to the tray never pays for the visual tree.
/// </summary>
public partial class App : Application
{
    private const string InstanceMutexName = @"Local\AirClip.SingleInstance";
    private const string ShowWindowEventName = @"Local\AirClip.ShowWindow";

    private Mutex? _instanceMutex;
    private EventWaitHandle? _showWindowRequest;
    private RegisteredWaitHandle? _showWindowRegistration;
    private SettingsStore? _store;
    private FileLogger? _logProvider;
    private ILogger? _logger;
    private AppHost? _host;
    private MainViewModel? _viewModel;
    private Win32TrayIcon? _tray;
    private MainWindow? _window;
    private bool _exiting;

    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);

        bool selfTest = HasFlag(e.Args, "--selftest");
        _instanceMutex = new Mutex(initiallyOwned: true, InstanceMutexName, out bool isFirstInstance);
        if (!isFirstInstance)
        {
            // A second launch is a request to surface the running tray instance, not a new process.
            if (selfTest)
            {
                WriteDiagnostic("SKIP  另一个 AirClip 实例正在占用剪贴板监听器，请先退出它");
                Shutdown(2);
            }
            else
            {
                SignalRunningInstance();
                Shutdown(0);
            }

            return;
        }
        _store = new SettingsStore();
        AppSettings settings = _store.Load();
        _store.Save(settings);

        _logProvider = new FileLogger(_store.Directory);
        _logger = _logProvider.CreateLogger("App");
        _host = new AppHost(settings, _logProvider, _store.Directory);
        _viewModel = new MainViewModel(_host, _store, Dispatcher);
        _viewModel.StateChanged += OnStateChanged;
        _viewModel.BalloonRequested += (title, message) => _tray?.ShowBalloon(title, message);

        DispatcherUnhandledException += OnDispatcherUnhandledException;

        CreateTray();
        _viewModel.Start();
        ListenForShowRequests();
        _logger.LogInformation(
            "AirClip started as {Device} ({Id}), settings at {Path}",
            settings.DeviceName, settings.DeviceId, _store.FilePath);

        if (selfTest)
        {
            _ = RunSelfTestAsync();
            return;
        }

        bool minimised = settings.StartMinimised
            || HasFlag(e.Args, "--minimised")
            || HasFlag(e.Args, "--minimized");
        if (!minimised)
        {
            ShowMainWindow();
        }

        // Startup JIT and XAML parsing leave a lot of one-off pages resident. A process that then sits
        // in the tray for hours should not keep paying for them; they fault back in if they are needed.
        Dispatcher.BeginInvoke(DispatcherPriority.ApplicationIdle, () => Win32Memory.TrimWorkingSet());
    }

    protected override void OnExit(ExitEventArgs e)
    {
        _logger?.LogInformation("AirClip exiting with code {Code}", e.ApplicationExitCode);
        _showWindowRegistration?.Unregister(null);
        _showWindowRequest?.Dispose();
        _tray?.Dispose();
        _host?.Dispose();
        ReleaseInstanceMutex();
        base.OnExit(e);
    }

    private static bool HasFlag(string[] args, string flag) =>
        args.Any(a => string.Equals(a, flag, StringComparison.OrdinalIgnoreCase));

    private void CreateTray()
    {
        _tray = new Win32TrayIcon(new FileLogger<Win32TrayIcon>(_logProvider!));

        // Both tray events arrive on the tray's own message-pump thread.
        _tray.Activated += () => Dispatcher.BeginInvoke(ShowMainWindow);
        _tray.CommandInvoked += id => Dispatcher.BeginInvoke(() => OnTrayCommand(id));
        _tray.Show(TrayArtwork.CreateTrayIcon(_viewModel!.State), Tooltip(_viewModel.State));
        RefreshTrayMenu();
    }

    private void RefreshTrayMenu() => _tray?.SetMenu(
    [
        TrayMenuItem.Command("show", "显示主窗口"),
        TrayMenuItem.Command("send", "立即发送剪贴板"),
        TrayMenuItem.Command("pause", _viewModel!.PauseLabel, isChecked: _viewModel.IsPaused),
        TrayMenuItem.Separator,
        TrayMenuItem.Command("exit", "退出 AirClip"),
    ]);

    private void OnTrayCommand(string id)
    {
        switch (id)
        {
            case "show":
                ShowMainWindow();
                break;
            case "send":
                _viewModel!.SendCurrentClipboard();
                break;
            case "pause":
                _viewModel!.IsPaused = !_viewModel.IsPaused;
                break;
            case "exit":
                _ = ExitAsync();
                break;
        }
    }
    /// <summary>Tray icon, tooltip and the checkable pause item all follow the view model's state.</summary>
    private void OnStateChanged(SyncState state)
    {
        _tray?.SetIcon(TrayArtwork.CreateTrayIcon(state));
        _tray?.SetTooltip(Tooltip(state));
        RefreshTrayMenu();
    }

    private string Tooltip(SyncState state) => state switch
    {
        SyncState.Connected => $"AirClip · {_viewModel!.Peers.Count} 台设备已连接",
        SyncState.Paused => "AirClip · 已暂停",
        _ => "AirClip · 离线",
    };

    private void ShowMainWindow()
    {
        _window ??= new MainWindow(_viewModel!);
        _window.ShowFromTray();
    }

    private void ListenForShowRequests()
    {
        _showWindowRequest = new EventWaitHandle(false, EventResetMode.AutoReset, ShowWindowEventName);
        _showWindowRegistration = ThreadPool.RegisterWaitForSingleObject(
            _showWindowRequest,
            (_, _) => Dispatcher.BeginInvoke(ShowMainWindow),
            null,
            Timeout.Infinite,
            executeOnlyOnce: false);
    }

    private static void SignalRunningInstance()
    {
        try
        {
            if (EventWaitHandle.TryOpenExisting(ShowWindowEventName, out EventWaitHandle? handle))
            {
                using (handle)
                {
                    handle.Set();
                }
            }
        }
        catch (WaitHandleCannotBeOpenedException)
        {
            // The first instance is still starting up; nothing useful to do but exit quietly.
        }
    }
    private async Task ExitAsync()
    {
        if (_exiting)
        {
            return;
        }

        _exiting = true;
        if (_window is not null)
        {
            _window.AllowClose = true;
            _window.Close();
        }

        if (_viewModel is not null)
        {
            await _viewModel.StopAsync();
        }

        Shutdown(0);
    }

    private void ReleaseInstanceMutex()
    {
        if (_instanceMutex is null)
        {
            return;
        }

        try
        {
            _instanceMutex.ReleaseMutex();
        }
        catch (Exception ex) when (ex is ApplicationException or ObjectDisposedException)
        {
            // Not the owner (the second-instance path never took it); closing the handle is enough.
        }

        _instanceMutex.Dispose();
        _instanceMutex = null;
    }

    /// <summary>A resident tray app should survive a UI-thread fault; the log is how it gets diagnosed.</summary>
    private void OnDispatcherUnhandledException(object sender, DispatcherUnhandledExceptionEventArgs e)
    {
        _logger?.LogError(e.Exception, "Unhandled exception on the UI thread");
        e.Handled = true;
    }
}
