using AirClip.Core.Clipboard;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Logging.Abstractions;

namespace AirClip.Core.Sync;

/// <summary>
/// Wires the platform clipboard monitor/writer to the loop guard. Platform-agnostic so the
/// filtering rules can be unit tested without touching a real clipboard.
/// </summary>
public sealed class ClipboardSyncEngine : IDisposable
{
    private readonly IClipboardMonitor _monitor;
    private readonly IClipboardWriter _writer;
    private readonly LoopGuard _loopGuard;
    private readonly ClipboardOptions _options;
    private readonly ILogger _logger;
    private bool _started;

    public ClipboardSyncEngine(
        IClipboardMonitor monitor,
        IClipboardWriter writer,
        LoopGuard loopGuard,
        ClipboardOptions? options = null,
        ILogger<ClipboardSyncEngine>? logger = null)
    {
        _monitor = monitor;
        _writer = writer;
        _loopGuard = loopGuard;
        _options = options ?? ClipboardOptions.Default;
        _logger = logger ?? NullLogger<ClipboardSyncEngine>.Instance;
    }

    /// <summary>Local clipboard content that passed every filter and should go out to peers.</summary>
    public event EventHandler<ClipboardChangedEventArgs>? LocalClipboardPublished;

    public void Start()
    {
        if (_started)
        {
            return;
        }

        _monitor.Changed += OnMonitorChanged;
        _monitor.Start();
        _started = true;
    }

    public void Stop()
    {
        if (!_started)
        {
            return;
        }

        _monitor.Changed -= OnMonitorChanged;
        _monitor.Stop();
        _started = false;
    }

    /// <summary>Manual "send clipboard now" path, used by tray menu and hotkeys.</summary>
    public bool PublishCurrent()
    {
        ClipboardContent? content = _monitor.ReadCurrent();
        return content is not null && TryPublish(content);
    }

    public async Task<bool> ApplyRemoteAsync(ClipboardContent content, CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(content);

        if (!IsWithinLimits(content))
        {
            return false;
        }

        IDisposable? scope = _loopGuard.TryBeginApply(content.Hash);
        if (scope is null)
        {
            _logger.LogDebug("Skipped remote {Content}: already seen locally", content);
            return false;
        }

        using (scope)
        {
            await _writer.WriteAsync(content, cancellationToken).ConfigureAwait(false);
        }

        _logger.LogInformation("Applied remote {Content}", content);
        return true;
    }

    public void Dispose() => Stop();

    private void OnMonitorChanged(object? sender, ClipboardChangedEventArgs e) => TryPublish(e.Content);

    private bool TryPublish(ClipboardContent content)
    {
        if (!IsWithinLimits(content))
        {
            return false;
        }

        if (!_loopGuard.TryBeginPublish(content.Hash))
        {
            _logger.LogDebug("Suppressed local {Content}: echo of remote write", content);
            return false;
        }

        _logger.LogInformation("Publishing local {Content}", content);
        LocalClipboardPublished?.Invoke(this, new ClipboardChangedEventArgs(content));
        return true;
    }

    private bool IsWithinLimits(ClipboardContent content)
    {
        if (content.Kind == ClipboardContentKind.Image && !_options.SyncImages)
        {
            return false;
        }

        int limit = content.Kind == ClipboardContentKind.Text ? _options.MaxTextBytes : _options.MaxImageBytes;
        if (content.ByteSize <= limit)
        {
            return true;
        }

        _logger.LogWarning("Dropped {Content}: exceeds {Limit} bytes", content, limit);
        return false;
    }
}
