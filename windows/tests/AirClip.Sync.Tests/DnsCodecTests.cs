using System.Net;
using AirClip.Discovery.Dns;
using Xunit;

namespace AirClip.Sync.Tests;

public class DnsCodecTests
{
    private const string ServiceType = "_airclip._tcp.local.";
    private const string Instance = "办公室台式机-1a2b._airclip._tcp.local.";
    private const string Host = "airclip-1a2b3c4d.local.";

    [Fact]
    public void A_full_announcement_round_trips_field_for_field()
    {
        var message = new DnsMessage { Flags = DnsMessage.AuthoritativeResponse };
        message.Answers.Add(new PtrRecord(ServiceType, DnsRecord.ClassIn, 120, Instance));
        message.Additionals.Add(new SrvRecord(
            Instance, DnsRecord.ClassIn | DnsRecord.CacheFlushBit, 120, 0, 0, 47653, Host));
        message.Additionals.Add(new TxtRecord(
            Instance, DnsRecord.ClassIn | DnsRecord.CacheFlushBit, 120, ["id=abc", "name=办公室台式机"]));
        message.Additionals.Add(new ARecord(
            Host, DnsRecord.ClassIn | DnsRecord.CacheFlushBit, 120, IPAddress.Parse("192.168.1.9")));

        DnsMessage parsed = DnsMessage.Parse(message.ToArray());

        Assert.True(parsed.IsResponse);
        var ptr = Assert.IsType<PtrRecord>(Assert.Single(parsed.Answers));
        Assert.True(DnsName.Equal(ServiceType, ptr.Name));
        Assert.True(DnsName.Equal(Instance, ptr.Target));

        // A shared PTR must not carry cache-flush: it would tell the peer to forget every other device
        // announcing the same service type, which on a three-device LAN means losing two of them.
        Assert.False(ptr.CacheFlush);
        Assert.Equal(120u, ptr.Ttl);

        var srv = Assert.IsType<SrvRecord>(parsed.Additionals[0]);
        Assert.Equal(47653, srv.Port);
        Assert.True(DnsName.Equal(Host, srv.Target));
        Assert.True(srv.CacheFlush);
        Assert.Equal(DnsRecord.ClassIn, srv.ClassCode);

        var txt = Assert.IsType<TxtRecord>(parsed.Additionals[1]);
        Assert.Equal("abc", txt.Lookup("id"));
        Assert.Equal("办公室台式机", txt.Lookup("name"));

        var a = Assert.IsType<ARecord>(parsed.Additionals[2]);
        Assert.Equal(IPAddress.Parse("192.168.1.9"), a.Address);
    }

    [Fact]
    public void A_question_round_trips_and_stays_multicast()
    {
        var message = new DnsMessage();
        message.Questions.Add(new DnsQuestion(ServiceType, DnsRecordType.Ptr));

        DnsMessage parsed = DnsMessage.Parse(message.ToArray());

        DnsQuestion question = Assert.Single(parsed.Questions);
        Assert.True(DnsName.Equal(ServiceType, question.Name));
        Assert.Equal(DnsRecordType.Ptr, question.Type);
        Assert.False(parsed.IsResponse);
        Assert.False(question.WantsUnicastResponse);
    }

    [Fact]
    public void A_repeated_name_costs_two_bytes_instead_of_twenty_two()
    {
        var one = new DnsMessage();
        one.Questions.Add(new DnsQuestion(ServiceType, DnsRecordType.Ptr));
        var two = new DnsMessage();
        two.Questions.Add(new DnsQuestion(ServiceType, DnsRecordType.Ptr));
        two.Questions.Add(new DnsQuestion(ServiceType, DnsRecordType.Any));

        // Two bytes of pointer plus type and class: the name itself is written once. Without compression
        // the second question would add twenty-six bytes, and a four-record announcement stops fitting.
        Assert.Equal(6, two.ToArray().Length - one.ToArray().Length);
        Assert.Equal(2, DnsMessage.Parse(two.ToArray()).Questions.Count);
    }

    [Fact]
    public void Names_shared_between_records_survive_being_pointers()
    {
        var message = new DnsMessage { Flags = DnsMessage.AuthoritativeResponse };
        message.Answers.Add(new PtrRecord(ServiceType, DnsRecord.ClassIn, 120, Instance));
        message.Answers.Add(new SrvRecord(Instance, DnsRecord.ClassIn, 120, 0, 0, 1234, Host));
        message.Answers.Add(new PtrRecord(ServiceType, DnsRecord.ClassIn, 120, Instance));

        DnsMessage parsed = DnsMessage.Parse(message.ToArray());

        Assert.Equal(3, parsed.Answers.Count);
        Assert.All(parsed.Answers, record => Assert.NotEmpty(record.Name));
        Assert.True(DnsName.Equal(Instance, ((PtrRecord)parsed.Answers[2]).Target));
        Assert.True(DnsName.Equal(Host, ((SrvRecord)parsed.Answers[1]).Target));
    }

    [Fact]
    public void A_goodbye_is_a_ttl_of_zero_and_reads_back_as_one()
    {
        var message = new DnsMessage { Flags = DnsMessage.AuthoritativeResponse };
        message.Answers.Add(new PtrRecord(ServiceType, DnsRecord.ClassIn, 0, Instance));

        DnsRecord parsed = Assert.Single(DnsMessage.Parse(message.ToArray()).Answers);

        Assert.True(parsed.IsGoodbye);
    }

    [Fact]
    public void A_record_type_we_do_not_model_is_kept_verbatim_and_does_not_desync_the_rest()
    {
        byte[] address = IPAddress.Parse("fe80::1").GetAddressBytes();
        var message = new DnsMessage { Flags = DnsMessage.AuthoritativeResponse };
        message.Answers.Add(new UnknownRecord(Host, DnsRecordType.Aaaa, DnsRecord.ClassIn, 120, address));
        message.Answers.Add(new PtrRecord(ServiceType, DnsRecord.ClassIn, 120, Instance));

        DnsMessage parsed = DnsMessage.Parse(message.ToArray());

        var unknown = Assert.IsType<UnknownRecord>(parsed.Answers[0]);
        Assert.Equal(address, unknown.Data);
        Assert.True(DnsName.Equal(Instance, ((PtrRecord)parsed.Answers[1]).Target));
    }

    [Fact]
    public void A_record_shorter_than_its_type_implies_does_not_shift_the_next_one()
    {
        // The reason parsing always jumps to dataStart + rdlength: this TXT declares no data at all, and a
        // parser that trusted the record type instead of the length would read the next record as garbage.
        var writer = new DnsWriter();
        WriteHeader(writer, answers: 2);
        writer.WriteName("weird.local");
        writer.WriteUInt16((ushort)DnsRecordType.Txt);
        writer.WriteUInt16(DnsRecord.ClassIn);
        writer.WriteUInt32(120);
        writer.WriteUInt16(0);
        writer.WriteName(Host);
        writer.WriteUInt16((ushort)DnsRecordType.A);
        writer.WriteUInt16(DnsRecord.ClassIn);
        writer.WriteUInt32(120);
        writer.WriteUInt16(4);
        writer.WriteBytes(IPAddress.Parse("10.0.0.7").GetAddressBytes());

        DnsMessage parsed = DnsMessage.Parse(writer.ToArray());

        Assert.Empty(Assert.IsType<TxtRecord>(parsed.Answers[0]).Entries);
        Assert.Equal(IPAddress.Parse("10.0.0.7"), Assert.IsType<ARecord>(parsed.Answers[1]).Address);
    }

    [Fact]
    public void A_pointer_that_points_at_itself_is_rejected_rather_than_looping_forever()
    {
        // Anyone on the LAN can send this. If it were an infinite loop, it would be a one-packet denial
        // of service against every AirClip on the network.
        var writer = new DnsWriter();
        WriteHeader(writer, questions: 1);
        writer.WriteUInt16(0xC00C);
        writer.WriteUInt16((ushort)DnsRecordType.Ptr);
        writer.WriteUInt16(DnsRecord.ClassIn);

        Assert.Throws<InvalidDataException>(() => { DnsMessage.Parse(writer.ToArray()); });
    }

    [Fact]
    public void A_pointer_past_the_end_of_the_datagram_is_rejected()
    {
        var writer = new DnsWriter();
        WriteHeader(writer, questions: 1);
        writer.WriteUInt16(0xC0FF);
        writer.WriteUInt16((ushort)DnsRecordType.Ptr);
        writer.WriteUInt16(DnsRecord.ClassIn);

        Assert.Throws<InvalidDataException>(() => { DnsMessage.Parse(writer.ToArray()); });
    }

    [Fact]
    public void A_record_claiming_more_data_than_the_datagram_holds_is_rejected()
    {
        var writer = new DnsWriter();
        WriteHeader(writer, answers: 1);
        writer.WriteName(Host);
        writer.WriteUInt16((ushort)DnsRecordType.Txt);
        writer.WriteUInt16(DnsRecord.ClassIn);
        writer.WriteUInt32(120);
        writer.WriteUInt16(4096);

        Assert.Throws<InvalidDataException>(() => { DnsMessage.Parse(writer.ToArray()); });
    }

    [Theory]
    [InlineData(0)]
    [InlineData(4)]
    [InlineData(11)]
    public void A_truncated_datagram_is_rejected(int length)
    {
        var message = new DnsMessage { Flags = DnsMessage.AuthoritativeResponse };
        message.Answers.Add(new PtrRecord(ServiceType, DnsRecord.ClassIn, 120, Instance));
        byte[] full = message.ToArray();

        Assert.Throws<InvalidDataException>(() => { DnsMessage.Parse(full.AsMemory(0, length)); });
    }

    [Fact]
    public void A_header_that_promises_records_it_does_not_carry_is_rejected()
    {
        var writer = new DnsWriter();
        WriteHeader(writer, answers: 3);

        Assert.Throws<InvalidDataException>(() => { DnsMessage.Parse(writer.ToArray()); });
    }

    private static void WriteHeader(DnsWriter writer, int questions = 0, int answers = 0)
    {
        writer.WriteUInt16(0);
        writer.WriteUInt16(DnsMessage.AuthoritativeResponse);
        writer.WriteUInt16((ushort)questions);
        writer.WriteUInt16((ushort)answers);
        writer.WriteUInt16(0);
        writer.WriteUInt16(0);
    }
}
