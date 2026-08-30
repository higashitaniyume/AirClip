using System.IO;
using System.Text;
using AirClip.Crypto;
using AirClip.Platform.Windows;

namespace AirClip.App.Services;

/// <summary>
/// Keeps the group's pairing secret between runs. It lives beside <c>settings.json</c> but never inside it:
/// the settings file is plain JSON that a user is expected to open and edit, and the secret is the one
/// thing in AirClip whose disclosure would let anyone on the LAN read the clipboard.
/// <para>
/// At rest it is DPAPI-protected, bound to the current Windows user, so the file is useless if it is
/// copied off the machine or read by another account on it. <see cref="Entropy"/> binds the blob to this
/// particular file as well, so a protected blob lifted from some other AirClip file cannot be dropped in.
/// </para>
/// </summary>
public sealed class PairingStore
{
    /// <summary>
    /// Extra input DPAPI mixes in. Versioned, because the day the format of what is stored changes, the
    /// old blobs should fail to decrypt loudly rather than be misread as the new format.
    /// </summary>
    private static readonly byte[] Entropy = Encoding.UTF8.GetBytes("AirClip.pairing.v1");

    private readonly object _sync = new();

    public PairingStore(string? directory = null)
    {
        Directory = directory ?? Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "AirClip");
        FilePath = Path.Combine(Directory, "pairing.bin");
    }

    public string Directory { get; }

    public string FilePath { get; }

    public bool Exists => File.Exists(FilePath);

    /// <summary>
    /// Reads the stored key. <see langword="false"/> with a null <paramref name="error"/> means simply
    /// "no key yet"; a non-null one means there was a file and it could not be used, which the caller is
    /// expected to show the user rather than swallow.
    /// </summary>
    public bool TryLoad(out PairingKey? key, out string? error)
    {
        lock (_sync)
        {
            key = null;
            error = null;

            byte[] blob;
            try
            {
                if (!File.Exists(FilePath))
                {
                    return false;
                }

                blob = File.ReadAllBytes(FilePath);
            }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
            {
                error = $"无法读取配对文件：{ex.Message}";
                return false;
            }

            if (!Win32Dpapi.TryUnprotect(blob, Entropy, out byte[]? secret))
            {
                error = "配对文件无法解密，可能是由其他 Windows 用户或其他电脑上的 AirClip 写入的";
                return false;
            }

            try
            {
                if (secret!.Length != PairingKey.SecretSizeBytes)
                {
                    error = "配对文件内容长度不正确，已损坏";
                    return false;
                }

                key = PairingKey.FromSecret(secret);
                return true;
            }
            finally
            {
                Array.Clear(secret!);
            }
        }
    }

    /// <summary>
    /// The startup path: returns the stored key, or a brand-new one when there is nothing usable on disk.
    /// A new key is never minted silently over a file that exists — <paramref name="notice"/> says what
    /// happened, because a device that quietly re-pairs itself looks to the user like a network fault.
    /// </summary>
    public PairingKey LoadOrCreate(out string? notice)
    {
        bool hadFile = Exists;
        if (TryLoad(out PairingKey? key, out string? error))
        {
            notice = null;
            return key!;
        }

        PairingKey created = PairingKey.Create();
        string? saveError = Save(created);

        notice = (error, hadFile) switch
        {
            (not null, _) => $"{error}；已生成新的配对码 {created.Fingerprint}，需要在其他设备上重新配对",
            (_, true) => $"配对文件不可用，已生成新的配对码 {created.Fingerprint}",
            _ => $"已生成新的配对码 {created.Fingerprint}，请在其他设备上输入相同的配对码",
        };

        if (saveError is not null)
        {
            notice = $"{notice}（{saveError}，下次启动会再次变化）";
        }

        return created;
    }

    /// <summary>
    /// Persists <paramref name="key"/>, returning a message on failure instead of throwing: losing the
    /// ability to save is worth telling the user about, but it must not stop sync from working right now.
    /// </summary>
    public string? Save(PairingKey key)
    {
        ArgumentNullException.ThrowIfNull(key);
        lock (_sync)
        {
            byte[] secret = key.ExportSecret();
            byte[]? blob = null;
            try
            {
                blob = Win32Dpapi.Protect(secret, Entropy);
                System.IO.Directory.CreateDirectory(Directory);

                // Written to a sibling first: a half-written pairing file would cost the user the whole
                // group, and File.Move over an existing file is the closest thing Windows gives us to atomic.
                string temporary = FilePath + ".tmp";
                File.WriteAllBytes(temporary, blob);
                File.Move(temporary, FilePath, overwrite: true);
                return null;
            }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or InvalidOperationException)
            {
                return $"无法保存配对文件：{ex.Message}";
            }
            catch (System.ComponentModel.Win32Exception ex)
            {
                return $"无法加密配对文件：{ex.Message}";
            }
            finally
            {
                Array.Clear(secret);
                if (blob is not null)
                {
                    Array.Clear(blob);
                }
            }
        }
    }
}
