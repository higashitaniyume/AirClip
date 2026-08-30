using System.Diagnostics;
using System.IO;
using Microsoft.Win32;

namespace AirClip.App.Services;

/// <summary>
/// Run-on-logon via the per-user Run key. HKCU only: no elevation, and unchecking the box in the UI
/// removes the value again.
/// </summary>
public static class AutoStart
{
    private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string ValueName = "AirClip";

    public static bool IsEnabled()
    {
        try
        {
            using RegistryKey? key = Registry.CurrentUser.OpenSubKey(RunKey, writable: false);
            return key?.GetValue(ValueName) is string value && value.Length > 0;
        }
        catch (Exception ex) when (ex is UnauthorizedAccessException or System.Security.SecurityException)
        {
            return false;
        }
    }

    public static bool TrySet(bool enabled)
    {
        try
        {
            using RegistryKey key = Registry.CurrentUser.CreateSubKey(RunKey, writable: true);
            if (!enabled)
            {
                key.DeleteValue(ValueName, throwOnMissingValue: false);
                return true;
            }

            string? executable = Environment.ProcessPath;
            if (string.IsNullOrEmpty(executable))
            {
                executable = Process.GetCurrentProcess().MainModule?.FileName;
            }

            if (string.IsNullOrEmpty(executable))
            {
                return false;
            }

            key.SetValue(ValueName, $"\"{executable}\" --minimised", RegistryValueKind.String);
            return true;
        }
        catch (Exception ex) when (ex is UnauthorizedAccessException or System.Security.SecurityException or IOException)
        {
            return false;
        }
    }
}
