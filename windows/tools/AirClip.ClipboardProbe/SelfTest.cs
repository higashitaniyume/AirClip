using AirClip.Core.Clipboard;
using AirClip.Platform.Windows;
using Microsoft.Extensions.Logging;

namespace AirClip.ClipboardProbe;

/// <summary>
/// End-to-end check of the stage-one Windows pieces against the real system clipboard. The existing
/// clipboard content is saved up front and restored afterwards.
/// </summary>
internal static class SelfTest
{
    internal static async Task<int> RunAsync()
    {
        using ProbeContext probe = ProbeContext.Create(LogLevel.Debug);
        probe.Engine.Start();

        ClipboardContent? original = probe.Monitor.ReadCurrent();
        Console.WriteLine($"saved current clipboard: {Describe(original)}\n");

        var failures = new List<string>();
        try
        {
            await ForeignCopyIsPublished(probe, failures);
            await RemoteTextIsNotEchoed(probe, failures);
            await ImageSurvivesTheDibRoundTrip(probe, failures);
        }
        finally
        {
            if (original is not null)
            {
                await probe.Writer.WriteAsync(original);
                Console.WriteLine("\nrestored the original clipboard content");
            }
        }

        foreach (string failure in failures)
        {
            Console.WriteLine($"FAIL  {failure}");
        }

        Console.WriteLine(failures.Count == 0
            ? "\nAll clipboard self-tests passed."
            : $"\n{failures.Count} self-test(s) failed.");

        return failures.Count == 0 ? 0 : 1;
    }

    private static async Task ForeignCopyIsPublished(ProbeContext probe, List<string> failures)
    {
        // A second host owns a different window, which is what a foreign application looks like.
        using var foreignHost = new Win32ClipboardHost();
        var foreignWriter = new Win32ClipboardWriter(foreignHost);
        string sample = $"airclip-foreign-{Guid.NewGuid():N}";

        await foreignWriter.WriteAsync(ClipboardContent.FromText(sample));

        if (!probe.Published.TryTake(out ClipboardContent? published, TimeSpan.FromSeconds(3)))
        {
            failures.Add("a copy made by another window never reached the sync engine");
        }
        else if (published.Text != sample)
        {
            failures.Add($"expected '{sample}', the engine published '{published.Text}'");
        }
        else
        {
            Console.WriteLine("PASS  WM_CLIPBOARDUPDATE from another window was captured and published");
        }
    }

    private static async Task RemoteTextIsNotEchoed(ProbeContext probe, List<string> failures)
    {
        string remote = $"airclip-remote-{Guid.NewGuid():N}";
        if (!await probe.Engine.ApplyRemoteAsync(ClipboardContent.FromText(remote)))
        {
            failures.Add("the engine refused to apply remote text");
            return;
        }

        // Read back first: a co-installed clipboard manager (Ditto, rdpclip, VM guest tools) tends to
        // restamp the clipboard a second or two later, and its markers then legitimately hide it from us.
        ClipboardContent? readBack = probe.Monitor.ReadCurrent();
        if (readBack?.Text != remote)
        {
            failures.Add($"clipboard holds {Describe(readBack)} instead of the remote text");
        }
        else
        {
            Console.WriteLine("PASS  CF_UNICODETEXT write is readable through the Win32 path");
        }

        if (probe.Published.TryTake(out ClipboardContent? echo, TimeSpan.FromSeconds(2)))
        {
            failures.Add($"loop prevention failed, the engine re-published '{echo.Text}'");
        }
        else
        {
            Console.WriteLine("PASS  remote text was written without being echoed back");
        }
    }

    private static async Task ImageSurvivesTheDibRoundTrip(ProbeContext probe, List<string> failures)
    {
        ClipboardContent image = ProbeContext.CreateTestImage(64, 48);
        if (!await probe.Engine.ApplyRemoteAsync(image))
        {
            failures.Add("the engine refused to apply a remote image");
            return;
        }

        ClipboardContent? readBack = probe.Monitor.ReadCurrent();
        if (readBack is null || readBack.Kind != ClipboardContentKind.Image)
        {
            failures.Add($"expected an image on the clipboard, found {Describe(readBack)}");
            return;
        }

        if (readBack.Image!.Width != image.Image!.Width || readBack.Image.Height != image.Image.Height)
        {
            failures.Add($"image geometry changed to {readBack.Image.Width}x{readBack.Image.Height}");
        }
        else if (readBack.Hash != image.Hash)
        {
            failures.Add($"pixels changed during the DIB round-trip ({image.Hash[..8]} -> {readBack.Hash[..8]})");
        }
        else
        {
            Console.WriteLine("PASS  CF_DIBV5/CF_DIB image round-trip is pixel-exact");
        }
    }

    private static string Describe(ClipboardContent? content) => content?.ToString() ?? "<empty>";
}
