using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using AirClip.Discovery.Dns;

namespace AirClip.Discovery;

/// <summary>One received datagram, with the interface it arrived on so a reply can go back the same way.</summary>
public sealed record MdnsPacket(DnsMessage Message, IPEndPoint Remote, IPAddress LocalAddress);

/// <summary>
/// The multicast plumbing: one socket per usable IPv4 interface, each bound to 5353 with address reuse.
/// <para>
/// Reuse is not optional on Windows — the built-in resolver already holds the port, and a responder that
/// insisted on exclusive use would simply never start on a normal desktop.
/// </para>
/// <para>
/// Loopback is opened alongside the real adapters. It costs one extra copy of each announcement, which the
/// browser folds together anyway, and it buys discovery that still works when a machine is between
/// networks or when a firewall rule is quietly dropping multicast on the adapter.
/// </para>
/// </summary>
public sealed class MdnsSocket : IDisposable
{
    public static readonly IPAddress MulticastAddress = IPAddress.Parse("224.0.0.251");
    public const int Port = 5353;

    private readonly List<(UdpClient Client, IPAddress Local)> _endpoints = [];
    private readonly List<Task> _receivers = [];
    private CancellationTokenSource? _cts;
    private bool _disposed;

    public event EventHandler<MdnsPacket>? PacketReceived;

    public IReadOnlyList<IPAddress> LocalAddresses => _endpoints.ConvertAll(e => e.Local);

    /// <summary>Addresses worth publishing in an A record: the real adapters, never loopback.</summary>
    public IReadOnlyList<IPAddress> PublishableAddresses =>
        _endpoints.Where(e => !IPAddress.IsLoopback(e.Local)).Select(e => e.Local).ToList();

    public bool IsOpen => _endpoints.Count > 0;

    /// <summary>
    /// Opens what can be opened and returns how many interfaces are live. A single interface failing is
    /// normal (adapters come and go, and some refuse the port), so it is logged by omission rather than
    /// thrown: discovery on three of four adapters is still discovery.
    /// </summary>
    public int Open()
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        if (_endpoints.Count > 0)
        {
            return _endpoints.Count;
        }

        _cts = new CancellationTokenSource();
        foreach (IPAddress address in EnumerateAddresses())
        {
            if (TryOpen(address, out UdpClient? client))
            {
                _endpoints.Add((client!, address));
            }
        }

        foreach ((UdpClient client, IPAddress local) in _endpoints)
        {
            _receivers.Add(ReceiveLoopAsync(client, local, _cts.Token));
        }

        return _endpoints.Count;
    }

    /// <summary>Sends to the group on every interface. Failures are per-interface and never fatal.</summary>
    public async Task SendAsync(byte[] datagram, CancellationToken cancellationToken = default)
    {
        var destination = new IPEndPoint(MulticastAddress, Port);
        foreach ((UdpClient client, _) in _endpoints)
        {
            try
            {
                await client.SendAsync(datagram, destination, cancellationToken).ConfigureAwait(false);
            }
            catch (SocketException)
            {
            }
            catch (ObjectDisposedException)
            {
                return;
            }
        }
    }

    /// <summary>Answers a question directly, used when the asker set the unicast-response bit.</summary>
    public async Task SendAsync(
        byte[] datagram, IPEndPoint destination, IPAddress via, CancellationToken cancellationToken = default)
    {
        foreach ((UdpClient client, IPAddress local) in _endpoints)
        {
            if (!local.Equals(via))
            {
                continue;
            }

            try
            {
                await client.SendAsync(datagram, destination, cancellationToken).ConfigureAwait(false);
            }
            catch (SocketException)
            {
            }
            catch (ObjectDisposedException)
            {
            }

            return;
        }
    }

    public void Dispose()
    {
        if (_disposed)
        {
            return;
        }

        _disposed = true;
        _cts?.Cancel();
        foreach ((UdpClient client, _) in _endpoints)
        {
            try
            {
                client.Dispose();
            }
            catch (SocketException)
            {
            }
        }

        _endpoints.Clear();
        _receivers.Clear();
        _cts?.Dispose();
        _cts = null;
    }

    private static IEnumerable<IPAddress> EnumerateAddresses()
    {
        var found = new List<IPAddress>();
        foreach (NetworkInterface nic in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (nic.OperationalStatus != OperationalStatus.Up
                || !nic.SupportsMulticast
                || nic.NetworkInterfaceType is NetworkInterfaceType.Loopback or NetworkInterfaceType.Tunnel)
            {
                continue;
            }

            IPInterfaceProperties properties;
            try
            {
                properties = nic.GetIPProperties();
            }
            catch (NetworkInformationException)
            {
                // Some virtual adapters refuse to describe themselves; they are not where peers live.
                continue;
            }

            foreach (UnicastIPAddressInformation info in properties.UnicastAddresses)
            {
                // Link-local 169.254.* means DHCP failed on that adapter, so nothing is reachable there.
                if (info.Address.AddressFamily == AddressFamily.InterNetwork
                    && !info.Address.ToString().StartsWith("169.254.", StringComparison.Ordinal))
                {
                    found.Add(info.Address);
                }
            }
        }

        found.Add(IPAddress.Loopback);
        return found;
    }

    private static bool TryOpen(IPAddress address, out UdpClient? client)
    {
        client = null;
        UdpClient? udp = null;
        try
        {
            udp = new UdpClient(AddressFamily.InterNetwork) { ExclusiveAddressUse = false };
            udp.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
            udp.Client.Bind(new IPEndPoint(address, Port));
            udp.JoinMulticastGroup(MulticastAddress, address);

            // On, deliberately: two AirClip instances on one machine — and the self-test is exactly that —
            // only find each other if the stack hands each of them a copy of what the other sent.
            udp.MulticastLoopback = true;
            udp.Client.SetSocketOption(SocketOptionLevel.IP, SocketOptionName.MulticastTimeToLive, 255);
            udp.Client.SetSocketOption(
                SocketOptionLevel.IP, SocketOptionName.MulticastInterface, address.GetAddressBytes());
            client = udp;
            return true;
        }
        catch (SocketException)
        {
            udp?.Dispose();
            return false;
        }
        catch (ObjectDisposedException)
        {
            return false;
        }
    }

    private async Task ReceiveLoopAsync(UdpClient client, IPAddress local, CancellationToken cancellationToken)
    {
        while (!cancellationToken.IsCancellationRequested)
        {
            UdpReceiveResult result;
            try
            {
                result = await client.ReceiveAsync(cancellationToken).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                return;
            }
            catch (ObjectDisposedException)
            {
                return;
            }
            catch (SocketException)
            {
                // A refused datagram or an adapter blinking does not end the loop.
                continue;
            }

            try
            {
                DnsMessage message = DnsMessage.Parse(result.Buffer);
                PacketReceived?.Invoke(this, new MdnsPacket(message, result.RemoteEndPoint, local));
            }
            catch (InvalidDataException)
            {
                // Multicast DNS is a shared channel; malformed packets are someone else's bug, not ours.
            }
        }
    }
}
