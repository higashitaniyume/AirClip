package com.airclip.core.sync

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Prevents the A -> B -> A ping-pong that naive clipboard sync produces. Port of
 * `AirClip.Core.Sync.LoopGuard`, with the same three layers:
 *
 *  1. a recently-seen hash set (catches exact echoes),
 *  2. a suppression window after writing remote data (catches echoes whose bytes changed during a
 *     clipboard round-trip, e.g. an image re-encoded by the platform), and
 *  3. the [isWritingRemote] flag for the write itself.
 *
 * The clock is injected and monotonic by default: a wall-clock jump must not widen or collapse the
 * suppression window.
 */
class LoopGuard(
    private val hashTtlMillis: Long = 20_000,
    private val remoteWriteSuppressionMillis: Long = 2_000,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val recent = HashMap<String, Long>()
    private val lock = Any()
    private var suppressUntil = Long.MIN_VALUE
    private var activeRemoteWrites = 0

    val isWritingRemote: Boolean
        get() = synchronized(lock) { activeRemoteWrites > 0 || nowMillis() < suppressUntil }

    /** Local clipboard changed: may we send it to peers? */
    fun tryBeginPublish(hash: String): Boolean {
        require(hash.isNotEmpty()) { "hash must not be empty" }
        synchronized(lock) {
            val now = nowMillis()
            prune(now)

            if (activeRemoteWrites > 0 || now < suppressUntil) {
                return false
            }

            if (recent.containsKey(hash)) {
                recent[hash] = now
                return false
            }

            recent[hash] = now
            return true
        }
    }

    /**
     * A peer sent us content: may we write it to the local clipboard? Returns a scope that must be
     * closed after the write completes, or `null` if the content is an echo of our own.
     */
    fun tryBeginApply(hash: String): ApplyScope? {
        require(hash.isNotEmpty()) { "hash must not be empty" }
        synchronized(lock) {
            val now = nowMillis()
            prune(now)

            if (recent.containsKey(hash)) {
                recent[hash] = now
                return null
            }

            recent[hash] = now
            activeRemoteWrites++
            return ApplyScope(this)
        }
    }

    /**
     * Marks a hash as seen without publishing or applying. Used when the user re-copies a history
     * entry: the clipboard will fire a change we must not bounce back to the sender.
     */
    fun remember(hash: String) {
        if (hash.isEmpty()) return
        synchronized(lock) { recent[hash] = nowMillis() }
    }

    fun reset() {
        synchronized(lock) {
            recent.clear()
            activeRemoteWrites = 0
            suppressUntil = Long.MIN_VALUE
        }
    }

    private fun endApply() {
        synchronized(lock) {
            if (activeRemoteWrites > 0) {
                activeRemoteWrites--
            }
            suppressUntil = nowMillis() + remoteWriteSuppressionMillis
        }
    }

    private fun prune(now: Long) {
        if (recent.isEmpty()) return
        val iterator = recent.entries.iterator()
        while (iterator.hasNext()) {
            if (now - iterator.next().value > hashTtlMillis) {
                iterator.remove()
            }
        }
    }

    /** Idempotent: closing twice must not shorten the suppression window of an unrelated write. */
    class ApplyScope internal constructor(private val owner: LoopGuard) : AutoCloseable {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                owner.endApply()
            }
        }
    }
}
