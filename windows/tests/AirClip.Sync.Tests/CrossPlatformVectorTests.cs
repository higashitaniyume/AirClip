using System.Text;
using AirClip.Core.Protocol;
using AirClip.Crypto;
using Xunit;

namespace AirClip.Sync.Tests;

/// <summary>
/// Frozen bytes rather than properties. Every other test beside this one asks whether the crypto is
/// self-consistent — which a Windows build can satisfy perfectly while agreeing with nothing on the
/// phone, and that is exactly how the two clients came to derive different fingerprints from the same
/// pairing code without either side reporting a fault. The values below were recomputed from the RFCs by
/// a third implementation, so they pin the contract to something neither client can quietly drift away
/// from, and the same numbers are quoted in <c>com.airclip.core.crypto.PairingKey</c> for the Android
/// side to be checked against.
/// <para>
/// A failure here is never "update the expected value". It means the wire format moved, and every device
/// already paired is about to stop talking with no error that names the cause.
/// </para>
/// </summary>
public class CrossPlatformVectorTests
{
    /// <summary>Twenty bytes, <c>00 01 02 … 13</c>: an arbitrary secret both clients can hard-code.</summary>
    private const string SecretHex = "000102030405060708090A0B0C0D0E0F10111213";
    private const string SecretCode = "000G-40R4-0M30-E209-185G-R38E-1W81-24GK";
    private const string SecretFingerprint = "B4171D99";

    /// <summary>
    /// Deliberately not ASCII. The phrase is stretched over its bytes, and a client that hands it to an
    /// API taking <c>char[]</c> can pick up an 8-bit-per-character encoding instead of UTF-8 and derive a
    /// different secret from the same phrase — silently, and only for the users who type non-ASCII.
    /// </summary>
    private const string Passphrase = "airclip-口令-2026";
    private const string PassphraseUtf8Hex = "616972636C69702DE58FA3E4BBA42D32303236";
    private const string PassphraseSecretHex = "106F3EE587130825BF4643061584A038B236DF57";
    private const string PassphraseCode = "21QK-XSC7-2C42-BFT6-8C31-B150-72S3-DQTQ";
    private const string PassphraseFingerprint = "8D56C7C3";

    private const string ClientToServerKeyHex =
        "9764DACAF082D02C2179E6B94110B48D11B6505502FA63E7AD37250B59E3F66A";
    private const string ServerToClientKeyHex =
        "6088D127EA401F8FB6F94531B6B9BF7FE50139934E375BB55357E0645CB92310";
    private const string ClientProofHex =
        "701F1D68777A298FB5813749DBDB1887267CF21AAE810810DB93439EBAD456D4";
    private const string ServerProofHex =
        "54280ACFE572EF745FDBBDD2AE8B209B9379D5AD6D5E1E23D05DCEE0D3C9F3B8";

    private const string PlainText = "你好，剪贴板";
    private const string PlainTextUtf8Hex = "E4BDA0E5A5BDEFBC8CE589AAE8B4B4E69DBF";
    private const string FrozenEnvelopeBase64 =
        "AAAAAAAAAAAAAAABSO2UNJPydbqb6PgPnTfEDmXGX0jh6NdwQe93vBdodmI5EA==";

    [Fact]
    public void A_known_secret_yields_the_frozen_code_and_fingerprint()
    {
        PairingKey key = FrozenKey();

        Assert.Equal(SecretCode, key.Code);
        Assert.Equal(SecretFingerprint, key.Fingerprint);
        Assert.Equal(SecretHex, Convert.ToHexString(key.ExportSecret()));
    }

    [Theory]
    [InlineData(SecretCode)]
    [InlineData("000G40R40M30E209185GR38E1W8124GK")]
    [InlineData("000g-40r4-0m30-e209-185g-r38e-1w81-24gk")]
    [InlineData("000G 40R4 0M30 E209 185G R38E 1W81 24GK")]
    [InlineData("OOOG-4OR4-OM3O-E2O9-185G-R38E-1W81-24GK")]
    public void Every_spelling_a_user_might_type_reaches_the_frozen_key(string typed)
    {
        // The last case is the one that matters in practice: a code read off a phone screen and typed on a
        // laptop, where the zeros arrive as the letter O.
        Assert.True(PairingKey.TryParse(typed, out PairingKey? key));
        Assert.Equal(SecretFingerprint, key!.Fingerprint);
    }

    [Fact]
    public void A_known_passphrase_stretches_to_the_frozen_secret()
    {
        // Guards the literal above before it is used: if this file was ever saved in a non-UTF-8 code page
        // the vector fails for a reason that has nothing to do with the crypto, and this says so directly.
        Assert.Equal(PassphraseUtf8Hex, Convert.ToHexString(Encoding.UTF8.GetBytes(Passphrase)));

        PairingKey key = PairingKey.FromPassphrase(Passphrase);

        Assert.Equal(PassphraseSecretHex, Convert.ToHexString(key.ExportSecret()));
        Assert.Equal(PassphraseCode, key.Code);
        Assert.Equal(PassphraseFingerprint, key.Fingerprint);
    }

    [Fact]
    public void The_pass_prefix_reaches_the_same_key_as_the_phrase_itself()
    {
        Assert.True(PairingKey.TryParse(PairingKey.PassphrasePrefix + Passphrase, out PairingKey? typed));
        Assert.True(PairingKey.TryParse($"PASS: {Passphrase}  ", out PairingKey? sloppy));

        Assert.Equal(PassphraseFingerprint, typed!.Fingerprint);
        Assert.Equal(PassphraseFingerprint, sloppy!.Fingerprint);
    }

    [Fact]
    public void Session_keys_are_frozen_for_a_known_pair_of_challenges()
    {
        // Deriving both directions here pins three things at once: the master key the group secret expands
        // to, the order the two challenges are concatenated in, and the purpose strings.
        byte[] salt = new byte[SessionCrypto.ChallengeSize * 2];
        Array.Fill(salt, (byte)0x01, 0, SessionCrypto.ChallengeSize);
        Array.Fill(salt, (byte)0x02, SessionCrypto.ChallengeSize, SessionCrypto.ChallengeSize);
        PairingKey key = FrozenKey();

        Assert.Equal(
            ClientToServerKeyHex, Convert.ToHexString(key.DeriveSessionKey(salt, "client-to-server")));
        Assert.Equal(
            ServerToClientKeyHex, Convert.ToHexString(key.DeriveSessionKey(salt, "server-to-client")));
    }

    [Fact]
    public void Handshake_proofs_are_frozen_for_each_role()
    {
        PairingKey key = FrozenKey();
        byte[] client = SessionCrypto.ComputeProof(
            key, Challenge(0x01), Challenge(0x02), isServer: false, "device-a");
        byte[] server = SessionCrypto.ComputeProof(
            key, Challenge(0x01), Challenge(0x02), isServer: true, "device-b");

        Assert.Equal(ClientProofHex, Convert.ToHexString(client));
        Assert.Equal(ServerProofHex, Convert.ToHexString(server));
    }

    [Fact]
    public void The_first_frame_of_a_session_is_reproducible_byte_for_byte()
    {
        Assert.Equal(PlainTextUtf8Hex, Convert.ToHexString(Encoding.UTF8.GetBytes(PlainText)));
        using SessionCrypto client = SessionCrypto.Establish(
            FrozenKey(), Challenge(0x01), Challenge(0x02), isServer: false);

        ClipMessage protectedMessage = MessageProtector.Protect(FrozenTextMessage(), client);
        byte[] envelope = Convert.FromBase64String(protectedMessage.Payload!.Content);

        // Split out from the ciphertext so a nonce that stopped starting at one, or stopped being four zero
        // bytes and a big-endian counter, is reported as itself rather than as an unreadable frame.
        Assert.Equal("000000000000000000000001", Convert.ToHexString(envelope, 0, SessionCrypto.NonceSize));
        Assert.Equal(ProtocolConstants.AesGcmEncoding, protectedMessage.Payload.Encoding);
        Assert.Equal(FrozenEnvelopeBase64, protectedMessage.Payload.Content);
    }

    [Fact]
    public void A_frame_recorded_off_the_wire_still_opens()
    {
        // The other half of the same vector, and the half a phone actually exercises: the frozen bytes were
        // produced elsewhere, so this is the closest thing to a real cross-client decrypt in one process.
        using SessionCrypto server = SessionCrypto.Establish(
            FrozenKey(), Challenge(0x01), Challenge(0x02), isServer: true);
        ClipMessage original = FrozenTextMessage();
        ClipMessage onTheWire = original with
        {
            Payload = original.Payload! with
            {
                Content = FrozenEnvelopeBase64,
                Encoding = ProtocolConstants.AesGcmEncoding,
            },
        };

        Assert.True(MessageProtector.TryUnprotect(
            onTheWire, server, out ClipMessage? plain, out string? error));
        Assert.Null(error);
        Assert.Equal(PlainText, plain!.Payload!.Content);
        Assert.Equal(ProtocolConstants.Utf8Encoding, plain.Payload.Encoding);
    }

    [Fact]
    public void The_constants_both_clients_hard_code_are_frozen()
    {
        // Changing any of these renames the protocol: every paired device stops deriving the same key, and
        // the only recovery is re-pairing all of them by hand.
        Assert.Equal(20, PairingKey.SecretSizeBytes);
        Assert.Equal(32, PairingKey.MasterKeySizeBytes);
        Assert.Equal(200_000, PairingKey.PassphraseIterations);
        Assert.Equal("pass:", PairingKey.PassphrasePrefix);
        Assert.Equal(32, SessionCrypto.ChallengeSize);
        Assert.Equal(12, SessionCrypto.NonceSize);
        Assert.Equal(16, SessionCrypto.TagSize);
        Assert.Equal("1.0", ProtocolConstants.Version);
        Assert.Equal("aes-256-gcm", ProtocolConstants.AesGcmEncoding);
    }

    private static PairingKey FrozenKey() => PairingKey.FromSecret(Convert.FromHexString(SecretHex));

    private static byte[] Challenge(byte value)
    {
        byte[] bytes = new byte[SessionCrypto.ChallengeSize];
        Array.Fill(bytes, value);
        return bytes;
    }

    /// <summary>
    /// Every cleartext field GCM covers as associated data, pinned: the frozen envelope can only be
    /// reproduced if both clients join them with the same separator, in the same order, and lower-case the
    /// message type the same way. <c>device_name</c> is absent from that list, so its value is free here.
    /// </summary>
    private static ClipMessage FrozenTextMessage() => new()
    {
        Version = ProtocolConstants.Version,
        MessageId = "msg-0001",
        DeviceId = "device-a",
        DeviceName = "办公室台式机",
        Timestamp = 1735689600000,
        Type = ClipMessageType.Text,
        Hash = "HASH0001",
        Payload = new ClipPayload
        {
            Content = PlainText,
            MimeType = ProtocolConstants.TextMimeType,
            Encoding = ProtocolConstants.Utf8Encoding,
        },
    };
}
