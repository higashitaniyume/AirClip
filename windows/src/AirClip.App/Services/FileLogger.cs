using System.IO;
using System.Text;
using Microsoft.Extensions.Logging;

namespace AirClip.App.Services;

/// <summary>
/// Single rolling log file under %APPDATA%\AirClip. A tray app has no console, and clipboard bugs are
/// almost always timing-dependent, so a durable log is the only realistic way to diagnose reports.
/// </summary>
public sealed class FileLogger : ILoggerProvider
{
    private const long MaxBytes = 1024 * 1024;

    private readonly object _sync = new();
    private readonly string _path;
    private readonly LogLevel _minimum;

    public FileLogger(string directory, LogLevel minimum = LogLevel.Information)
    {
        _path = Path.Combine(directory, "airclip.log");
        _minimum = minimum;

        try
        {
            Directory.CreateDirectory(directory);
            if (File.Exists(_path) && new FileInfo(_path).Length > MaxBytes)
            {
                File.Move(_path, _path + ".1", overwrite: true);
            }
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            // Logging must never be the reason the app fails to start.
        }
    }

    public string FilePath => _path;

    public ILogger CreateLogger(string categoryName) => new CategoryLogger(this, ShortName(categoryName));

    public void Dispose()
    {
    }

    private static string ShortName(string categoryName)
    {
        int lastDot = categoryName.LastIndexOf('.');
        return lastDot >= 0 && lastDot < categoryName.Length - 1 ? categoryName[(lastDot + 1)..] : categoryName;
    }

    private void Write(LogLevel level, string category, string message, Exception? exception)
    {
        var line = new StringBuilder()
            .Append(DateTimeOffset.Now.ToString("yyyy-MM-dd HH:mm:ss.fff"))
            .Append(" [").Append(Abbreviate(level)).Append("] ")
            .Append(category).Append(": ").Append(message);

        if (exception is not null)
        {
            line.Append(" :: ").Append(exception.GetType().Name).Append(": ").Append(exception.Message);
        }

        lock (_sync)
        {
            try
            {
                File.AppendAllText(_path, line.Append(Environment.NewLine).ToString(), Encoding.UTF8);
            }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
            {
                // Swallow: see the constructor.
            }
        }
    }

    private static string Abbreviate(LogLevel level) => level switch
    {
        LogLevel.Trace => "TRC",
        LogLevel.Debug => "DBG",
        LogLevel.Information => "INF",
        LogLevel.Warning => "WRN",
        LogLevel.Error => "ERR",
        LogLevel.Critical => "CRT",
        _ => "OFF",
    };

    private sealed class CategoryLogger(FileLogger owner, string category) : ILogger
    {
        public IDisposable? BeginScope<TState>(TState state)
            where TState : notnull => null;

        public bool IsEnabled(LogLevel logLevel) => logLevel >= owner._minimum;

        public void Log<TState>(
            LogLevel logLevel,
            EventId eventId,
            TState state,
            Exception? exception,
            Func<TState, Exception?, string> formatter)
        {
            if (IsEnabled(logLevel))
            {
                owner.Write(logLevel, category, formatter(state, exception), exception);
            }
        }
    }
}

/// <summary>Adapts <see cref="FileLogger"/> to the generic <c>ILogger&lt;T&gt;</c> the core types take.</summary>
public sealed class FileLogger<T>(FileLogger provider) : ILogger<T>
{
    private readonly ILogger _inner = provider.CreateLogger(typeof(T).FullName ?? typeof(T).Name);

    public IDisposable? BeginScope<TState>(TState state)
        where TState : notnull => _inner.BeginScope(state);

    public bool IsEnabled(LogLevel logLevel) => _inner.IsEnabled(logLevel);

    public void Log<TState>(
        LogLevel logLevel,
        EventId eventId,
        TState state,
        Exception? exception,
        Func<TState, Exception?, string> formatter) =>
        _inner.Log(logLevel, eventId, state, exception, formatter);
}
