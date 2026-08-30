using AirClip.Core.Sync;
using Xunit;

namespace AirClip.Core.Tests;

public sealed class LoopGuardTests
{
    private const string HashA = "aaaa";
    private const string HashB = "bbbb";

    [Fact]
    public void PublishesNewContentOnce()
    {
        var guard = new LoopGuard(new TestTimeProvider());

        Assert.True(guard.TryBeginPublish(HashA));
        Assert.False(guard.TryBeginPublish(HashA));
    }

    [Fact]
    public void ForgetsHashesAfterTtl()
    {
        var time = new TestTimeProvider();
        var guard = new LoopGuard(time, hashTtl: TimeSpan.FromSeconds(10));

        Assert.True(guard.TryBeginPublish(HashA));
        time.Advance(TimeSpan.FromSeconds(11));

        Assert.True(guard.TryBeginPublish(HashA));
    }

    [Fact]
    public void RejectsRemoteContentWeJustPublished()
    {
        var guard = new LoopGuard(new TestTimeProvider());

        Assert.True(guard.TryBeginPublish(HashA));
        Assert.Null(guard.TryBeginApply(HashA));
    }

    [Fact]
    public void SuppressesPublishingWhileAndAfterApplyingRemoteContent()
    {
        var time = new TestTimeProvider();
        var guard = new LoopGuard(time, remoteWriteSuppression: TimeSpan.FromSeconds(2));

        IDisposable? scope = guard.TryBeginApply(HashA);
        Assert.NotNull(scope);
        Assert.True(guard.IsWritingRemote);

        // A clipboard round-trip can change the bytes (images get re-encoded), so a different hash
        // must still be suppressed for the duration of the window.
        Assert.False(guard.TryBeginPublish(HashB));

        scope.Dispose();
        Assert.True(guard.IsWritingRemote);
        Assert.False(guard.TryBeginPublish(HashB));

        time.Advance(TimeSpan.FromSeconds(3));
        Assert.False(guard.IsWritingRemote);
        Assert.True(guard.TryBeginPublish(HashB));
    }

    [Fact]
    public void ResetClearsState()
    {
        var guard = new LoopGuard(new TestTimeProvider());
        Assert.True(guard.TryBeginPublish(HashA));

        guard.Reset();

        Assert.True(guard.TryBeginPublish(HashA));
    }
}
