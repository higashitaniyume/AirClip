namespace AirClip.Core.Clipboard;

public sealed class ClipboardOptions
{
    public static ClipboardOptions Default { get; } = new();

    /// <summary>
    /// A single copy can raise several WM_CLIPBOARDUPDATE messages; coalesce them. Read once when the
    /// monitor starts, so a change only takes effect after a restart.
    /// </summary>
    public TimeSpan DebounceInterval { get; set; } = TimeSpan.FromMilliseconds(120);

    /// <summary>
    /// How long after writing remote data we refuse to publish local changes. Read once when the
    /// loop guard is constructed.
    /// </summary>
    public TimeSpan RemoteWriteSuppression { get; set; } = TimeSpan.FromSeconds(2);

    /// <summary>Lifetime of a hash in the recently-seen set. Read once when the loop guard is constructed.</summary>
    public TimeSpan HashTtl { get; set; } = TimeSpan.FromSeconds(20);

    /// <summary>Evaluated per clipboard item, so the UI can change it while the app runs.</summary>
    public int MaxTextBytes { get; set; } = 2 * 1024 * 1024;

    /// <summary>Evaluated per clipboard item, so the UI can change it while the app runs.</summary>
    public int MaxImageBytes { get; set; } = 8 * 1024 * 1024;

    /// <summary>
    /// Skip content marked by password managers as excluded from clipboard monitors. Evaluated per
    /// clipboard read.
    /// </summary>
    public bool HonorSensitiveContentMarkers { get; set; } = true;

    /// <summary>Evaluated per clipboard item.</summary>
    public bool SyncImages { get; set; } = true;
}
