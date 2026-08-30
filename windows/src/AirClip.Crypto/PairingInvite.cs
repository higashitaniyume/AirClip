using System.Text;

namespace AirClip.Crypto;

/// <summary>
/// The payload behind a pairing QR code: <c>airclip://pair?v=1&amp;k=…&amp;n=…&amp;s=…&amp;p=…</c>. The phone scans
/// it, joins the group and knows where to look, all without anyone typing thirty-two characters.
/// <para>
/// <see cref="ToString"/> deliberately does <em>not</em> return the URI. The URI contains the group
/// secret, and a type whose default string form is a secret ends up in a log file sooner or later; call
/// <see cref="ToUri"/> when the secret is genuinely what is wanted.
/// </para>
/// </summary>
public sealed class PairingInvite
{
    public const string Scheme = "airclip";
    public const string PairHost = "pair";
    public const string Version = "1";

    public PairingInvite(PairingKey key, string deviceName, string serviceName, int port)
    {
        ArgumentNullException.ThrowIfNull(key);
        Key = key;
        DeviceName = string.IsNullOrWhiteSpace(deviceName) ? "AirClip" : deviceName.Trim();
        ServiceName = string.IsNullOrWhiteSpace(serviceName) ? "_airclip._tcp.local." : serviceName.Trim();
        Port = port is >= 1 and <= 65535 ? port : 47653;
    }

    public PairingKey Key { get; }

    public string DeviceName { get; }

    public string ServiceName { get; }

    public int Port { get; }

    public Uri ToUri()
    {
        var builder = new StringBuilder($"{Scheme}://{PairHost}?v={Version}");
        builder.Append("&k=").Append(Uri.EscapeDataString(Key.Code));
        builder.Append("&n=").Append(Uri.EscapeDataString(DeviceName));
        builder.Append("&s=").Append(Uri.EscapeDataString(ServiceName));
        builder.Append("&p=").Append(Port);
        return new Uri(builder.ToString());
    }

    public static bool TryParse(string? text, out PairingInvite? invite)
    {
        invite = null;
        if (string.IsNullOrWhiteSpace(text)
            || !Uri.TryCreate(text.Trim(), UriKind.Absolute, out Uri? uri)
            || !string.Equals(uri.Scheme, Scheme, StringComparison.OrdinalIgnoreCase)
            || !string.Equals(uri.Host, PairHost, StringComparison.OrdinalIgnoreCase))
        {
            return false;
        }

        Dictionary<string, string> query = ParseQuery(uri.Query);
        if (!query.TryGetValue("k", out string? code) || !PairingKey.TryParse(code, out PairingKey? key))
        {
            return false;
        }

        query.TryGetValue("n", out string? name);
        query.TryGetValue("s", out string? service);
        int port = query.TryGetValue("p", out string? rawPort) && int.TryParse(rawPort, out int parsed)
            ? parsed
            : 47653;

        invite = new PairingInvite(key!, name ?? "AirClip", service ?? "_airclip._tcp.local.", port);
        return true;
    }

    /// <summary>Safe to log: describes the invite by fingerprint and omits the secret entirely.</summary>
    public override string ToString() => $"配对邀请 {DeviceName} · {ServiceName} · :{Port} · {Key.Fingerprint}";

    private static Dictionary<string, string> ParseQuery(string query)
    {
        var values = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        foreach (string pair in query.TrimStart('?').Split('&', StringSplitOptions.RemoveEmptyEntries))
        {
            int split = pair.IndexOf('=', StringComparison.Ordinal);
            if (split > 0)
            {
                values[Uri.UnescapeDataString(pair[..split])] = Uri.UnescapeDataString(pair[(split + 1)..]);
            }
        }

        return values;
    }
}
