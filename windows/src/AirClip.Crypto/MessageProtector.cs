using System.Globalization;
using System.Text;
using AirClip.Core.Protocol;

namespace AirClip.Crypto;

/// <summary>
/// Turns a <see cref="ClipMessage"/> into its encrypted form and back without changing the wire schema:
/// the ciphertext travels in the payload's <c>content</c> field with <c>encoding</c> set to
/// <c>aes-256-gcm</c>, so the JSON a peer parses has exactly the shape the protocol specifies.
/// <para>
/// Two things stay in the clear because the schema puts them outside the payload: the content hash and
/// the mime type. The hash is what makes loop prevention work across devices, and the price is that an
/// eavesdropper who already suspects a specific clipboard value can confirm the guess. That is a real
/// tradeoff, taken deliberately to keep the specified protocol intact; keying the hash with the group
/// secret would close it without changing the field's size or purpose.
/// </para>
/// <para>
/// Every header field that is in the clear is fed to GCM as associated data, so none of them can be
/// altered in flight — a rewritten hash, device id or timestamp makes decryption fail outright.
/// </para>
/// </summary>
public static class MessageProtector
{
    public static ClipMessage Protect(ClipMessage message, SessionCrypto crypto)
    {
        ArgumentNullException.ThrowIfNull(message);
        ArgumentNullException.ThrowIfNull(crypto);

        if (message.Payload is null)
        {
            // Ping and ack carry no content; there is nothing to hide and nothing to unwrap later.
            return message;
        }

        byte[] plaintext = string.Equals(
            message.Payload.Encoding, ProtocolConstants.Base64Encoding, StringComparison.OrdinalIgnoreCase)
            ? Convert.FromBase64String(message.Payload.Content)
            : Encoding.UTF8.GetBytes(message.Payload.Content);

        // Images are sealed as raw PNG bytes rather than as their base64 text: base64 inside the
        // ciphertext would inflate every screenshot by a third for no benefit whatsoever.
        byte[] envelope = crypto.Seal(plaintext, AssociatedData(message));
        return message with
        {
            Payload = message.Payload with
            {
                Content = Convert.ToBase64String(envelope),
                Encoding = ProtocolConstants.AesGcmEncoding,
            },
        };
    }

    /// <summary>
    /// Reverses <see cref="Protect"/>. A payload that is <em>not</em> marked as encrypted is rejected
    /// rather than passed through: accepting cleartext would let anyone on the LAN inject a clipboard
    /// entry simply by omitting the encryption, which is exactly what the session key is there to stop.
    /// </summary>
    public static bool TryUnprotect(
        ClipMessage message, SessionCrypto crypto, out ClipMessage? plain, out string? error)
    {
        ArgumentNullException.ThrowIfNull(message);
        ArgumentNullException.ThrowIfNull(crypto);
        plain = null;
        error = null;

        if (message.Payload is null)
        {
            plain = message;
            return true;
        }

        if (!string.Equals(
            message.Payload.Encoding, ProtocolConstants.AesGcmEncoding, StringComparison.OrdinalIgnoreCase))
        {
            error = "载荷未加密，已拒绝";
            return false;
        }

        byte[] envelope;
        try
        {
            envelope = Convert.FromBase64String(message.Payload.Content);
        }
        catch (FormatException)
        {
            error = "密文不是合法的 base64";
            return false;
        }

        if (!crypto.TryOpen(envelope, AssociatedData(message), out byte[]? plaintext))
        {
            // Deliberately one message for every failure: wrong key, tampered header, replay, truncation.
            error = "解密失败（密钥不符、报文被改动或重放）";
            return false;
        }

        bool isText = string.Equals(
            message.Payload.MimeType, ProtocolConstants.TextMimeType, StringComparison.OrdinalIgnoreCase);
        plain = message with
        {
            Payload = message.Payload with
            {
                Content = isText ? Encoding.UTF8.GetString(plaintext!) : Convert.ToBase64String(plaintext!),
                Encoding = isText ? ProtocolConstants.Utf8Encoding : ProtocolConstants.Base64Encoding,
            },
        };
        return true;
    }

    /// <summary>
    /// The cleartext header, in a fixed order, as GCM associated data. Field order and the separator are
    /// part of the protocol: both sides must build this byte-for-byte identically or nothing decrypts.
    /// </summary>
    private static byte[] AssociatedData(ClipMessage message) => Encoding.UTF8.GetBytes(string.Join(
        '|',
        message.Version,
        message.MessageId,
        message.DeviceId,
        message.Timestamp.ToString(CultureInfo.InvariantCulture),
        message.Type.ToString().ToLowerInvariant(),
        message.Hash));
}
