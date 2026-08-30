using AirClip.Core.Clipboard;
using AirClip.Platform.Windows.Interop;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Logging.Abstractions;

namespace AirClip.Platform.Windows;

/// <summary>
/// Event-driven clipboard listener: no polling. The message-only window is registered with
/// <c>AddClipboardFormatListener</c> and every WM_CLIPBOARDUPDATE is debounced, because one logical
/// copy usually produces several notifications while the source app publishes its formats.
/// </summary>
public sealed class Win32ClipboardMonitor : IClipboardMonitor, IDisposable
{
    private const int MaxLockedClipboardRetries = 2;

    private readonly Win32ClipboardHost _host;
    private readonly ClipboardOptions _options;
    private readonly TimeProvider _time;
    private readonly ILogger _logger;
    private readonly ClipboardReader _reader;
    private readonly object _sync = new();
    private ITimer? _debounce;
    private uint _lastSequence;
    private int _lockedRetries;
    private bool _running;
    private bool _disposed;

    public Win32ClipboardMonitor(
        Win32ClipboardHost host,
        ClipboardOptions? options = null,
        TimeProvider? timeProvider = null,
        ILogger<Win32ClipboardMonitor>? logger = null)
    {
        _host = host;
        _options = options ?? ClipboardOptions.Default;
        _time = timeProvider ?? TimeProvider.System;
        _logger = logger ?? NullLogger<Win32ClipboardMonitor>.Instance;
        _reader = new ClipboardReader(_options, _logger);
    }

    public event EventHandler<ClipboardChangedEventArgs>? Changed;

    public bool IsRunning => _running;

    public void Start()
    {
        lock (_sync)
        {
            ObjectDisposedException.ThrowIf(_disposed, this);
            if (_running)
            {
                return;
            }

            _host.Start();
            _lastSequence = NativeMethods.GetClipboardSequenceNumber();
            _debounce = _time.CreateTimer(
                _ => _host.Post(ProcessUpdate), null, Timeout.InfiniteTimeSpan, Timeout.InfiniteTimeSpan);
            _host.ClipboardUpdated += OnClipboardUpdated;
            _host.AddClipboardListener();
            _running = true;
            _logger.LogInformation(
                "Clipboard monitor started, debounce {Debounce}ms", _options.DebounceInterval.TotalMilliseconds);
        }
    }

    public void Stop()
    {
        lock (_sync)
        {
            if (!_running)
            {
                return;
            }

            _host.ClipboardUpdated -= OnClipboardUpdated;
            _host.RemoveClipboardListener();
            _debounce?.Dispose();
            _debounce = null;
            _running = false;
            _logger.LogInformation("Clipboard monitor stopped");
        }
    }

    public ClipboardContent? ReadCurrent()
    {
        _host.Start();
        return _host.Invoke(ReadWithSession);
    }

    public void Dispose()
    {
        Stop();
        _disposed = true;
    }

    private void OnClipboardUpdated()
    {
        _lockedRetries = 0;
        ScheduleRead(_options.DebounceInterval);
    }

    private void ScheduleRead(TimeSpan delay) => _debounce?.Change(delay, Timeout.InfiniteTimeSpan);

    private void ProcessUpdate()
    {
        uint sequence = NativeMethods.GetClipboardSequenceNumber();
        if (sequence == _lastSequence)
        {
            return;
        }

        if (NativeMethods.GetClipboardOwner() == _host.Handle)
        {
            _lastSequence = sequence;
            _logger.LogDebug("Ignored the clipboard update caused by our own write");
            return;
        }

        if (!ClipboardSession.TryOpen(_host.Handle, out ClipboardSession session))
        {
            if (_lockedRetries++ < MaxLockedClipboardRetries)
            {
                ScheduleRead(TimeSpan.FromMilliseconds(200));
            }
            else
            {
                _logger.LogWarning("Clipboard stayed locked by another process; dropped this update");
            }

            return;
        }

        ClipboardContent? content;
        using (session)
        {
            content = _reader.Read();
        }

        _lastSequence = sequence;
        _lockedRetries = 0;

        if (content is not null)
        {
            Changed?.Invoke(this, new ClipboardChangedEventArgs(content));
        }
    }

    private ClipboardContent? ReadWithSession()
    {
        if (!ClipboardSession.TryOpen(_host.Handle, out ClipboardSession session))
        {
            _logger.LogWarning("Could not open the clipboard for reading");
            return null;
        }

        using (session)
        {
            return _reader.Read();
        }
    }
}
