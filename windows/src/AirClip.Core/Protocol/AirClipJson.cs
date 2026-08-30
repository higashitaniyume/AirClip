using System.Text.Json;
using System.Text.Json.Serialization;

namespace AirClip.Core.Protocol;

public static class AirClipJson
{
    public static JsonSerializerOptions Options { get; } = Create();

    public static string Serialize(ClipMessage message) => JsonSerializer.Serialize(message, Options);

    public static ClipMessage? Deserialize(string json) => JsonSerializer.Deserialize<ClipMessage>(json, Options);

    private static JsonSerializerOptions Create()
    {
        var options = new JsonSerializerOptions
        {
            DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
            PropertyNameCaseInsensitive = true,
        };
        options.Converters.Add(new JsonStringEnumConverter(JsonNamingPolicy.SnakeCaseLower));

        // Freeze eagerly so the shared instance can never be mutated mid-flight; the flag installs
        // the reflection-based resolver, which the parameterless overload refuses to do.
        options.MakeReadOnly(populateMissingResolver: true);
        return options;
    }
}
