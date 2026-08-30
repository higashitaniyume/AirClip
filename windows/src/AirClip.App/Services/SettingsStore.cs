using System.IO;
using System.Text.Json;

namespace AirClip.App.Services;

/// <summary>
/// Loads and saves <see cref="AppSettings"/>. Writes go to a temporary file first so a crash or a
/// full disk cannot leave a truncated settings file behind.
/// </summary>
public sealed class SettingsStore
{
    private static readonly JsonSerializerOptions SerializerOptions = new()
    {
        WriteIndented = true,
        PropertyNameCaseInsensitive = true,
    };

    private readonly object _sync = new();

    public SettingsStore(string? directory = null)
    {
        Directory = directory ?? Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "AirClip");
        FilePath = Path.Combine(Directory, "settings.json");
    }

    public string Directory { get; }

    public string FilePath { get; }

    public AppSettings Load()
    {
        lock (_sync)
        {
            try
            {
                if (File.Exists(FilePath))
                {
                    AppSettings? loaded = JsonSerializer.Deserialize<AppSettings>(
                        File.ReadAllText(FilePath), SerializerOptions);
                    if (loaded is not null)
                    {
                        return loaded.EnsureIdentity();
                    }
                }
            }
            catch (Exception ex) when (ex is IOException or JsonException or UnauthorizedAccessException)
            {
                // A corrupt or unreadable file must not stop the app from starting.
            }

            return new AppSettings().EnsureIdentity();
        }
    }

    public void Save(AppSettings settings)
    {
        lock (_sync)
        {
            try
            {
                System.IO.Directory.CreateDirectory(Directory);
                string temporary = FilePath + ".tmp";
                File.WriteAllText(temporary, JsonSerializer.Serialize(settings, SerializerOptions));
                File.Move(temporary, FilePath, overwrite: true);
            }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
            {
                // Settings are a convenience, not a correctness requirement.
            }
        }
    }
}
