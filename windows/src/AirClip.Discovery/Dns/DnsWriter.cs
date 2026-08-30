using System.Buffers.Binary;
using System.Text;

namespace AirClip.Discovery.Dns;

/// <summary>
/// Builds a DNS wire message. Names are compressed the way every resolver expects: a suffix that has
/// already been written becomes a two-byte pointer to it, which is what keeps a service announcement
/// carrying four records inside one small datagram.
/// </summary>
public sealed class DnsWriter
{
    private const int MaxPointerOffset = 0x3FFF;
    private const byte PointerMarker = 0xC0;

    private readonly Dictionary<string, int> _suffixes = new(StringComparer.OrdinalIgnoreCase);
    private byte[] _buffer = new byte[512];
    private int _length;

    public int Position => _length;

    public void WriteByte(byte value)
    {
        Ensure(1);
        _buffer[_length++] = value;
    }

    public void WriteUInt16(ushort value)
    {
        Ensure(sizeof(ushort));
        BinaryPrimitives.WriteUInt16BigEndian(_buffer.AsSpan(_length), value);
        _length += sizeof(ushort);
    }

    public void WriteUInt32(uint value)
    {
        Ensure(sizeof(uint));
        BinaryPrimitives.WriteUInt32BigEndian(_buffer.AsSpan(_length), value);
        _length += sizeof(uint);
    }

    public void WriteBytes(ReadOnlySpan<byte> value)
    {
        Ensure(value.Length);
        value.CopyTo(_buffer.AsSpan(_length));
        _length += value.Length;
    }

    /// <summary>A length-prefixed string, as TXT records are built from. Longer than 255 bytes is dropped.</summary>
    public void WriteCharacterString(string value)
    {
        byte[] bytes = Encoding.UTF8.GetBytes(value);
        if (bytes.Length > byte.MaxValue)
        {
            return;
        }

        WriteByte((byte)bytes.Length);
        WriteBytes(bytes);
    }

    public void WriteName(string name)
    {
        string remaining = name.TrimEnd('.');
        while (remaining.Length > 0)
        {
            if (_suffixes.TryGetValue(remaining, out int offset))
            {
                WriteUInt16((ushort)(offset | (PointerMarker << 8)));
                return;
            }

            if (_length <= MaxPointerOffset)
            {
                _suffixes[remaining] = _length;
            }

            int dot = remaining.IndexOf('.', StringComparison.Ordinal);
            string label = dot < 0 ? remaining : remaining[..dot];
            remaining = dot < 0 ? string.Empty : remaining[(dot + 1)..];

            byte[] bytes = Encoding.UTF8.GetBytes(label);
            if (bytes.Length is 0 or > 63)
            {
                // A 64-byte label cannot be represented at all; emitting a truncated one would be worse
                // than emitting a name that stops here, which at least fails visibly at the far end.
                break;
            }

            WriteByte((byte)bytes.Length);
            WriteBytes(bytes);
        }

        WriteByte(0);
    }

    /// <summary>
    /// Reserves the two RDLENGTH bytes and hands back the spot, because a record's length is only known
    /// after its data has been written — and its data may contain compression pointers, so it cannot be
    /// built into a side buffer and measured there.
    /// </summary>
    public int ReserveLength()
    {
        int position = _length;
        WriteUInt16(0);
        return position;
    }

    public void PatchLength(int position) =>
        BinaryPrimitives.WriteUInt16BigEndian(_buffer.AsSpan(position), (ushort)(_length - position - 2));

    public byte[] ToArray() => _buffer.AsSpan(0, _length).ToArray();

    private void Ensure(int extra)
    {
        if (_length + extra <= _buffer.Length)
        {
            return;
        }

        int capacity = _buffer.Length;
        while (capacity < _length + extra)
        {
            capacity *= 2;
        }

        Array.Resize(ref _buffer, capacity);
    }
}
