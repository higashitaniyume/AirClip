using System.Net;
using System.Net.Sockets;
using System.Net.WebSockets;
using System.Text;
using AirClip.Net;
using Xunit;

namespace AirClip.Sync.Tests;

public class WebSocketUpgradeTests
{
    [Fact]
    public void The_rfc_6455_example_key_produces_the_documented_accept_value()
    {
        // Straight out of RFC 6455 §1.3. If this line is wrong, every client refuses the connection with a
        // message about the accept header and nothing else in the stack gets a chance to be wrong.
        Assert.Equal("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", WebSocketUpgrade.ComputeAccept("dGhlIHNhbXBsZSBub25jZQ=="));
        Assert.Equal(
            WebSocketUpgrade.ComputeAccept("dGhlIHNhbXBsZSBub25jZQ=="),
            WebSocketUpgrade.ComputeAccept("  dGhlIHNhbXBsZSBub25jZQ==  "));
    }

    [Theory]
    [InlineData("/airclip", true)]
    [InlineData("/AirClip", true)]
    [InlineData("/airclip?v=1", true)]
    [InlineData("/", false)]
    [InlineData("/index.html", false)]
    public void Only_the_airclip_path_is_ours(string target, bool expected)
    {
        Assert.Equal(expected, WebSocketUpgrade.IsAirClipPath(target));
    }

    [Fact]
    public async Task A_real_upgrade_request_is_answered_with_101_and_the_matching_accept()
    {
        const string key = "x3JJHMbDL1EzLkh9GBhXDw==";
        string[] response = await ExchangeAsync(
            "GET /airclip HTTP/1.1\r\n"
            + "Host: 127.0.0.1\r\n"
            + "Upgrade: websocket\r\n"
            + "Connection: Upgrade\r\n"
            + $"Sec-WebSocket-Key: {key}\r\n"
            + "Sec-WebSocket-Version: 13\r\n\r\n",
            expectSession: true);

        Assert.Equal("HTTP/1.1 101 Switching Protocols", response[0]);
        Assert.Contains($"Sec-WebSocket-Accept: {WebSocketUpgrade.ComputeAccept(key)}", response);
        Assert.Contains("Upgrade: websocket", response);
    }

    [Theory]
    [InlineData("GET /nope HTTP/1.1\r\nHost: x\r\n\r\n", "HTTP/1.1 404 Not Found")]
    [InlineData("POST /airclip HTTP/1.1\r\nHost: x\r\n\r\n", "HTTP/1.1 404 Not Found")]
    [InlineData("hello?\r\n\r\n", "HTTP/1.1 404 Not Found")]
    [InlineData("GET /airclip HTTP/1.1\r\nHost: x\r\n\r\n", "HTTP/1.1 400 Bad Request")]
    [InlineData("GET /airclip HTTP/1.1\r\nSec-WebSocket-Key: abc\r\n\r\n", "HTTP/1.1 400 Bad Request")]
    public async Task Anything_that_is_not_an_airclip_upgrade_is_turned_away(string request, string expected)
    {
        // The port is open to the LAN, so the well-behaved half of what arrives is browsers and port
        // scanners. They get an HTTP error and a closed socket, and the accept loop keeps going.
        string[] response = await ExchangeAsync(request);

        Assert.Equal(expected, response[0]);
    }

    [Fact]
    public async Task A_client_that_never_sends_a_blank_line_is_dropped_at_the_header_limit()
    {
        // Eight kilobytes and no further: without the cap, one socket sending headers forever would grow a
        // buffer forever, which is a denial of service that costs the sender nothing.
        string[] response = await ExchangeAsync(new string('a', 8 * 1024));

        Assert.Empty(response);
    }

    /// <summary>
    /// Sends one raw request to a real listener on loopback and returns the response's header lines. The
    /// listener is the production one, so what is being tested is the code that will face the network.
    /// </summary>
    private static async Task<string[]> ExchangeAsync(string request, bool expectSession = false)
    {
        var session = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        using var listener = new AirClipListener(
            0,
            (WebSocket _, IPEndPoint? _, CancellationToken _) =>
            {
                session.TrySetResult();
                return Task.CompletedTask;
            },
            IPAddress.Loopback);
        listener.Start();

        var lines = new List<string>();
        try
        {
            using var client = new TcpClient();
            await client.ConnectAsync(IPAddress.Loopback, listener.Port);
            NetworkStream stream = client.GetStream();
            await stream.WriteAsync(Encoding.ASCII.GetBytes(request));
            await stream.FlushAsync();

            using var reader = new StreamReader(stream, Encoding.ASCII);
            while (await reader.ReadLineAsync() is { } line && line.Length > 0)
            {
                lines.Add(line);
            }

            if (expectSession)
            {
                await session.Task.WaitAsync(TimeSpan.FromSeconds(10));
            }
            else
            {
                // The refusal paths never reach the callback at all, so this cannot race: a completed task
                // here would mean a rejected connection had been handed to the session layer anyway.
                Assert.False(session.Task.IsCompleted);
            }
        }
        finally
        {
            await listener.StopAsync();
        }

        return [.. lines];
    }
}
