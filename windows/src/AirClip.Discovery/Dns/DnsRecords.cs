using System.Net;

namespace AirClip.Discovery.Dns;

public enum DnsRecordType : ushort
{
    A = 1,
    Ptr = 12,
    Txt = 16,
    Aaaa = 28,
    Srv = 33,
    Any = 255,
}

/// <summary>
/// One resource record. The <c>class</c> field is kept whole rather than split, because mDNS overloads
/// its top bit as the cache-flush flag and a record that loses that bit on the way through would tell a
/// peer to add a name alongside the old one instead of replacing it.
/// </summary>
public abstract record DnsRecord(string Name, DnsRecordType Type, ushort Class, uint Ttl)
{
    public const ushort ClassIn = 0x0001;
    public const ushort CacheFlushBit = 0x8000;

    public ushort ClassCode => (ushort)(Class & 0x7FFF);

    public bool CacheFlush => (Class & CacheFlushBit) != 0;

    /// <summary>A TTL of zero is how DNS-SD says "this is going away", not "cache forever".</summary>
    public bool IsGoodbye => Ttl == 0;

    public abstract void WriteData(DnsWriter writer);
}

public sealed record PtrRecord(string Name, ushort Class, uint Ttl, string Target)
    : DnsRecord(Name, DnsRecordType.Ptr, Class, Ttl)
{
    public override void WriteData(DnsWriter writer) => writer.WriteName(Target);
}

public sealed record SrvRecord(
    string Name, ushort Class, uint Ttl, ushort Priority, ushort Weight, ushort Port, string Target)
    : DnsRecord(Name, DnsRecordType.Srv, Class, Ttl)
{
    public override void WriteData(DnsWriter writer)
    {
        writer.WriteUInt16(Priority);
        writer.WriteUInt16(Weight);
        writer.WriteUInt16(Port);
        writer.WriteName(Target);
    }
}

/// <summary>
/// DNS-SD metadata: a list of length-prefixed <c>key=value</c> strings, kept as the list they are on the
/// wire. Lookup scans it, because a service record holds five entries and a dictionary per record would
/// cost more than it saves.
/// </summary>
public sealed record TxtRecord(string Name, ushort Class, uint Ttl, IReadOnlyList<string> Entries)
    : DnsRecord(Name, DnsRecordType.Txt, Class, Ttl)
{
    public string? Lookup(string key)
    {
        foreach (string entry in Entries)
        {
            int split = entry.IndexOf('=', StringComparison.Ordinal);
            if (split > 0 && entry.AsSpan(0, split).Equals(key, StringComparison.OrdinalIgnoreCase))
            {
                return entry[(split + 1)..];
            }
        }

        return null;
    }

    public override void WriteData(DnsWriter writer)
    {
        foreach (string entry in Entries)
        {
            writer.WriteCharacterString(entry);
        }

        if (Entries.Count == 0)
        {
            // An empty TXT is a single zero-length string, never zero bytes: some resolvers reject the latter.
            writer.WriteCharacterString(string.Empty);
        }
    }
}

public sealed record ARecord(string Name, ushort Class, uint Ttl, IPAddress Address)
    : DnsRecord(Name, DnsRecordType.A, Class, Ttl)
{
    public override void WriteData(DnsWriter writer) => writer.WriteBytes(Address.GetAddressBytes());
}

/// <summary>Anything we do not model. Kept verbatim so a packet can be re-emitted without loss.</summary>
public sealed record UnknownRecord(string Name, DnsRecordType Type, ushort Class, uint Ttl, byte[] Data)
    : DnsRecord(Name, Type, Class, Ttl)
{
    public override void WriteData(DnsWriter writer) => writer.WriteBytes(Data);
}

/// <summary>
/// A question. The top bit of the class is mDNS's "answer me directly" flag; AirClip leaves it clear so
/// answers go to the group and every device gets to update its cache from one exchange.
/// </summary>
public sealed record DnsQuestion(string Name, DnsRecordType Type, ushort Class = DnsRecord.ClassIn)
{
    public bool WantsUnicastResponse => (Class & DnsRecord.CacheFlushBit) != 0;

    public ushort ClassCode => (ushort)(Class & 0x7FFF);
}
