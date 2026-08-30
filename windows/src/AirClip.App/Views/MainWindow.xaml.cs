using System.ComponentModel;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Threading;
using AirClip.App.Services;
using AirClip.App.Tray;
using AirClip.App.ViewModels;
using AirClip.Platform.Windows;

namespace AirClip.App.Views;

/// <summary>
/// The window is a view over the resident process, not the process itself: closing it hides to the
/// tray, and only <see cref="AllowClose"/> (set by the tray's exit command) lets it really close.
/// </summary>
public partial class MainWindow : Window
{
    public MainWindow(MainViewModel viewModel)
    {
        InitializeComponent();
        DataContext = viewModel;
        Icon = TrayArtwork.CreateWindowIcon(viewModel.State);
        viewModel.StateChanged += OnStateChanged;
    }

    public bool AllowClose { get; set; }

    public void ShowFromTray()
    {
        Show();
        WindowState = WindowState.Normal;
        Activate();
        Topmost = true;
        Topmost = false;
        Focus();
    }

    protected override void OnClosing(CancelEventArgs e)
    {
        if (!AllowClose)
        {
            e.Cancel = true;
            HideToTray();
        }

        base.OnClosing(e);
    }

    private void OnStateChanged(SyncState state) => Icon = TrayArtwork.CreateWindowIcon(state);

    private void OnHideToTray(object sender, RoutedEventArgs e) => HideToTray();

    /// <summary>
    /// Hides the window and then releases its resident pages, once the dispatcher has finished the
    /// teardown the hide queues. The window object stays alive, so showing it again is still instant.
    /// </summary>
    private void HideToTray()
    {
        Hide();
        Dispatcher.BeginInvoke(DispatcherPriority.ApplicationIdle, () => Win32Memory.TrimWorkingSet());
    }

    /// <summary>
    /// Selects every tab in turn so a diagnostic run realises all three visual trees. A TabControl only
    /// builds the selected tab's content, so bindings on the other two would never be exercised.
    /// </summary>
    internal void RealiseAllTabs()
    {
        for (int i = 0; i < TabCount; i++)
        {
            SelectTab(i);
        }

        SelectTab(0);
    }

    /// <summary>Tab count and selection, so the diagnostic run can photograph one tab at a time.</summary>
    internal int TabCount => Tabs.Items.Count;

    internal void SelectTab(int index)
    {
        Tabs.SelectedIndex = index;
        UpdateLayout();
    }

    /// <summary>Root of the selected tab, so a diagnostic run can find that tab's own scroller.</summary>
    internal DependencyObject? SelectedTabContent => (Tabs.SelectedItem as TabItem)?.Content as DependencyObject;
}
