using AirClip.Discovery.Dns;

namespace AirClip.Discovery;

/// <summary>
/// What this device publishes. The instance label carries a slice of the device id, so two laptops that
/// are both called "办公室" still announce two distinct services instead of fighting over one name.
/// </summary>
public sealed record ServiceProfile
{
    /// <summary>
    /// Two minutes. DNS-SD suggests far longer for PTR records, but a clipboard peer that has been
    /// unplugged should disappear from the list in about the time it takes to notice it is gone.
    /// </summary>
    public const uint DefaultTtl = 120;

    public const string TxtDeviceId = "id";
    public const string TxtDeviceName = "name";
    public const string TxtPlatform = "plat";
    public const string TxtVersion = "ver";
    public const string TxtFingerprint = "fp";

    public required string ServiceType { get; init; }

    public required string InstanceName { get; init; }

    public required string HostName { get; init; }

    public required int Port { get; init; }

    public required string DeviceId { get; init; }

    public IReadOnlyList<string> TxtEntries { get; init; } = [];

    public uint Ttl { get; init; } = DefaultTtl;

    public static ServiceProfile Create(
        string serviceType,
        string deviceId,
        string deviceName,
        int port,
        string? fingerprint = null,
        string platform = "windows",
        string version = "1.0")
    {
        string type = DnsName.Normalise(string.IsNullOrWhiteSpace(serviceType) ? "_airclip._tcp.local" : serviceType);
        string id = string.IsNullOrWhiteSpace(deviceId) ? "unknown" : deviceId.Trim();
        string suffix = id.Length >= 4 ? id[^4..] : id;
        string label = DnsName.SanitiseLabel($"{DnsName.SanitiseLabel(deviceName)}-{suffix}");

        var entries = new List<string>
        {
            $"{TxtDeviceId}={id}",
            $"{TxtDeviceName}={deviceName?.Trim() ?? string.Empty}",
            $"{TxtPlatform}={platform}",
            $"{TxtVersion}={version}",
        };

        if (!string.IsNullOrWhiteSpace(fingerprint))
        {
            // The pairing fingerprint, never the code: it lets a mismatched group be spotted in the peer
            // list instead of after a failed handshake, and it says nothing usable about the secret.
            entries.Add($"{TxtFingerprint}={fingerprint}");
        }

        return new ServiceProfile
        {
            ServiceType = type,
            InstanceName = $"{label}.{type}",
            HostName = $"airclip-{(id.Length >= 8 ? id[..8] : id)}.local",
            Port = port,
            DeviceId = id,
            TxtEntries = entries,
        };
    }
}
