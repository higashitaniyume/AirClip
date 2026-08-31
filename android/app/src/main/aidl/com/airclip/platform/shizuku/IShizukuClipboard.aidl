// Interface exposed by the Shizuku user service, which runs in a separate process under the shell
// UID. Transaction 16777114 is what Shizuku itself calls to tear the service down, so `destroy`
// must claim that id explicitly.
package com.airclip.platform.shizuku;

interface IShizukuClipboard {
    void destroy() = 16777114;

    /** Primary clip as text, or null when unavailable. Text only: URIs cannot cross UIDs here. */
    String getPrimaryClipText() = 1;

    boolean setPrimaryClipText(String text) = 2;

    /** Human-readable backend state for the settings screen (matched signature, API level, errors). */
    String describeBackend() = 3;

    /**
     * Log lines the helper has accumulated since the last call, oldest first, newline separated.
     * Drained rather than snapshotted: the app copies them into its own buffer, and the helper's
     * memory budget is one shell process nobody is watching.
     */
    String drainLog() = 4;
}
