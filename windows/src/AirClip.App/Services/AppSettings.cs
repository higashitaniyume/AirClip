using System.Text.Json.Serialization;

namespace AirClip.App.Services;

/// <summary>User-visible configuration, persisted as JSON under %APPDATA%\AirClip.</summary>
public sealed class AppSettings
{
    [JsonPropertyName("device_id")]
    public string DeviceId { get; set; } = string.Empty;

    [JsonPropertyName("device_name")]
    public string DeviceName { get; set; } = string.Empty;

    [JsonPropertyName("service_name")]
    public string ServiceName { get; set; } = "_airclip._tcp.local.";

    [JsonPropertyName("listen_port")]
    public int ListenPort { get; set; } = 47653;

    /// <summary>
    /// Whether the network side runs at all. Off means no listening socket and no mDNS announcement —
    /// clipboard monitoring and history still work, which is how the app behaves on a network the user
    /// does not trust.
    /// </summary>
    [JsonPropertyName("enable_sync")]
    public bool EnableSync { get; set; } = true;

    [JsonPropertyName("sync_images")]
    public bool SyncImages { get; set; } = true;

    [JsonPropertyName("honor_sensitive_markers")]
    public bool HonorSensitiveMarkers { get; set; } = true;

    [JsonPropertyName("keep_history")]
    public bool KeepHistory { get; set; } = true;

    [JsonPropertyName("history_limit")]
    public int HistoryLimit { get; set; } = 100;

    [JsonPropertyName("max_text_kb")]
    public int MaxTextKb { get; set; } = 2048;

    [JsonPropertyName("max_image_kb")]
    public int MaxImageKb { get; set; } = 8192;

    [JsonPropertyName("debounce_ms")]
    public int DebounceMs { get; set; } = 120;

    [JsonPropertyName("start_with_windows")]
    public bool StartWithWindows { get; set; }

    [JsonPropertyName("start_minimised")]
    public bool StartMinimised { get; set; } = true;

    [JsonPropertyName("notify_on_receive")]
    public bool NotifyOnReceive { get; set; } = true;

    /// <summary>Fills in the machine-specific defaults that cannot be baked into the class.</summary>
    public AppSettings EnsureIdentity()
    {
        if (string.IsNullOrWhiteSpace(DeviceId))
        {
            DeviceId = Guid.NewGuid().ToString("N")[..12];
        }

        if (string.IsNullOrWhiteSpace(DeviceName))
        {
            DeviceName = Environment.MachineName;
        }

        HistoryLimit = Math.Clamp(HistoryLimit, 10, 500);
        MaxTextKb = Math.Clamp(MaxTextKb, 1, 32 * 1024);
        MaxImageKb = Math.Clamp(MaxImageKb, 16, 64 * 1024);
        DebounceMs = Math.Clamp(DebounceMs, 20, 2000);
        ListenPort = ListenPort is >= 1024 and <= 65535 ? ListenPort : 47653;
        return this;
    }
}
