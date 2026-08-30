using AirClip.Core.Clipboard;
using AirClip.Core.Sync;
using Xunit;

namespace AirClip.Core.Tests;

public sealed class ClipboardSyncEngineTests
{
    [Fact]
    public void PublishesForeignClipboardChanges()
    {
        Harness harness = Harness.Create();
        harness.Engine.Start();

        harness.Monitor.Raise(ClipboardContent.FromText("copied in notepad"));

        Assert.Single(harness.Published);
        Assert.Equal("copied in notepad", harness.Published[0].Text);
    }

    [Fact]
    public async Task DoesNotRepublishContentWrittenFromRemote()
    {
        Harness harness = Harness.Create();
        harness.Engine.Start();

        ClipboardContent remote = ClipboardContent.FromText("pushed from android");
        Assert.True(await harness.Engine.ApplyRemoteAsync(remote));
        Assert.Single(harness.Writer.Written);

        // Writing to the clipboard raises a local change notification for the same content.
        harness.Monitor.Raise(remote);
        Assert.Empty(harness.Published);

        harness.Time.Advance(TimeSpan.FromSeconds(5));
        harness.Monitor.Raise(ClipboardContent.FromText("typed locally afterwards"));
        Assert.Single(harness.Published);
    }

    [Fact]
    public async Task IgnoresRemoteContentThatEchoesALocalCopy()
    {
        Harness harness = Harness.Create();
        harness.Engine.Start();

        ClipboardContent local = ClipboardContent.FromText("copied on windows");
        harness.Monitor.Raise(local);
        Assert.Single(harness.Published);

        Assert.False(await harness.Engine.ApplyRemoteAsync(local));
        Assert.Empty(harness.Writer.Written);
    }

    [Fact]
    public void DropsContentOverTheSizeLimit()
    {
        Harness harness = Harness.Create(new ClipboardOptions { MaxTextBytes = 8 });
        harness.Engine.Start();

        harness.Monitor.Raise(ClipboardContent.FromText("this text is definitely longer than eight bytes"));

        Assert.Empty(harness.Published);
    }

    [Fact]
    public void DropsImagesWhenImageSyncIsDisabled()
    {
        Harness harness = Harness.Create(new ClipboardOptions { SyncImages = false });
        harness.Engine.Start();

        harness.Monitor.Raise(ClipboardContent.FromImage(new ClipboardImage(1, 1, [1, 2, 3], "hash")));

        Assert.Empty(harness.Published);
    }

    [Fact]
    public void PublishCurrentSendsWhateverIsOnTheClipboard()
    {
        Harness harness = Harness.Create();
        harness.Monitor.Current = ClipboardContent.FromText("manual send");
        harness.Engine.Start();

        Assert.True(harness.Engine.PublishCurrent());
        Assert.Single(harness.Published);
        Assert.False(harness.Engine.PublishCurrent());
    }

    private sealed class Harness
    {
        public required TestTimeProvider Time { get; init; }

        public required FakeMonitor Monitor { get; init; }

        public required FakeWriter Writer { get; init; }

        public required ClipboardSyncEngine Engine { get; init; }

        public required List<ClipboardContent> Published { get; init; }

        public static Harness Create(ClipboardOptions? options = null)
        {
            var time = new TestTimeProvider();
            var monitor = new FakeMonitor();
            var writer = new FakeWriter();
            var published = new List<ClipboardContent>();
            var engine = new ClipboardSyncEngine(
                monitor, writer, new LoopGuard(time, remoteWriteSuppression: TimeSpan.FromSeconds(2)), options);
            engine.LocalClipboardPublished += (_, e) => published.Add(e.Content);

            return new Harness
            {
                Time = time,
                Monitor = monitor,
                Writer = writer,
                Engine = engine,
                Published = published,
            };
        }
    }

    private sealed class FakeMonitor : IClipboardMonitor
    {
        public event EventHandler<ClipboardChangedEventArgs>? Changed;

        public bool IsRunning { get; private set; }

        public ClipboardContent? Current { get; set; }

        public void Start() => IsRunning = true;

        public void Stop() => IsRunning = false;

        public ClipboardContent? ReadCurrent() => Current;

        public void Raise(ClipboardContent content) => Changed?.Invoke(this, new ClipboardChangedEventArgs(content));
    }

    private sealed class FakeWriter : IClipboardWriter
    {
        public List<ClipboardContent> Written { get; } = [];

        public Task WriteAsync(ClipboardContent content, CancellationToken cancellationToken = default)
        {
            Written.Add(content);
            return Task.CompletedTask;
        }
    }
}
