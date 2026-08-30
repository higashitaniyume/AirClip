using System.Net;
using System.Net.WebSockets;
using System.Text;
using AirClip.Core.Protocol;
using AirClip.Crypto;
using AirClip.Net;

namespace AirClip.Sync.Tests;

/// <summary>
/// Two <see cref="PeerSession"/> ends of one real TCP connection on loopback, already through the
/// handshake and already pumping. Nothing here is a stub: the listener, the HTTP upgrade and the crypto are
/// the ones that ship, which is the only way a test can catch the ordering mistakes that matter.
/// </summary>
internal sealed class Loopback : IAsyncDisposable
{
    /// <summary>Ids are fixed rather than random so which side dials is never a coin toss in a test.</summary>
    public static readonly DeviceIdentity ServerIdentity = new("aaaa1111-server", "办公室台式机");

    public static readonly DeviceIdentity ClientIdentity = new("bbbb2222-client", "笔记本");

    private static readonly TimeSpan Patience = TimeSpan.FromSeconds(10);

    private readonly AirClipListener _listener;
    private readonly ClientWebSocket _socket;
    private readonly TaskCompletionSource _hold;
    private readonly CancellationTokenSource _cts = new();
    private readonly Task _clientLoop;
    private readonly Task _serverLoop;

    private Loopback(
        AirClipListener listener,
        ClientWebSocket socket,
        TaskCompletionSource hold,
        PeerSession client,
        PeerSession server)
    {
        _listener = listener;
        _socket = socket;
        _hold = hold;
        Client = client;
        Server = server;
        _clientLoop = client.RunAsync(_cts.Token);
        _serverLoop = server.RunAsync(_cts.Token);
    }

    public PeerSession Client { get; }

    public PeerSession Server { get; }

    public static Uri EndpointFor(int port) => new($"ws://127.0.0.1:{port}{WebSocketUpgrade.Path}");

    public static async Task<Loopback> ConnectAsync(PairingKey serverKey, PairingKey? clientKey = null)
    {
        var ready = new TaskCompletionSource<PeerSession>(TaskCreationOptions.RunContinuationsAsynchronously);
        var hold = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        AirClipListener listener = StartServer(serverKey, ready, hold);
        var socket = new ClientWebSocket();
        try
        {
            await socket.ConnectAsync(EndpointFor(listener.Port), CancellationToken.None);
            PeerSession client = await PeerSession
                .ConnectAsync(socket, clientKey ?? serverKey, ClientIdentity, "windows", null)
                .WaitAsync(Patience);
            PeerSession server = await ready.Task.WaitAsync(Patience);
            return new Loopback(listener, socket, hold, client, server);
        }
        catch
        {
            // The server side fails at the same moment and for the same reason; observing it here keeps a
            // refused handshake from surfacing later as an unobserved task exception in an unrelated test.
            _ = ready.Task.ContinueWith(static task => task.Exception, TaskScheduler.Default);
            hold.TrySetResult();
            socket.Dispose();
            await listener.StopAsync();
            listener.Dispose();
            throw;
        }
    }

    /// <summary>
    /// Starts the production listener on an ephemeral loopback port. <paramref name="hold"/> is what keeps
    /// the accepted connection alive: <see cref="AirClipListener"/> owns the socket and closes it the moment
    /// the callback returns, which is correct in production and inconvenient in a test.
    /// </summary>
    public static AirClipListener StartServer(
        PairingKey key,
        TaskCompletionSource<PeerSession> ready,
        TaskCompletionSource hold,
        DeviceIdentity? identity = null)
    {
        var listener = new AirClipListener(
            0,
            async (WebSocket socket, IPEndPoint? remote, CancellationToken token) =>
            {
                PeerSession session;
                try
                {
                    session = await PeerSession.AcceptAsync(
                        socket, key, identity ?? ServerIdentity, "windows", remote, cancellationToken: token);
                }
                catch (Exception ex)
                {
                    ready.TrySetException(ex);
                    return;
                }

                ready.TrySetResult(session);
                await hold.Task;
            },
            IPAddress.Loopback);
        listener.Start();
        return listener;
    }

    /// <summary>
    /// Sends a frame straight down the client's socket, bypassing <see cref="PeerSession.SendAsync"/> and
    /// therefore the encryption. This is how a hostile peer on the LAN would behave, and the only way to
    /// test that the receiver refuses it.
    /// </summary>
    public Task SendRawTextAsync(string json) => _socket.SendAsync(
        Encoding.UTF8.GetBytes(json), WebSocketMessageType.Text, endOfMessage: true, CancellationToken.None);

    public Task SendRawBinaryAsync(byte[] frame) => _socket.SendAsync(
        frame, WebSocketMessageType.Binary, endOfMessage: true, CancellationToken.None);

    public async ValueTask DisposeAsync()
    {
        _hold.TrySetResult();
        await _cts.CancelAsync();
        try
        {
            await Task.WhenAll(_clientLoop, _serverLoop).WaitAsync(Patience);
        }
        catch (Exception)
        {
            // Teardown never masks the assertion that already ran: a session that refuses to wind down is
            // the next test's problem, not this one's verdict.
        }

        await _listener.StopAsync();
        _listener.Dispose();
        Client.Dispose();
        Server.Dispose();
        _cts.Dispose();
    }
}
