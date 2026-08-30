using AirClip.ClipboardProbe;
using AirClip.Core.Clipboard;
using Microsoft.Extensions.Logging;

if (args.Contains("--selftest", StringComparer.OrdinalIgnoreCase))
{
    return await SelfTest.RunAsync();
}

LogLevel logLevel = args.Contains("--verbose", StringComparer.OrdinalIgnoreCase) ? LogLevel.Debug : LogLevel.Information;
using ProbeContext probe = ProbeContext.Create(logLevel);
probe.Engine.Start();

Console.WriteLine("AirClip clipboard probe: copy anything and watch it get captured.");
Console.WriteLine("Type text then Enter to simulate a remote push, ':image' for a test bitmap, ':q' to quit.\n");

using var cancellation = new CancellationTokenSource();
_ = Task.Run(() =>
{
    try
    {
        foreach (ClipboardContent content in probe.Published.GetConsumingEnumerable(cancellation.Token))
        {
            Console.WriteLine($"-> would send {content}");
        }
    }
    catch (OperationCanceledException)
    {
        // shutting down
    }
});

while (true)
{
    string? line = Console.ReadLine();
    if (line is null || string.Equals(line, ":q", StringComparison.OrdinalIgnoreCase))
    {
        break;
    }

    if (line.Length == 0)
    {
        continue;
    }

    ClipboardContent push = string.Equals(line, ":image", StringComparison.OrdinalIgnoreCase)
        ? ProbeContext.CreateTestImage(64, 48)
        : ClipboardContent.FromText(line);

    Console.WriteLine($"<- applying {push}");
    await probe.Engine.ApplyRemoteAsync(push);
}

await cancellation.CancelAsync();
return 0;
