using System.Security.Cryptography;
using System.Text;

namespace AirClip.Crypto;

/// <summary>
/// The pre-shared secret shared by every device in one AirClip group. Twenty random bytes, shown to the
/// user as eight four-character groups, because a code that has to be read off a phone screen and typed
/// on a laptop is a user interface, not just a byte array: the alphabet is Crockford Base32, which drops
/// I, L, O and U so that nothing in the code can be confused with 1, 0 or a swear word.
/// <para>
/// The AES-256 master key is derived from those bytes rather than being them, so the length of the code
/// the user types and the length of the key the cipher wants stay independent of each other.
/// </para>
/// </summary>
public sealed class PairingKey
{
    /// <summary>Entropy the user actually handles: 160 bits, far past brute-force, still typeable.</summary>
    public const int SecretSizeBytes = 20;

    public const int MasterKeySizeBytes = 32;

    private const string Alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    private const int GroupSize = 4;

    private static readonly byte[] MasterInfo = Encoding.UTF8.GetBytes("airclip-master-key-v1");
    private static readonly byte[] FingerprintInfo = Encoding.UTF8.GetBytes("airclip-fingerprint-v1");
    private static readonly byte[] Salt = Encoding.UTF8.GetBytes("airclip-pairing-v1");

    private readonly byte[] _secret;
    private readonly byte[] _masterKey;

    private PairingKey(byte[] secret)
    {
        _secret = secret;
        _masterKey = HKDF.DeriveKey(HashAlgorithmName.SHA256, secret, MasterKeySizeBytes, Salt, MasterInfo);

        byte[] digest = HKDF.DeriveKey(HashAlgorithmName.SHA256, secret, 4, Salt, FingerprintInfo);
        Fingerprint = Convert.ToHexString(digest);
    }

    /// <summary>
    /// Four bytes of a derived hash, shown in the UI and published in the mDNS TXT record so two devices
    /// can be compared at a glance and a mismatched group is diagnosed before anyone reads a log. It is
    /// derived, never the secret itself, and 32 bits of it says nothing usable about the other 160.
    /// </summary>
    public string Fingerprint { get; }

    /// <summary>The user-facing code, in groups: <c>A1B2-C3D4-…</c>.</summary>
    public string Code => Format(ToBase32(_secret));

    public static PairingKey Create() => new(RandomNumberGenerator.GetBytes(SecretSizeBytes));

    public static PairingKey FromSecret(ReadOnlySpan<byte> secret)
    {
        if (secret.Length != SecretSizeBytes)
        {
            throw new ArgumentException($"配对密钥必须是 {SecretSizeBytes} 字节", nameof(secret));
        }

        return new PairingKey(secret.ToArray());
    }

    /// <summary>
    /// Accepts what a user is likely to hand over: the grouped code, the same code without separators or
    /// in lower case, or a whole <c>airclip://pair?…</c> invite pasted from a QR scan.
    /// </summary>
    public static bool TryParse(string? text, out PairingKey? key)
    {
        key = null;
        if (string.IsNullOrWhiteSpace(text))
        {
            return false;
        }

        string candidate = text.Trim();
        if (candidate.Contains("://", StringComparison.Ordinal))
        {
            if (!PairingInvite.TryParse(candidate, out PairingInvite? invite))
            {
                return false;
            }

            key = invite!.Key;
            return true;
        }

        if (!TryFromBase32(candidate, out byte[]? secret))
        {
            return false;
        }

        key = new PairingKey(secret!);
        return true;
    }

    public static PairingKey Parse(string text) =>
        TryParse(text, out PairingKey? key) ? key! : throw new FormatException("配对码格式不正确");

    /// <summary>Copies the secret out for at-rest protection. The caller owns clearing what it gets.</summary>
    public byte[] ExportSecret() => (byte[])_secret.Clone();

    /// <summary>
    /// Session keys are always derived, never the master key itself: a fresh key per connection and per
    /// direction means a nonce counter can restart at zero without ever repeating a key/nonce pair.
    /// </summary>
    public byte[] DeriveSessionKey(ReadOnlySpan<byte> salt, string purpose, int lengthBytes = 32)
    {
        // The span overload rather than the array-returning one: the salt is a stack buffer at the call
        // site, and copying it to the heap just to satisfy an overload would be a pointless allocation.
        byte[] key = new byte[lengthBytes];
        HKDF.DeriveKey(HashAlgorithmName.SHA256, _masterKey, key, salt, Encoding.UTF8.GetBytes(purpose));
        return key;
    }

    /// <summary>Keyed proof that the far side holds the same secret, used by the handshake.</summary>
    public byte[] ComputeMac(ReadOnlySpan<byte> message)
    {
        using var mac = new HMACSHA256(_masterKey);
        return mac.ComputeHash(message.ToArray());
    }

    public PairingInvite CreateInvite(string deviceName, string serviceName, int port) =>
        new(this, deviceName, serviceName, port);

    public override string ToString() => $"配对码 {Fingerprint}";

    private static string Format(string raw)
    {
        var builder = new StringBuilder(raw.Length + (raw.Length / GroupSize));
        for (int i = 0; i < raw.Length; i++)
        {
            if (i > 0 && i % GroupSize == 0)
            {
                builder.Append('-');
            }

            builder.Append(raw[i]);
        }

        return builder.ToString();
    }

    private static string ToBase32(ReadOnlySpan<byte> data)
    {
        var builder = new StringBuilder((data.Length * 8 / 5) + 1);
        int buffer = 0;
        int bits = 0;
        foreach (byte value in data)
        {
            buffer = (buffer << 8) | value;
            bits += 8;
            while (bits >= 5)
            {
                bits -= 5;
                builder.Append(Alphabet[(buffer >> bits) & 0x1F]);
            }
        }

        if (bits > 0)
        {
            builder.Append(Alphabet[(buffer << (5 - bits)) & 0x1F]);
        }

        return builder.ToString();
    }

    /// <summary>
    /// Twenty bytes are exactly thirty-two Base32 characters, so a valid code never has a partial group
    /// and any other length is a typo rather than something to pad. O, I and L are folded onto 0 and 1,
    /// which is the whole reason for choosing this alphabet.
    /// </summary>
    private static bool TryFromBase32(string text, out byte[]? secret)
    {
        secret = null;
        Span<char> digits = stackalloc char[SecretSizeBytes * 8 / 5];
        int count = 0;
        foreach (char raw in text)
        {
            if (raw is '-' or ' ' or '_' or '\t')
            {
                continue;
            }

            if (count == digits.Length)
            {
                return false;
            }

            char upper = char.ToUpperInvariant(raw);
            digits[count++] = upper switch
            {
                'O' => '0',
                'I' or 'L' => '1',
                _ => upper,
            };
        }

        if (count != digits.Length)
        {
            return false;
        }

        var bytes = new byte[SecretSizeBytes];
        int buffer = 0;
        int bits = 0;
        int written = 0;
        foreach (char digit in digits)
        {
            int value = Alphabet.IndexOf(digit, StringComparison.Ordinal);
            if (value < 0)
            {
                return false;
            }

            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8)
            {
                bits -= 8;
                bytes[written++] = (byte)((buffer >> bits) & 0xFF);
            }
        }

        secret = bytes;
        return true;
    }
}
