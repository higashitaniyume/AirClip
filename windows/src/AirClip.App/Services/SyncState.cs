namespace AirClip.App.Services;

public enum SyncState
{
    /// <summary>Running and watching the clipboard, but no peer is connected.</summary>
    Offline,

    /// <summary>At least one peer is connected.</summary>
    Connected,

    /// <summary>The user paused syncing; the clipboard is not being watched.</summary>
    Paused,
}

public enum ClipDirection
{
    Local,
    Remote,
}
