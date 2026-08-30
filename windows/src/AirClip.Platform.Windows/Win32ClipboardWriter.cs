using System.ComponentModel;
using System.Runtime.InteropServices;
using AirClip.Core.Clipboard;
using AirClip.Platform.Windows.Imaging;
using AirClip.Platform.Windows.Interop;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Logging.Abstractions;

namespace AirClip.Platform.Windows;

/// <summary>
/// Writes clipboard content with the standard OpenClipboard / EmptyClipboard / SetClipboardData
/// sequence. Handles passed to SetClipboardData become owned by the system, so they are only freed
/// when the call fails.
/// </summary>
public sealed class Win32ClipboardWriter : IClipboardWriter
{
    private readonly Win32ClipboardHost _host;
    private readonly ILogger _logger;

    public Win32ClipboardWriter(Win32ClipboardHost host, ILogger<Win32ClipboardWriter>? logger = null)
    {
        _host = host;
        _logger = logger ?? NullLogger<Win32ClipboardWriter>.Instance;
    }

    public Task WriteAsync(ClipboardContent content, CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(content);
        cancellationToken.ThrowIfCancellationRequested();

        _host.Start();
        return _host.InvokeAsync(() => Write(content));
    }

    /// <summary>
    /// Writes text that must not be captured by anything: AirClip's own monitor already skips its own
    /// writes, and the three markers keep Windows clipboard history, the cloud clipboard and third-party
    /// clipboard managers away as well. Used for the pairing code, which is the group's shared secret.
    /// </summary>
    public Task WriteSensitiveTextAsync(string text, CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(text);
        cancellationToken.ThrowIfCancellationRequested();

        _host.Start();
        return _host.InvokeAsync(() => WriteSensitiveText(text));
    }

    private bool WriteSensitiveText(string text)
    {
        if (!ClipboardSession.TryOpen(_host.Handle, out ClipboardSession session))
        {
            throw new InvalidOperationException("Could not open the clipboard for writing; another process holds it.");
        }

        using (session)
        {
            if (!NativeMethods.EmptyClipboard())
            {
                throw new Win32Exception(Marshal.GetLastWin32Error(), "EmptyClipboard failed.");
            }

            SetData(NativeMethods.CF_UNICODETEXT, AllocateUnicodeText(text));
            MarkPrivate(ClipboardFormats.ExcludeFromMonitorProcessingFormat, 1);
            MarkPrivate(ClipboardFormats.CanIncludeInClipboardHistoryFormat, 0);
            MarkPrivate(ClipboardFormats.CanUploadToCloudClipboardFormat, 0);
        }

        _logger.LogDebug("Wrote {Length} characters of private text to the clipboard", text.Length);
        return true;
    }

    /// <summary>
    /// Publishes one of the DWORD opt-out markers. A marker that cannot be registered is not fatal: the
    /// text is already on the clipboard, and the caller is told what the guarantee is worth by the log.
    /// </summary>
    private void MarkPrivate(uint format, uint value)
    {
        if (format == 0)
        {
            _logger.LogWarning("Could not register a clipboard privacy format; the copy is not marked");
            return;
        }

        SetData(format, AllocateBytes(BitConverter.GetBytes(value)));
    }

    private bool Write(ClipboardContent content)
    {
        if (!ClipboardSession.TryOpen(_host.Handle, out ClipboardSession session))
        {
            throw new InvalidOperationException("Could not open the clipboard for writing; another process holds it.");
        }

        using (session)
        {
            if (!NativeMethods.EmptyClipboard())
            {
                throw new Win32Exception(Marshal.GetLastWin32Error(), "EmptyClipboard failed.");
            }

            if (content.Kind == ClipboardContentKind.Text)
            {
                SetData(NativeMethods.CF_UNICODETEXT, AllocateUnicodeText(content.Text!));
            }
            else
            {
                if (!DibDecoder.TryDecodeEncoded(content.Image!.Png, out Bgra32Image pixels))
                {
                    throw new InvalidOperationException("The image payload could not be decoded.");
                }

                SetData(NativeMethods.CF_DIBV5, AllocateBytes(DibEncoder.EncodeDibV5(pixels)));
                SetData(NativeMethods.CF_DIB, AllocateBytes(DibEncoder.EncodeDib24(pixels)));
            }
        }

        _logger.LogDebug("Wrote {Content} to the clipboard", content);
        return true;
    }

    private static void SetData(uint format, IntPtr handle)
    {
        if (NativeMethods.SetClipboardData(format, handle) != IntPtr.Zero)
        {
            return;
        }

        int error = Marshal.GetLastWin32Error();
        NativeMethods.GlobalFree(handle);
        throw new Win32Exception(error, $"SetClipboardData failed for format {format}.");
    }

    private static IntPtr AllocateUnicodeText(string text)
    {
        IntPtr handle = AllocateMoveable((nuint)((text.Length + 1) * sizeof(char)), out IntPtr pointer);
        try
        {
            if (text.Length > 0)
            {
                Marshal.Copy(text.ToCharArray(), 0, pointer, text.Length);
            }

            Marshal.WriteInt16(pointer, text.Length * sizeof(char), 0);
        }
        finally
        {
            NativeMethods.GlobalUnlock(handle);
        }

        return handle;
    }

    private static IntPtr AllocateBytes(byte[] data)
    {
        IntPtr handle = AllocateMoveable((nuint)data.Length, out IntPtr pointer);
        try
        {
            Marshal.Copy(data, 0, pointer, data.Length);
        }
        finally
        {
            NativeMethods.GlobalUnlock(handle);
        }

        return handle;
    }

    private static IntPtr AllocateMoveable(nuint bytes, out IntPtr pointer)
    {
        IntPtr handle = NativeMethods.GlobalAlloc(NativeMethods.GMEM_MOVEABLE, bytes);
        if (handle == IntPtr.Zero)
        {
            throw new OutOfMemoryException("GlobalAlloc for clipboard data failed.");
        }

        pointer = NativeMethods.GlobalLock(handle);
        if (pointer != IntPtr.Zero)
        {
            return handle;
        }

        int error = Marshal.GetLastWin32Error();
        NativeMethods.GlobalFree(handle);
        throw new Win32Exception(error, "GlobalLock for clipboard data failed.");
    }
}
