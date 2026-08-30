namespace AirClip.Platform.Windows.Interop;

/// <summary>
/// Scoped <c>OpenClipboard</c>/<c>CloseClipboard</c> pair. Opening fails while another process holds
/// the clipboard, which is routine on Windows, so the open is retried briefly before giving up.
/// </summary>
internal readonly struct ClipboardSession : IDisposable
{
    private readonly bool _open;

    private ClipboardSession(bool open) => _open = open;

    internal static bool TryOpen(IntPtr owner, out ClipboardSession session, int attempts = 10, int delayMs = 30)
    {
        for (int attempt = 0; attempt < attempts; attempt++)
        {
            if (NativeMethods.OpenClipboard(owner))
            {
                session = new ClipboardSession(true);
                return true;
            }

            if (attempt < attempts - 1)
            {
                Thread.Sleep(delayMs);
            }
        }

        session = default;
        return false;
    }

    public void Dispose()
    {
        if (_open)
        {
            NativeMethods.CloseClipboard();
        }
    }
}
