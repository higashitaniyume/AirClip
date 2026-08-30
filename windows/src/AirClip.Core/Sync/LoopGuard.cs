namespace AirClip.Core.Sync;

/// <summary>
/// Prevents the A -&gt; B -&gt; A ping-pong that naive clipboard sync produces. Three layers:
/// a recently-seen hash set (catches exact echoes), a suppression window after writing remote
/// data (catches echoes whose bytes changed during a clipboard round-trip, e.g. re-encoded
/// images), and an <see cref="IsWritingRemote"/> flag for the write itself.
/// </summary>
public sealed class LoopGuard
{
    private readonly Dictionary<string, DateTimeOffset> _recent = new(StringComparer.Ordinal);
    private readonly object _sync = new();
    private readonly TimeProvider _time;
    private readonly TimeSpan _hashTtl;
    private readonly TimeSpan _remoteWriteSuppression;
    private DateTimeOffset _suppressUntil = DateTimeOffset.MinValue;
    private int _activeRemoteWrites;

    public LoopGuard(
        TimeProvider? timeProvider = null,
        TimeSpan? hashTtl = null,
        TimeSpan? remoteWriteSuppression = null)
    {
        _time = timeProvider ?? TimeProvider.System;
        _hashTtl = hashTtl ?? TimeSpan.FromSeconds(20);
        _remoteWriteSuppression = remoteWriteSuppression ?? TimeSpan.FromSeconds(2);
    }

    public bool IsWritingRemote
    {
        get
        {
            lock (_sync)
            {
                return _activeRemoteWrites > 0 || _time.GetUtcNow() < _suppressUntil;
            }
        }
    }

    /// <summary>Local clipboard changed: may we send it to peers?</summary>
    public bool TryBeginPublish(string hash)
    {
        ArgumentException.ThrowIfNullOrEmpty(hash);
        lock (_sync)
        {
            DateTimeOffset now = _time.GetUtcNow();
            Prune(now);

            if (_activeRemoteWrites > 0 || now < _suppressUntil)
            {
                return false;
            }

            if (_recent.ContainsKey(hash))
            {
                _recent[hash] = now;
                return false;
            }

            _recent[hash] = now;
            return true;
        }
    }

    /// <summary>
    /// A peer sent us content: may we write it to the local clipboard? Returns a scope that must be
    /// disposed after the write completes, or <see langword="null"/> if the content is an echo.
    /// </summary>
    public IDisposable? TryBeginApply(string hash)
    {
        ArgumentException.ThrowIfNullOrEmpty(hash);
        lock (_sync)
        {
            DateTimeOffset now = _time.GetUtcNow();
            Prune(now);

            if (_recent.ContainsKey(hash))
            {
                _recent[hash] = now;
                return null;
            }

            _recent[hash] = now;
            _activeRemoteWrites++;
            return new ApplyScope(this);
        }
    }

    public void Reset()
    {
        lock (_sync)
        {
            _recent.Clear();
            _activeRemoteWrites = 0;
            _suppressUntil = DateTimeOffset.MinValue;
        }
    }

    private void EndApply()
    {
        lock (_sync)
        {
            if (_activeRemoteWrites > 0)
            {
                _activeRemoteWrites--;
            }

            _suppressUntil = _time.GetUtcNow() + _remoteWriteSuppression;
        }
    }

    private void Prune(DateTimeOffset now)
    {
        if (_recent.Count == 0)
        {
            return;
        }

        List<string>? expired = null;
        foreach ((string key, DateTimeOffset stamp) in _recent)
        {
            if (now - stamp > _hashTtl)
            {
                (expired ??= []).Add(key);
            }
        }

        if (expired is null)
        {
            return;
        }

        foreach (string key in expired)
        {
            _recent.Remove(key);
        }
    }

    private sealed class ApplyScope(LoopGuard owner) : IDisposable
    {
        private LoopGuard? _owner = owner;

        public void Dispose() => Interlocked.Exchange(ref _owner, null)?.EndApply();
    }
}
