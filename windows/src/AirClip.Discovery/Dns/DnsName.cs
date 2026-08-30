using System.Text;

namespace AirClip.Discovery.Dns;

/// <summary>
/// Name handling for DNS-SD. Two things bite here and both are handled in one place: the trailing dot
/// (<c>_airclip._tcp.local.</c> and <c>_airclip._tcp.local</c> are the same name, and settings files
/// contain both) and the dot inside a label — a device called "Anna's MacBook 2.0" would otherwise
/// announce an instance name that every resolver reads as two labels.
/// </summary>
public static class DnsName
{
    public const int MaxLabelBytes = 63;

    public static string Normalise(string? name) => (name ?? string.Empty).Trim().TrimEnd('.');

    public static bool Equal(string? left, string? right) =>
        string.Equals(Normalise(left), Normalise(right), StringComparison.OrdinalIgnoreCase);

    /// <summary>True when <paramref name="candidate"/> is an instance of <paramref name="serviceType"/>.</summary>
    public static bool IsInstanceOf(string? candidate, string? serviceType)
    {
        string instance = Normalise(candidate);
        string type = Normalise(serviceType);
        return instance.Length > type.Length + 1
            && instance.EndsWith(type, StringComparison.OrdinalIgnoreCase)
            && instance[instance.Length - type.Length - 1] == '.';
    }

    /// <summary>
    /// Turns arbitrary user text into one safe label: dots and control characters become hyphens, and the
    /// result is trimmed to 63 <em>bytes</em> rather than 63 characters, since a Chinese device name spends
    /// three bytes per character and cutting on the wrong boundary would produce invalid UTF-8.
    /// </summary>
    public static string SanitiseLabel(string? text, string fallback = "AirClip")
    {
        string source = (text ?? string.Empty).Trim();
        var builder = new StringBuilder(source.Length);
        foreach (char c in source)
        {
            builder.Append(c is '.' or '\\' or '\0' || char.IsControl(c) ? '-' : c);
        }

        string label = builder.ToString().Trim('-', ' ');
        if (label.Length == 0)
        {
            return fallback;
        }

        while (Encoding.UTF8.GetByteCount(label) > MaxLabelBytes)
        {
            label = label[..^1];
        }

        return label;
    }
}
