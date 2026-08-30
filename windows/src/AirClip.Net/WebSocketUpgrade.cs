using System.Net.WebSockets;
using System.Security.Cryptography;
using System.Text;

namespace AirClip.Net;

/// <summary>
/// The server half of the WebSocket opening handshake, done by hand over a plain TCP stream.
/// <para>
/// The reason not to use <c>HttpListener</c> is practical: on Windows it goes through http.sys, which
/// requires either an administrator-registered URL ACL or an elevated process for a non-localhost prefix.
/// A clipboard utility should not ask for either. Fifty lines of header parsing plus
/// <see cref="WebSocket.CreateFromStream"/> gets the same protocol with none of that.
/// </para>
/// </summary>
public static class WebSocketUpgrade
{
    public const string Path = "/airclip";
    private const string AcceptGuid = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private const int MaxHeaderBytes = 8 * 1024;

    /// <summary>
    /// Reads the request, answers it, and hands back a server-side <see cref="WebSocket"/>, or null when
    /// what arrived was not an AirClip upgrade — a port scan, a browser, or a peer on the wrong path.
    /// </summary>
    public static async Task<WebSocket?> TryAcceptAsync(
        Stream stream, TimeSpan keepAlive, CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(stream);
        string? request = await ReadHeadersAsync(stream, cancellationToken).ConfigureAwait(false);
        if (request is null)
        {
            return null;
        }

        string[] lines = request.Split("\r\n", StringSplitOptions.RemoveEmptyEntries);
        if (lines.Length == 0 || !TryReadTarget(lines[0], out string? target) || !IsAirClipPath(target!))
        {
            await RefuseAsync(stream, "404 Not Found", cancellationToken).ConfigureAwait(false);
            return null;
        }

        var headers = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        foreach (string line in lines.Skip(1))
        {
            int colon = line.IndexOf(':', StringComparison.Ordinal);
            if (colon > 0)
            {
                headers[line[..colon].Trim()] = line[(colon + 1)..].Trim();
            }
        }

        if (!headers.TryGetValue("Sec-WebSocket-Key", out string? key)
            || string.IsNullOrWhiteSpace(key)
            || !headers.TryGetValue("Upgrade", out string? upgrade)
            || !upgrade.Contains("websocket", StringComparison.OrdinalIgnoreCase))
        {
            await RefuseAsync(stream, "400 Bad Request", cancellationToken).ConfigureAwait(false);
            return null;
        }

        string response = string.Concat(
            "HTTP/1.1 101 Switching Protocols\r\n",
            "Upgrade: websocket\r\n",
            "Connection: Upgrade\r\n",
            $"Sec-WebSocket-Accept: {ComputeAccept(key)}\r\n\r\n");
        await stream.WriteAsync(Encoding.ASCII.GetBytes(response), cancellationToken).ConfigureAwait(false);
        await stream.FlushAsync(cancellationToken).ConfigureAwait(false);

        return WebSocket.CreateFromStream(
            stream,
            new WebSocketCreationOptions { IsServer = true, KeepAliveInterval = keepAlive });
    }

    /// <summary>The one line of RFC 6455 that has to be exactly right: SHA-1 of the key plus the fixed GUID.</summary>
    public static string ComputeAccept(string key) =>
        Convert.ToBase64String(SHA1.HashData(Encoding.ASCII.GetBytes(key.Trim() + AcceptGuid)));

    public static bool IsAirClipPath(string target) =>
        target.StartsWith(Path, StringComparison.OrdinalIgnoreCase);

    private static bool TryReadTarget(string requestLine, out string? target)
    {
        target = null;
        string[] parts = requestLine.Split(' ', StringSplitOptions.RemoveEmptyEntries);
        if (parts.Length < 2 || !string.Equals(parts[0], "GET", StringComparison.Ordinal))
        {
            return false;
        }

        target = parts[1];
        return true;
    }

    private static async Task RefuseAsync(Stream stream, string status, CancellationToken cancellationToken)
    {
        try
        {
            byte[] bytes = Encoding.ASCII.GetBytes($"HTTP/1.1 {status}\r\nConnection: close\r\n\r\n");
            await stream.WriteAsync(bytes, cancellationToken).ConfigureAwait(false);
            await stream.FlushAsync(cancellationToken).ConfigureAwait(false);
        }
        catch (IOException)
        {
            // The other end hung up first; there is nothing to tell it.
        }
    }

    /// <summary>
    /// Reads exactly up to the blank line and not one byte further, a byte at a time. Buffered reading
    /// would be faster and would also risk swallowing the first WebSocket frame into a buffer that
    /// <see cref="WebSocket.CreateFromStream"/> never sees.
    /// </summary>
    private static async Task<string?> ReadHeadersAsync(Stream stream, CancellationToken cancellationToken)
    {
        var buffer = new byte[MaxHeaderBytes];
        byte[] one = new byte[1];
        int length = 0;
        while (length < buffer.Length)
        {
            int read = await stream.ReadAsync(one, cancellationToken).ConfigureAwait(false);
            if (read == 0)
            {
                return null;
            }

            buffer[length++] = one[0];
            if (length >= 4
                && buffer[length - 4] == (byte)'\r' && buffer[length - 3] == (byte)'\n'
                && buffer[length - 2] == (byte)'\r' && buffer[length - 1] == (byte)'\n')
            {
                return Encoding.UTF8.GetString(buffer, 0, length - 4);
            }
        }

        return null;
    }
}
