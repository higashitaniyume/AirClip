using System.ComponentModel;
using System.Runtime.InteropServices;

namespace AirClip.Platform.Windows;

/// <summary>
/// Encrypts a blob so that only this Windows user on this machine can read it back, using DPAPI's
/// <c>CryptProtectData</c>. The pairing secret is a long-lived symmetric key sitting in a file under
/// <c>%APPDATA%</c>, and a file that anything running as another user — or copied off the machine
/// unnoticed — could read is not a secret at all.
/// <para>
/// P/Invoked rather than taken from the <c>System.Security.Cryptography.ProtectedData</c> package: that
/// package is an out-of-band NuGet dependency, and this is two functions with no state.
/// </para>
/// </summary>
public static partial class Win32Dpapi
{
    /// <summary>Never show UI: this runs during startup, possibly before there is a window to own a prompt.</summary>
    private const uint CryptProtectUiForbidden = 0x1;

    private const string Crypt32 = "crypt32.dll";
    private const string Kernel32 = "kernel32.dll";

    /// <summary>
    /// Protects <paramref name="data"/>, optionally bound to <paramref name="entropy"/>: the same entropy
    /// must be supplied to unprotect it, so a blob taken from one of this app's files cannot be replayed
    /// into another one.
    /// </summary>
    public static byte[] Protect(byte[] data, byte[]? entropy = null)
    {
        ArgumentNullException.ThrowIfNull(data);
        DataBlob input = Allocate(data);
        DataBlob salt = Allocate(entropy);
        DataBlob output = default;
        try
        {
            if (!CryptProtectData(
                ref input, IntPtr.Zero, ref salt, IntPtr.Zero, IntPtr.Zero, CryptProtectUiForbidden, out output))
            {
                throw new Win32Exception(Marshal.GetLastWin32Error(), "CryptProtectData 失败，无法加密配对密钥");
            }

            return Read(output);
        }
        finally
        {
            Free(ref input, wipe: true);
            Free(ref salt, wipe: false);
            Release(ref output);
        }
    }

    /// <summary>
    /// Reverses <see cref="Protect"/>. Returns <see langword="false"/> rather than throwing for the two
    /// cases that are expected in the field: a file written by a different user or on a different machine,
    /// and a file that something truncated. Both mean "no usable key", which is a decision for the caller.
    /// </summary>
    public static bool TryUnprotect(byte[] data, byte[]? entropy, out byte[]? plaintext)
    {
        ArgumentNullException.ThrowIfNull(data);
        plaintext = null;
        DataBlob input = Allocate(data);
        DataBlob salt = Allocate(entropy);
        DataBlob output = default;
        try
        {
            if (!CryptUnprotectData(
                ref input, IntPtr.Zero, ref salt, IntPtr.Zero, IntPtr.Zero, CryptProtectUiForbidden, out output))
            {
                return false;
            }

            plaintext = Read(output);
            return true;
        }
        finally
        {
            Free(ref input, wipe: false);
            Free(ref salt, wipe: false);

            // Zeroed before it is handed back to the CryptoAPI heap: this one held the decrypted secret.
            Release(ref output, wipe: true);
        }
    }

    private static DataBlob Allocate(byte[]? data)
    {
        if (data is null || data.Length == 0)
        {
            // A zero-length blob is how the API is told there is no entropy; it must still be a valid struct.
            return default;
        }

        IntPtr buffer = Marshal.AllocHGlobal(data.Length);
        Marshal.Copy(data, 0, buffer, data.Length);
        return new DataBlob { cbData = data.Length, pbData = buffer };
    }

    private static byte[] Read(DataBlob blob)
    {
        if (blob.pbData == IntPtr.Zero || blob.cbData <= 0)
        {
            return [];
        }

        byte[] bytes = new byte[blob.cbData];
        Marshal.Copy(blob.pbData, bytes, 0, blob.cbData);
        return bytes;
    }

    /// <summary>Releases a buffer this class allocated, overwriting it first when it held plaintext.</summary>
    private static void Free(ref DataBlob blob, bool wipe)
    {
        if (blob.pbData == IntPtr.Zero)
        {
            return;
        }

        if (wipe)
        {
            Wipe(blob);
        }

        Marshal.FreeHGlobal(blob.pbData);
        blob = default;
    }

    /// <summary>Releases a buffer <em>the API</em> allocated, which is LocalAlloc memory, not HGlobal.</summary>
    private static void Release(ref DataBlob blob, bool wipe = false)
    {
        if (blob.pbData == IntPtr.Zero)
        {
            return;
        }

        if (wipe)
        {
            Wipe(blob);
        }

        LocalFree(blob.pbData);
        blob = default;
    }

    private static void Wipe(DataBlob blob)
    {
        if (blob.cbData > 0)
        {
            Marshal.Copy(new byte[blob.cbData], 0, blob.pbData, blob.cbData);
        }
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct DataBlob
    {
        public int cbData;
        public IntPtr pbData;
    }

    // szDataDescr and the prompt struct are IntPtr rather than typed: both are always null here, and
    // declaring them so keeps this free of string marshalling the call does not need.
    [LibraryImport(Crypt32, EntryPoint = "CryptProtectData", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool CryptProtectData(
        ref DataBlob pDataIn,
        IntPtr szDataDescr,
        ref DataBlob pOptionalEntropy,
        IntPtr pvReserved,
        IntPtr pPromptStruct,
        uint dwFlags,
        out DataBlob pDataOut);

    [LibraryImport(Crypt32, EntryPoint = "CryptUnprotectData", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool CryptUnprotectData(
        ref DataBlob pDataIn,
        IntPtr ppszDataDescr,
        ref DataBlob pOptionalEntropy,
        IntPtr pvReserved,
        IntPtr pPromptStruct,
        uint dwFlags,
        out DataBlob pDataOut);

    [LibraryImport(Kernel32, EntryPoint = "LocalFree")]
    private static partial IntPtr LocalFree(IntPtr hMem);
}
