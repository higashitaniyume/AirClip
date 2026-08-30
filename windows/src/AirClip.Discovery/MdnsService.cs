using System.Net;
using System.Net.Sockets;
using AirClip.Discovery.Dns;

namespace AirClip.Discovery;

/// <summary>
/// A minimal DNS-SD responder and browser sharing one multicast socket: it announces this device and
/// keeps a live list of the others.
/// <para>
/// Only what AirClip needs is implemented, and the omissions are deliberate rather than accidental. There
/// is no name-conflict probing (the instance label already carries part of the device id, so a collision
/// needs two devices with the same name <em>and</em> the same id suffix) and no known-answer suppression
/// (a handful of clipboard peers do not congest a network). Everything the protocol needs to be
/// well-behaved on a shared channel — cache-flush bits, TTL expiry, goodbye packets, backed-off queries —
/// is here.
/// </para>
/// </summary>
public sealed class MdnsService : IDisposable
{
    private static readonly TimeSpan ExpiryScanInterval = TimeSpan.FromSeconds(5);
    private static readonly TimeSpan MaxQueryInterval = TimeSpan.FromSeconds(60);

    private readonly object _gate = new();
    private readonly Dictionary<string, Entry> _entries = new(StringComparer.OrdinalIgnoreCase);
    private readonly MdnsSocket _socket = new();
    private readonly ServiceProfile _profile;
    private readonly TimeProvider _time;
    private CancellationTokenSource? _cts;
    private Task? _loops;

    public MdnsService(ServiceProfile profile, TimeProvider? time = null)
    {
        ArgumentNullException.ThrowIfNull(profile);
        _profile = profile;
        _time = time ?? TimeProvider.System;
        _socket.PacketReceived += OnPacket;
    }

    public event EventHandler<DiscoveredService>? ServiceFound;

    public event EventHandler<DiscoveredService>? ServiceLost;

    public ServiceProfile Profile => _profile;

    /// <summary>How many interfaces are actually carrying multicast. Zero means discovery is dead.</summary>
    public int InterfaceCount { get; private set; }

    public IReadOnlyList<DiscoveredService> Services
    {
        get
        {
            lock (_gate)
            {
                return _entries.Values.Select(e => e.ToService(_profile.ServiceType)).ToList();
            }
        }
    }

    /// <summary>Opens the sockets and starts announcing and browsing. Returns the live interface count.</summary>
    public Task<int> StartAsync(CancellationToken cancellationToken = default)
    {
        if (_cts is not null)
        {
            return Task.FromResult(InterfaceCount);
        }

        InterfaceCount = _socket.Open();
        _cts = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        CancellationToken token = _cts.Token;
        _loops = Task.WhenAll(AnnounceLoopAsync(token), QueryLoopAsync(token), ExpiryLoopAsync(token));
        return Task.FromResult(InterfaceCount);
    }

    /// <summary>
    /// Says goodbye before closing, which is the difference between peers dropping this device from their
    /// lists now and them waiting two minutes for a TTL to run out. Stopping is final: the sockets are
    /// closed, so a stopped instance is discarded rather than restarted.
    /// </summary>
    public async Task StopAsync()
    {
        if (_cts is null)
        {
            return;
        }

        try
        {
            await _socket.SendAsync(BuildAnnouncement(0)).ConfigureAwait(false);
        }
        catch (Exception ex) when (ex is SocketException or ObjectDisposedException)
        {
        }

        await _cts.CancelAsync().ConfigureAwait(false);
        if (_loops is not null)
        {
            try
            {
                await _loops.ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
            }
        }

        _socket.Dispose();
        _cts.Dispose();
        _cts = null;
        lock (_gate)
        {
            _entries.Clear();
        }
    }

    public Task AnnounceAsync(CancellationToken cancellationToken = default) =>
        _socket.SendAsync(BuildAnnouncement(_profile.Ttl), cancellationToken);

    /// <summary>Asks who else is out there: one PTR question for the service type, sent to the group.</summary>
    public Task QueryAsync(CancellationToken cancellationToken = default)
    {
        var message = new DnsMessage();
        message.Questions.Add(new DnsQuestion(_profile.ServiceType, DnsRecordType.Ptr));
        return _socket.SendAsync(message.ToArray(), cancellationToken);
    }

    public void Dispose()
    {
        _socket.PacketReceived -= OnPacket;
        _cts?.Cancel();
        _cts?.Dispose();
        _cts = null;
        _socket.Dispose();
    }

    /// <summary>
    /// Three announcements a second apart, then a refresh every half TTL. The opening burst is what makes
    /// pairing feel instant on a wireless network, where a single packet is genuinely likely to be dropped.
    /// </summary>
    private async Task AnnounceLoopAsync(CancellationToken token)
    {
        TimeSpan[] opening = [TimeSpan.Zero, TimeSpan.FromSeconds(1), TimeSpan.FromSeconds(2)];
        var refresh = TimeSpan.FromSeconds(Math.Max(5, _profile.Ttl / 2.0));
        try
        {
            foreach (TimeSpan wait in opening)
            {
                if (wait > TimeSpan.Zero)
                {
                    await Task.Delay(wait, _time, token).ConfigureAwait(false);
                }

                await AnnounceAsync(token).ConfigureAwait(false);
            }

            while (!token.IsCancellationRequested)
            {
                await Task.Delay(refresh, _time, token).ConfigureAwait(false);
                await AnnounceAsync(token).ConfigureAwait(false);
            }
        }
        catch (OperationCanceledException)
        {
        }
    }

    /// <summary>
    /// Queries with the doubling interval the spec asks for, capped at a minute. Uncapped doubling is
    /// correct for a printer that will still be there tomorrow; a phone that just joined the Wi-Fi should
    /// not have to wait a quarter of an hour to be noticed.
    /// </summary>
    private async Task QueryLoopAsync(CancellationToken token)
    {
        var interval = TimeSpan.FromSeconds(1);
        try
        {
            await QueryAsync(token).ConfigureAwait(false);
            while (!token.IsCancellationRequested)
            {
                await Task.Delay(interval, _time, token).ConfigureAwait(false);
                await QueryAsync(token).ConfigureAwait(false);
                interval = interval * 2 < MaxQueryInterval ? interval * 2 : MaxQueryInterval;
            }
        }
        catch (OperationCanceledException)
        {
        }
    }

    private async Task ExpiryLoopAsync(CancellationToken token)
    {
        try
        {
            while (!token.IsCancellationRequested)
            {
                await Task.Delay(ExpiryScanInterval, _time, token).ConfigureAwait(false);
                DateTimeOffset now = _time.GetUtcNow();
                List<DiscoveredService> lost = [];
                lock (_gate)
                {
                    foreach (Entry entry in _entries.Values.Where(e => e.ExpiresAt <= now).ToList())
                    {
                        _entries.Remove(entry.InstanceName);
                        lost.Add(entry.ToService(_profile.ServiceType));
                    }
                }

                foreach (DiscoveredService service in lost)
                {
                    ServiceLost?.Invoke(this, service);
                }
            }
        }
        catch (OperationCanceledException)
        {
        }
    }

    /// <summary>
    /// PTR in the answer section, the rest as additionals — the shape a DNS-SD client expects, and one
    /// packet rather than a query round trip. The PTR record is the only one without the cache-flush bit,
    /// because several devices legitimately share that name and flushing it would erase the others.
    /// </summary>
    private byte[] BuildAnnouncement(uint ttl)
    {
        const ushort flush = DnsRecord.ClassIn | DnsRecord.CacheFlushBit;
        var message = new DnsMessage { Flags = DnsMessage.AuthoritativeResponse };
        message.Answers.Add(new PtrRecord(_profile.ServiceType, DnsRecord.ClassIn, ttl, _profile.InstanceName));
        message.Additionals.Add(new SrvRecord(
            _profile.InstanceName, flush, ttl, 0, 0, (ushort)_profile.Port, _profile.HostName));
        message.Additionals.Add(new TxtRecord(_profile.InstanceName, flush, ttl, _profile.TxtEntries));

        IReadOnlyList<IPAddress> addresses = _socket.PublishableAddresses;
        foreach (IPAddress address in addresses.Count > 0 ? addresses : [IPAddress.Loopback])
        {
            message.Additionals.Add(new ARecord(_profile.HostName, flush, ttl, address));
        }

        return message.ToArray();
    }

    private void OnPacket(object? sender, MdnsPacket packet)
    {
        if (packet.Message.IsResponse)
        {
            Merge(packet);
            return;
        }

        _ = AnswerAsync(packet);
    }

    private async Task AnswerAsync(MdnsPacket packet)
    {
        bool wanted = false;
        bool unicast = false;
        foreach (DnsQuestion question in packet.Message.Questions)
        {
            bool match = (question.Type is DnsRecordType.Ptr or DnsRecordType.Any
                    && DnsName.Equal(question.Name, _profile.ServiceType))
                || (question.Type is DnsRecordType.Srv or DnsRecordType.Txt or DnsRecordType.Any
                    && DnsName.Equal(question.Name, _profile.InstanceName))
                || (question.Type is DnsRecordType.A or DnsRecordType.Any
                    && DnsName.Equal(question.Name, _profile.HostName));
            if (match)
            {
                wanted = true;
                unicast |= question.WantsUnicastResponse;
            }
        }

        if (!wanted)
        {
            return;
        }

        byte[] datagram = BuildAnnouncement(_profile.Ttl);
        try
        {
            if (unicast)
            {
                await _socket.SendAsync(datagram, packet.Remote, packet.LocalAddress).ConfigureAwait(false);
            }
            else
            {
                await _socket.SendAsync(datagram).ConfigureAwait(false);
            }
        }
        catch (Exception ex) when (ex is SocketException or ObjectDisposedException)
        {
        }
    }

    /// <summary>
    /// Folds one response into the table. Records for a service arrive together in the usual case but are
    /// allowed to arrive separately, so everything merges into whatever is already known and a peer is only
    /// reported once it has both a port and an address.
    /// </summary>
    private void Merge(MdnsPacket packet)
    {
        DateTimeOffset now = _time.GetUtcNow();
        var hosts = new Dictionary<string, IPAddress>(StringComparer.OrdinalIgnoreCase);
        foreach (ARecord record in packet.Message.AllRecords.OfType<ARecord>())
        {
            hosts[DnsName.Normalise(record.Name)] = record.Address;
        }

        List<DiscoveredService> found = [];
        List<DiscoveredService> lost = [];
        lock (_gate)
        {
            var touched = new List<Entry>();
            foreach (DnsRecord record in packet.Message.AllRecords)
            {
                switch (record)
                {
                    case PtrRecord ptr when DnsName.Equal(ptr.Name, _profile.ServiceType):
                        string key = DnsName.Normalise(ptr.Target);
                        if (ptr.IsGoodbye)
                        {
                            if (_entries.Remove(key, out Entry? departing) && departing.Announced)
                            {
                                lost.Add(departing.ToService(_profile.ServiceType));
                            }
                        }
                        else
                        {
                            touched.Add(Touch(key, ptr.Ttl, now));
                        }

                        break;

                    case SrvRecord srv when DnsName.IsInstanceOf(srv.Name, _profile.ServiceType):
                        Entry service = Touch(DnsName.Normalise(srv.Name), srv.Ttl, now);
                        string host = DnsName.Normalise(srv.Target);
                        service.Dirty |= service.Port != srv.Port || !DnsName.Equal(service.HostName, host);
                        service.Port = srv.Port;
                        service.HostName = host;
                        touched.Add(service);
                        break;

                    case TxtRecord txt when DnsName.IsInstanceOf(txt.Name, _profile.ServiceType):
                        Entry described = Touch(DnsName.Normalise(txt.Name), txt.Ttl, now);
                        described.Dirty |= !described.Txt.SequenceEqual(txt.Entries, StringComparer.Ordinal);
                        described.Txt = txt.Entries;
                        touched.Add(described);
                        break;
                }
            }

            foreach (Entry entry in touched)
            {
                // The A record if the peer sent one, otherwise the address the packet came from: in mDNS a
                // response is always sent by the device it describes, so the source address is that device.
                IPAddress? address = entry.HostName.Length > 0 && hosts.TryGetValue(entry.HostName, out IPAddress? a)
                    ? a
                    : entry.Address ?? packet.Remote.Address;
                entry.Dirty |= !address.Equals(entry.Address);
                entry.Address = address;

                if (IsSelf(entry))
                {
                    _entries.Remove(entry.InstanceName);
                    continue;
                }

                if (entry.IsDialable && (!entry.Announced || entry.Dirty))
                {
                    entry.Announced = true;
                    entry.Dirty = false;
                    found.Add(entry.ToService(_profile.ServiceType));
                }
            }
        }

        foreach (DiscoveredService service in lost)
        {
            ServiceLost?.Invoke(this, service);
        }

        foreach (DiscoveredService service in found)
        {
            ServiceFound?.Invoke(this, service);
        }
    }

    /// <summary>Our own announcement, heard back through multicast loopback. Not a peer.</summary>
    private bool IsSelf(Entry entry) =>
        DnsName.Equal(entry.InstanceName, _profile.InstanceName)
        || string.Equals(entry.DeviceId, _profile.DeviceId, StringComparison.OrdinalIgnoreCase);

    private Entry Touch(string instanceName, uint ttl, DateTimeOffset now)
    {
        if (!_entries.TryGetValue(instanceName, out Entry? entry))
        {
            entry = new Entry { InstanceName = instanceName };
            _entries[instanceName] = entry;
        }

        entry.LastSeen = now;

        // Floored at five seconds: a peer that advertises a one-second TTL would otherwise flicker in and
        // out of the list faster than a connection to it can be established.
        entry.ExpiresAt = now + TimeSpan.FromSeconds(Math.Max(ttl, 5));
        return entry;
    }

    private sealed class Entry
    {
        public required string InstanceName { get; init; }

        public string HostName { get; set; } = string.Empty;

        public int Port { get; set; }

        public IPAddress? Address { get; set; }

        public IReadOnlyList<string> Txt { get; set; } = [];

        public DateTimeOffset LastSeen { get; set; }

        public DateTimeOffset ExpiresAt { get; set; }

        /// <summary>Whether the owner has been told about this service, so it is not reported twice.</summary>
        public bool Announced { get; set; }

        public bool Dirty { get; set; }

        public bool IsDialable => Port is > 0 and <= 65535 && Address is not null;

        public string DeviceId
        {
            get
            {
                foreach (string entry in Txt)
                {
                    if (entry.StartsWith(ServiceProfile.TxtDeviceId + "=", StringComparison.OrdinalIgnoreCase))
                    {
                        return entry[(ServiceProfile.TxtDeviceId.Length + 1)..];
                    }
                }

                return string.Empty;
            }
        }

        public DiscoveredService ToService(string serviceType) => new()
        {
            InstanceName = InstanceName,
            ServiceType = serviceType,
            HostName = HostName,
            Port = Port,
            Address = Address,
            TxtEntries = Txt,
            LastSeen = LastSeen,
            ExpiresAt = ExpiresAt,
        };
    }
}
