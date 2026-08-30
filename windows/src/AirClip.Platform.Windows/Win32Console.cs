using System.Runtime.InteropServices;

namespace AirClip.Platform.Windows;

/// <summary>
/// Lets a GUI-subsystem process write to the console it was launched from. Without this, output from
/// a command-line diagnostic mode goes nowhere, because a WinExe starts with no console attached.
/// </summary>
public static partial class Win32Console
{
    private const uint AttachParentProcess = 0xFFFFFFFF;

    public static bool TryAttachToParent() => AttachConsole(AttachParentProcess);

    [LibraryImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool AttachConsole(uint processId);
}
