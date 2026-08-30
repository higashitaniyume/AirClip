using Microsoft.Extensions.Logging;

namespace AirClip.ClipboardProbe;

internal sealed class ConsoleLogger<T>(LogLevel minimumLevel) : ILogger<T>
{
    public IDisposable? BeginScope<TState>(TState state)
        where TState : notnull => null;

    public bool IsEnabled(LogLevel logLevel) => logLevel >= minimumLevel;

    public void Log<TState>(
        LogLevel logLevel,
        EventId eventId,
        TState state,
        Exception? exception,
        Func<TState, Exception?, string> formatter)
    {
        if (!IsEnabled(logLevel))
        {
            return;
        }

        string suffix = exception is null ? string.Empty : $" :: {exception.GetType().Name}: {exception.Message}";
        Console.WriteLine($"  [{logLevel,-11}] {typeof(T).Name}: {formatter(state, exception)}{suffix}");
    }
}
