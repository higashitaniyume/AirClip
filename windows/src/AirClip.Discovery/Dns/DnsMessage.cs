using System.Net;

namespace AirClip.Discovery.Dns;

/// <summary>
/// A DNS message, only as complete as multicast DNS needs: header, questions and the three record
/// sections. Parsing always advances past a record by its declared length, so one record this code does
/// not model — or does not model correctly — cannot desynchronise the rest of the packet.
/// </summary>
public sealed class DnsMessage
{
    /// <summary>QR = response, AA = authoritative, which is what every mDNS answer must set.</summary>
    public const ushort AuthoritativeResponse = 0x8400;

    public ushort Id { get; init; }

    public ushort Flags { get; init; }

    public bool IsResponse => (Flags & 0x8000) != 0;

    public List<DnsQuestion> Questions { get; } = [];

    public List<DnsRecord> Answers { get; } = [];

    public List<DnsRecord> Authorities { get; } = [];

    public List<DnsRecord> Additionals { get; } = [];

    /// <summary>Everything a responder should look at: answers plus the extra records that came along.</summary>
    public IEnumerable<DnsRecord> AllRecords => Answers.Concat(Authorities).Concat(Additionals);

    public static DnsMessage Parse(ReadOnlyMemory<byte> datagram)
    {
        var reader = new DnsReader(datagram);
        ushort id = reader.ReadUInt16();
        ushort flags = reader.ReadUInt16();
        int questions = reader.ReadUInt16();
        int answers = reader.ReadUInt16();
        int authorities = reader.ReadUInt16();
        int additionals = reader.ReadUInt16();

        var message = new DnsMessage { Id = id, Flags = flags };
        for (int i = 0; i < questions; i++)
        {
            string name = reader.ReadName();
            var type = (DnsRecordType)reader.ReadUInt16();
            ushort klass = reader.ReadUInt16();
            message.Questions.Add(new DnsQuestion(name, type, klass));
        }

        ReadRecords(reader, answers, message.Answers);
        ReadRecords(reader, authorities, message.Authorities);
        ReadRecords(reader, additionals, message.Additionals);
        return message;
    }

    public byte[] ToArray()
    {
        var writer = new DnsWriter();
        writer.WriteUInt16(Id);
        writer.WriteUInt16(Flags);
        writer.WriteUInt16((ushort)Questions.Count);
        writer.WriteUInt16((ushort)Answers.Count);
        writer.WriteUInt16((ushort)Authorities.Count);
        writer.WriteUInt16((ushort)Additionals.Count);

        foreach (DnsQuestion question in Questions)
        {
            writer.WriteName(question.Name);
            writer.WriteUInt16((ushort)question.Type);
            writer.WriteUInt16(question.Class);
        }

        WriteRecords(writer, Answers);
        WriteRecords(writer, Authorities);
        WriteRecords(writer, Additionals);
        return writer.ToArray();
    }

    private static void WriteRecords(DnsWriter writer, List<DnsRecord> records)
    {
        foreach (DnsRecord record in records)
        {
            writer.WriteName(record.Name);
            writer.WriteUInt16((ushort)record.Type);
            writer.WriteUInt16(record.Class);
            writer.WriteUInt32(record.Ttl);
            int lengthAt = writer.ReserveLength();
            record.WriteData(writer);
            writer.PatchLength(lengthAt);
        }
    }

    private static void ReadRecords(DnsReader reader, int count, List<DnsRecord> into)
    {
        for (int i = 0; i < count; i++)
        {
            string name = reader.ReadName();
            var type = (DnsRecordType)reader.ReadUInt16();
            ushort klass = reader.ReadUInt16();
            uint ttl = reader.ReadUInt32();
            int length = reader.ReadUInt16();
            if (length > reader.Remaining)
            {
                throw new InvalidDataException("记录长度超出报文");
            }

            int end = reader.Position + length;
            into.Add(ReadRecordData(reader, name, type, klass, ttl, length, end));

            // Unconditional, even on the paths that consumed exactly the right number of bytes: it is the
            // one line that keeps a surprising record from shifting every record after it.
            reader.Position = end;
        }
    }

    private static DnsRecord ReadRecordData(
        DnsReader reader, string name, DnsRecordType type, ushort klass, uint ttl, int length, int end)
    {
        switch (type)
        {
            case DnsRecordType.Ptr:
                return new PtrRecord(name, klass, ttl, reader.ReadName());
            case DnsRecordType.Srv:
                ushort priority = reader.ReadUInt16();
                ushort weight = reader.ReadUInt16();
                ushort port = reader.ReadUInt16();
                return new SrvRecord(name, klass, ttl, priority, weight, port, reader.ReadName());
            case DnsRecordType.Txt:
                var entries = new List<string>();
                while (reader.Position < end)
                {
                    entries.Add(reader.ReadCharacterString());
                }

                return new TxtRecord(name, klass, ttl, entries);
            case DnsRecordType.A when length == 4:
                return new ARecord(name, klass, ttl, new IPAddress(reader.ReadBytes(4)));
            default:
                return new UnknownRecord(name, type, klass, ttl, reader.ReadBytes(length));
        }
    }
}
