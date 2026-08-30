using System.Net;
using System.Net.Sockets;
using System.Net.WebSockets;

namespace AirClip.Net;

/// <summary>
/// Accepts peer connections: plain TCP, upgraded by hand to WebSocket, one task per connection.
/// <para>
/// The port is open to the LAN, so it is worth being explicit about what protects it. Nothing here trusts
/// the caller: a connection that does not ask for <c>/airclip</c> gets an HTTP error and is dropped, and a
/// connection that does gets five seconds to prove it holds the group secret before it can send anything.
/// The handshake, not the listener, is the security boundary.
/// </para>
/// </summary>
public sealed class AirClipListener : IDisposable
{
    private static readonly TimeSpan KeepAlive = TimeSpan.FromSeconds(30);

    private readonly Func<WebSocket, IPEndPoint?, CancellationToken, Task> _onAccepted;
    private readonly TcpListener _listener;
    private CancellationTokenSource? _cts;
    private Task? _loop;
    private bool _disposed;

    public AirClipListener(
        int port, Func<WebSocket, IPEndPoint?, CancellationToken, Task> onAccepted, IPAddress? bind = null)
    {
        ArgumentNullException.ThrowIfNull(onAccepted);
        _onAccepted = onAccepted;
        _listener = new TcpListener(bind ?? IPAddress.Any, port);
    }

    /// <summary>The port actually bound, which differs from the requested one when 0 was asked for.</summary>
    public int Port => _listener.LocalEndpoint is IPEndPoint endpoint ? endpoint.Port : 0;

    public bool IsListening { get; private set; }

    public void Start(CancellationToken cancellationToken = default)
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        if (IsListening)
        {
            return;
        }

        _listener.Start();
        IsListening = true;
        _cts = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        _loop = AcceptLoopAsync(_cts.Token);
    }

    public async Task StopAsync()
    {
        if (!IsListening)
        {
            return;
        }

        IsListening = false;
        if (_cts is not null)
        {
            await _cts.CancelAsync().ConfigureAwait(false);
        }

        _listener.Stop();
        if (_loop is not null)
        {
            try
            {
                await _loop.ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
            }
        }

        _cts?.Dispose();
        _cts = null;
        _loop = null;
    }

    public void Dispose()
    {
        if (_disposed)
        {
            return;
        }

        _disposed = true;
        IsListening = false;
        _cts?.Cancel();
        _cts?.Dispose();
        _cts = null;
        _listener.Dispose();
    }

    private async Task AcceptLoopAsync(CancellationToken cancellationToken)
    {
        while (!cancellationToken.IsCancellationRequested)
        {
            TcpClient client;
            try
            {
                client = await _listener.AcceptTcpClientAsync(cancellationToken).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                return;
            }
            catch (Exception ex) when (ex is SocketException or ObjectDisposedException or InvalidOperationException)
            {
                return;
            }

            // One task per connection, and it owns the connection: the session runs to completion inside it,
            // so the socket is closed exactly once, by the code that knows the session is over.
            _ = ServeAsync(client, cancellationToken);
        }
    }

    private async Task ServeAsync(TcpClient client, CancellationToken cancellationToken)
    {
        try
        {
            client.NoDelay = true;
            NetworkStream stream = client.GetStream();
            WebSocket? socket = await WebSocketUpgrade
                .TryAcceptAsync(stream, KeepAlive, cancellationToken)
                .ConfigureAwait(false);
            if (socket is null)
            {
                return;
            }

            await _onAccepted(socket, client.Client.RemoteEndPoint as IPEndPoint, cancellationToken)
                .ConfigureAwait(false);
        }
        catch (Exception ex) when (ex is IOException or SocketException or WebSocketException
            or ObjectDisposedException or OperationCanceledException or PeerHandshakeException
            or InvalidDataException)
        {
            // A failed inbound connection is routine: port scans, a peer with the wrong pairing code, a
            // laptop closing its lid mid-handshake. None of it should disturb the accept loop.
        }
        finally
        {
            client.Dispose();
        }
    }
}
