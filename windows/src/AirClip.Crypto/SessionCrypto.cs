using System.Buffers.Binary;
using System.Security.Cryptography;
using System.Text;

namespace AirClip.Crypto;

/// <summary>
/// One connection's worth of AES-256-GCM, keyed by the group secret and both sides' handshake
/// challenges, with a separate key per direction. Two properties are worth spelling out because they are
/// what make the scheme safe rather than merely encrypted:
/// <list type="bullet">
/// <item>Nonces are a counter, not random. Ninety-six random bits per message would be fine in practice
/// but "fine in practice" is doing a birthday-bound calculation on the user's behalf; a counter under a
/// key that exists only for this connection simply cannot repeat.</item>
/// <item>The counter must strictly increase on receive, which turns the nonce into replay protection for
/// free: a captured frame replayed later decrypts correctly and is still rejected.</item>
/// </list>
/// </summary>
public sealed class SessionCrypto : IDisposable
{
    public const int ChallengeSize = 32;
    public const int NonceSize = 12;
    public const int TagSize = 16;
    public const int Overhead = NonceSize + TagSize;

    private const string ClientToServer = "client-to-server";
    private const string ServerToClient = "server-to-client";
    private const string AuthContext = "airclip-auth-v1";

    private readonly AesGcm _send;
    private readonly AesGcm _receive;
    private ulong _sendCounter;
    private ulong _highestReceived;

    private SessionCrypto(byte[] sendKey, byte[] receiveKey)
    {
        _send = new AesGcm(sendKey, TagSize);
        _receive = new AesGcm(receiveKey, TagSize);

        // AesGcm has imported the key material; our copies are no longer needed anywhere.
        CryptographicOperations.ZeroMemory(sendKey);
        CryptographicOperations.ZeroMemory(receiveKey);
    }

    public static byte[] CreateChallenge() => RandomNumberGenerator.GetBytes(ChallengeSize);

    /// <summary>
    /// Both sides run this with the same two challenges and disagree only on <paramref name="isServer"/>,
    /// which is what swaps the send and receive keys so neither direction shares a key/nonce space.
    /// </summary>
    public static SessionCrypto Establish(
        PairingKey key, ReadOnlySpan<byte> clientChallenge, ReadOnlySpan<byte> serverChallenge, bool isServer)
    {
        ArgumentNullException.ThrowIfNull(key);
        if (clientChallenge.Length != ChallengeSize || serverChallenge.Length != ChallengeSize)
        {
            throw new ArgumentException($"握手随机数必须是 {ChallengeSize} 字节");
        }

        Span<byte> salt = stackalloc byte[ChallengeSize * 2];
        clientChallenge.CopyTo(salt);
        serverChallenge.CopyTo(salt[ChallengeSize..]);

        byte[] clientKey = key.DeriveSessionKey(salt, ClientToServer);
        byte[] serverKey = key.DeriveSessionKey(salt, ServerToClient);
        return isServer ? new SessionCrypto(serverKey, clientKey) : new SessionCrypto(clientKey, serverKey);
    }

    /// <summary>
    /// Proof that the far side holds the group secret, bound to this handshake and to who is claiming
    /// what: without the device id and the role in the MAC, a proof could be reflected back at its author.
    /// </summary>
    public static byte[] ComputeProof(
        PairingKey key,
        ReadOnlySpan<byte> clientChallenge,
        ReadOnlySpan<byte> serverChallenge,
        bool isServer,
        string deviceId)
    {
        ArgumentNullException.ThrowIfNull(key);
        byte[] role = Encoding.UTF8.GetBytes(isServer ? "server" : "client");
        byte[] context = Encoding.UTF8.GetBytes(AuthContext);
        byte[] device = Encoding.UTF8.GetBytes(deviceId ?? string.Empty);

        byte[] message = new byte[context.Length + role.Length + device.Length + (ChallengeSize * 2)];
        var cursor = message.AsSpan();
        context.CopyTo(cursor);
        cursor = cursor[context.Length..];
        role.CopyTo(cursor);
        cursor = cursor[role.Length..];
        device.CopyTo(cursor);
        cursor = cursor[device.Length..];
        clientChallenge.CopyTo(cursor);
        serverChallenge.CopyTo(cursor[ChallengeSize..]);

        return key.ComputeMac(message);
    }

    public static bool VerifyProof(
        PairingKey key,
        ReadOnlySpan<byte> clientChallenge,
        ReadOnlySpan<byte> serverChallenge,
        bool isServer,
        string deviceId,
        ReadOnlySpan<byte> candidate)
    {
        byte[] expected = ComputeProof(key, clientChallenge, serverChallenge, isServer, deviceId);
        return CryptographicOperations.FixedTimeEquals(expected, candidate);
    }

    /// <summary>Returns <c>nonce || ciphertext || tag</c>, which is what goes on the wire.</summary>
    public byte[] Seal(ReadOnlySpan<byte> plaintext, ReadOnlySpan<byte> associatedData)
    {
        ulong counter = Interlocked.Increment(ref _sendCounter);
        byte[] envelope = new byte[NonceSize + plaintext.Length + TagSize];
        BinaryPrimitives.WriteUInt64BigEndian(envelope.AsSpan(NonceSize - sizeof(ulong), sizeof(ulong)), counter);

        _send.Encrypt(
            nonce: envelope.AsSpan(0, NonceSize),
            plaintext: plaintext,
            ciphertext: envelope.AsSpan(NonceSize, plaintext.Length),
            tag: envelope.AsSpan(NonceSize + plaintext.Length, TagSize),
            associatedData: associatedData);
        return envelope;
    }

    /// <summary>
    /// Fails closed for every reason a frame can be wrong — truncated, replayed, tampered with, or sent
    /// under a different group secret — and never says which, because the caller has no use for the
    /// distinction and an attacker would.
    /// </summary>
    public bool TryOpen(ReadOnlySpan<byte> envelope, ReadOnlySpan<byte> associatedData, out byte[]? plaintext)
    {
        plaintext = null;
        if (envelope.Length < Overhead)
        {
            return false;
        }

        ulong counter = BinaryPrimitives.ReadUInt64BigEndian(
            envelope.Slice(NonceSize - sizeof(ulong), sizeof(ulong)));
        if (counter <= _highestReceived)
        {
            return false;
        }

        int length = envelope.Length - Overhead;
        byte[] buffer = new byte[length];
        try
        {
            _receive.Decrypt(
                nonce: envelope[..NonceSize],
                ciphertext: envelope.Slice(NonceSize, length),
                tag: envelope[(NonceSize + length)..],
                plaintext: buffer,
                associatedData: associatedData);
        }
        catch (CryptographicException)
        {
            return false;
        }

        _highestReceived = counter;
        plaintext = buffer;
        return true;
    }

    public void Dispose()
    {
        _send.Dispose();
        _receive.Dispose();
    }
}
