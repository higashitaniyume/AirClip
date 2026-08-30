using System.Runtime.InteropServices;

namespace AirClip.Platform.Windows;

/// <summary>
/// Hands back the pages a WPF window leaves resident once it is hidden. Nothing is freed and no state
/// is lost: the pages are only unmapped from the working set and fault straight back in when the window
/// is shown again. For a process that spends hours in the tray between interactions that is the
/// difference between idling around 60 MB and idling inside the spec's 30 MB budget.
/// </summary>
public static partial class Win32Memory
{
    /// <summary>Trims the process working set. Returns false if the call was refused, which is not fatal.</summary>
    public static bool TrimWorkingSet()
    {
        try
        {
            return EmptyWorkingSet(GetCurrentProcess());
        }
        catch (Exception ex) when (ex is DllNotFoundException or EntryPointNotFoundException)
        {
            // A memory optimisation must never be the reason the app fails to run.
            return false;
        }
    }

    [LibraryImport("psapi.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool EmptyWorkingSet(IntPtr process);

    [LibraryImport("kernel32.dll")]
    private static partial IntPtr GetCurrentProcess();
}
