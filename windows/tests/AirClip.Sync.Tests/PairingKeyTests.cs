using AirClip.Crypto;
using Xunit;

namespace AirClip.Sync.Tests;

public class PairingKeyTests
{
    [Fact]
    public void Code_is_thirty_two_digits_in_groups_of_four()
    {
        PairingKey key = PairingKey.Create();
        Assert.Equal(39, key.Code.Length);
        Assert.All(key.Code.Split('-'), group => Assert.Equal(4, group.Length));
    }

    [Theory]
    [InlineData(true, true)]
    [InlineData(false, true)]
    [InlineData(true, false)]
    public void Code_round_trips_however_the_user_types_it(bool stripDashes, bool lowerCase)
    {
        PairingKey original = PairingKey.Create();
        string typed = original.Code;
        typed = stripDashes ? typed.Replace("-", string.Empty, StringComparison.Ordinal) : typed;
        typed = lowerCase ? typed.ToLowerInvariant() : typed;

        Assert.True(PairingKey.TryParse(typed, out PairingKey? parsed));
        Assert.Equal(original.Code, parsed!.Code);
        Assert.Equal(original.Fingerprint, parsed.Fingerprint);
    }

    [Fact]
    public void Ambiguous_letters_fold_onto_the_digits_they_look_like()
    {
        // A user reading "0" off a phone screen and typing "O" must land on the same key, which is the
        // entire reason for choosing Crockford's alphabet over plain base32.
        PairingKey key = PairingKey.FromSecret(new byte[PairingKey.SecretSizeBytes]);
        string zeros = key.Code;
        string typed = zeros.Replace('0', 'O').Replace('1', 'l');

        Assert.True(PairingKey.TryParse(typed, out PairingKey? parsed));
        Assert.Equal(key.Code, parsed!.Code);
    }

    [Theory]
    [InlineData("")]
    [InlineData("   ")]
    [InlineData("ABCD-EFGH")]
    [InlineData("0123456789012345678901234567890123456789")]
    [InlineData("UUUU-UUUU-UUUU-UUUU-UUUU-UUUU-UUUU-UUUU")]
    public void Malformed_codes_are_rejected(string text)
    {
        Assert.False(PairingKey.TryParse(text, out PairingKey? key));
        Assert.Null(key);
    }

    [Fact]
    public void Fingerprint_depends_on_the_secret_and_never_reveals_it()
    {
        PairingKey first = PairingKey.FromSecret(new byte[PairingKey.SecretSizeBytes]);
        PairingKey same = PairingKey.FromSecret(new byte[PairingKey.SecretSizeBytes]);
        PairingKey other = PairingKey.Create();

        Assert.Equal(first.Fingerprint, same.Fingerprint);
        Assert.NotEqual(first.Fingerprint, other.Fingerprint);
        Assert.Equal(8, first.Fingerprint.Length);
        Assert.DoesNotContain(first.Fingerprint, first.Code, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public void ToString_does_not_leak_the_code()
    {
        PairingKey key = PairingKey.Create();
        string described = key.ToString();

        Assert.Contains(key.Fingerprint, described, StringComparison.Ordinal);
        Assert.DoesNotContain(
            key.Code.Replace("-", string.Empty, StringComparison.Ordinal), described, StringComparison.Ordinal);
    }

    [Fact]
    public void Session_keys_depend_on_both_salt_and_purpose()
    {
        PairingKey key = PairingKey.Create();
        byte[] salt = new byte[64];
        byte[] send = key.DeriveSessionKey(salt, "client-to-server");
        byte[] receive = key.DeriveSessionKey(salt, "server-to-client");
        byte[] again = key.DeriveSessionKey(salt, "client-to-server");
        salt[0] = 1;
        byte[] otherSalt = key.DeriveSessionKey(salt, "client-to-server");

        Assert.Equal(32, send.Length);
        Assert.Equal(send, again);
        Assert.NotEqual(send, receive);
        Assert.NotEqual(send, otherSalt);
    }

    [Fact]
    public void Invite_uri_round_trips_and_its_description_hides_the_secret()
    {
        PairingKey key = PairingKey.Create();
        PairingInvite invite = key.CreateInvite("办公室台式机", "_airclip._tcp.local.", 47653);
        Uri uri = invite.ToUri();

        Assert.True(PairingInvite.TryParse(uri.ToString(), out PairingInvite? parsed));
        Assert.Equal(key.Code, parsed!.Key.Code);
        Assert.Equal("办公室台式机", parsed.DeviceName);
        Assert.Equal("_airclip._tcp.local.", parsed.ServiceName);
        Assert.Equal(47653, parsed.Port);
        Assert.DoesNotContain(
            key.Code.Replace("-", string.Empty, StringComparison.Ordinal),
            invite.ToString(),
            StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public void A_whole_invite_uri_is_accepted_where_a_code_is_expected()
    {
        PairingKey key = PairingKey.Create();
        string uri = key.CreateInvite("桌面", "_airclip._tcp.local.", 47653).ToUri().ToString();

        Assert.True(PairingKey.TryParse(uri, out PairingKey? parsed));
        Assert.Equal(key.Code, parsed!.Code);
    }
}
