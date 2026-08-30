namespace AirClip.Core.Clipboard;

public sealed class ClipboardChangedEventArgs(ClipboardContent content) : EventArgs
{
    public ClipboardContent Content { get; } = content;
}

/// <summary>Raises <see cref="Changed"/> for genuine local clipboard changes only.</summary>
public interface IClipboardMonitor
{
    event EventHandler<ClipboardChangedEventArgs>? Changed;

    bool IsRunning { get; }

    void Start();

    void Stop();

    /// <summary>Reads the clipboard on demand, bypassing change notifications.</summary>
    ClipboardContent? ReadCurrent();
}

public interface IClipboardWriter
{
    Task WriteAsync(ClipboardContent content, CancellationToken cancellationToken = default);
}
