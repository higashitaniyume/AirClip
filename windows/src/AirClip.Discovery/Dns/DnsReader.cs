using System.Buffers.Binary;
using System.Text;

namespace AirClip.Discovery.Dns;

/// <summary>
/// Reads a DNS wire message, including compressed names. Every read is bounds-checked and every failure
/// is an <see cref="InvalidDataException"/>: this parser is fed unauthenticated multicast from anything on
/// the LAN, so a malformed packet has to be a caught exception rather than an index out of range.
/// </summary>
public sealed class DnsReader
{
    private const int MaxPointerHops = 32;
    private const byte PointerMask = 0xC0;

    private readonly ReadOnlyMemory<byte> _buffer;

    public DnsReader(ReadOnlyMemory<byte> buffer) => _buffer = buffer;

    public int Position { get; set; }

    public int Remaining => _buffer.Length - Position;

    public byte ReadByte()
    {
        if (Remaining < 1)
        {
            throw new InvalidDataException("报文提前结束");
        }

        return _buffer.Span[Position++];
    }

    public ushort ReadUInt16()
    {
        if (Remaining < sizeof(ushort))
        {
            throw new InvalidDataException("报文提前结束");
        }

        ushort value = BinaryPrimitives.ReadUInt16BigEndian(_buffer.Span[Position..]);
        Position += sizeof(ushort);
        return value;
    }

    public uint ReadUInt32()
    {
        if (Remaining < sizeof(uint))
        {
            throw new InvalidDataException("报文提前结束");
        }

        uint value = BinaryPrimitives.ReadUInt32BigEndian(_buffer.Span[Position..]);
        Position += sizeof(uint);
        return value;
    }

    public byte[] ReadBytes(int count)
    {
        if (count < 0 || Remaining < count)
        {
            throw new InvalidDataException("报文提前结束");
        }

        byte[] value = _buffer.Span.Slice(Position, count).ToArray();
        Position += count;
        return value;
    }

    public string ReadCharacterString()
    {
        byte length = ReadByte();
        return Encoding.UTF8.GetString(ReadBytes(length));
    }

    /// <summary>
    /// Reads a possibly-compressed name and leaves the cursor just past the name as it appears <em>here</em>,
    /// not past wherever the pointers led. Hops are capped, because a packet whose pointers form a cycle is
    /// otherwise an infinite loop in a listener that anyone on the network can start.
    /// </summary>
    public string ReadName()
    {
        var builder = new StringBuilder();
        ReadOnlySpan<byte> span = _buffer.Span;
        int cursor = Position;
        int hops = 0;
        bool jumped = false;

        while (true)
        {
            if (cursor >= span.Length)
            {
                throw new InvalidDataException("名称越界");
            }

            byte length = span[cursor++];
            if (length == 0)
            {
                break;
            }

            if ((length & PointerMask) == PointerMask)
            {
                if (cursor >= span.Length)
                {
                    throw new InvalidDataException("压缩指针不完整");
                }

                int target = ((length & 0x3F) << 8) | span[cursor++];
                if (!jumped)
                {
                    Position = cursor;
                    jumped = true;
                }

                if (++hops > MaxPointerHops || target >= span.Length)
                {
                    throw new InvalidDataException("压缩指针无效或成环");
                }

                cursor = target;
                continue;
            }

            if ((length & PointerMask) != 0)
            {
                throw new InvalidDataException("未知的标签类型");
            }

            if (cursor + length > span.Length)
            {
                throw new InvalidDataException("标签越界");
            }

            if (builder.Length > 0)
            {
                builder.Append('.');
            }

            builder.Append(Encoding.UTF8.GetString(span.Slice(cursor, length)));
            cursor += length;
        }

        if (!jumped)
        {
            Position = cursor;
        }

        return builder.ToString();
    }
}
