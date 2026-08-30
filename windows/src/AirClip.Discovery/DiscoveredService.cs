using System.Net;
using AirClip.Discovery.Dns;

namespace AirClip.Discovery;

/// <summary>
/// A service somebody else is advertising, assembled from however many packets it took to learn about it.
/// <para>
/// <see cref="Address"/> falls back to the address the answer arrived from when a responder omits its A
/// record, which is common and harmless: the packet came from the peer, so the source address is the peer.
/// </para>
/// </summary>
public sealed record DiscoveredService
{
    public required string InstanceName { get; init; }

    public required string ServiceType { get; init; }

    public string HostName { get; init; } = string.Empty;

    public int Port { get; init; }

    public IPAddress? Address { get; init; }

    public IReadOnlyList<string> TxtEntries { get; init; } = [];

    public DateTimeOffset LastSeen { get; init; }

    public DateTimeOffset ExpiresAt { get; init; }

    /// <summary>The first label of the instance name — what a user would recognise in a list.</summary>
    public string InstanceLabel
    {
        get
        {
            string name = DnsName.Normalise(InstanceName);
            int dot = name.IndexOf('.', StringComparison.Ordinal);
            return dot < 0 ? name : name[..dot];
        }
    }

    public string DeviceId => Txt(ServiceProfile.TxtDeviceId) ?? string.Empty;

    public string DeviceName
    {
        get
        {
            string? name = Txt(ServiceProfile.TxtDeviceName);
            return string.IsNullOrWhiteSpace(name) ? InstanceLabel : name;
        }
    }

    public string Platform => Txt(ServiceProfile.TxtPlatform) ?? "unknown";

    public string? Fingerprint => Txt(ServiceProfile.TxtFingerprint);

    /// <summary>Enough to dial: without a port and an address there is nothing to connect to.</summary>
    public bool IsDialable => Port is > 0 and <= 65535 && Address is not null;

    public IPEndPoint? EndPoint => IsDialable ? new IPEndPoint(Address!, Port) : null;

    public string? Txt(string key)
    {
        foreach (string entry in TxtEntries)
        {
            int split = entry.IndexOf('=', StringComparison.Ordinal);
            if (split > 0 && entry.AsSpan(0, split).Equals(key, StringComparison.OrdinalIgnoreCase))
            {
                return entry[(split + 1)..];
            }
        }

        return null;
    }

    public override string ToString() =>
        $"{DeviceName} · {Platform} · {(EndPoint?.ToString() ?? "地址未知")} · {InstanceLabel}";
}
