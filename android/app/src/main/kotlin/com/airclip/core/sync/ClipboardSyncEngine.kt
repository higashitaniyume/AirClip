package com.airclip.core.sync

import com.airclip.core.clipboard.ClipContent
import com.airclip.core.clipboard.ClipKind
import com.airclip.core.clipboard.ClipboardOptions
import com.airclip.core.clipboard.ClipboardReadFailure
import com.airclip.core.clipboard.ClipboardReader
import com.airclip.core.clipboard.ClipboardWriter
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where a publish attempt came from, so the UI can explain which backend actually works. */
enum class PublishSource { LISTENER, IME, ACCESSIBILITY, TILE, SHIZUKU, MANUAL, HISTORY }

sealed interface PublishResult {
    data class Sent(val content: ClipContent) : PublishResult

    /** Recognised as an echo of something we just received; deliberately dropped. */
    data object Suppressed : PublishResult

    data class Failed(val reason: ClipboardReadFailure) : PublishResult
}

/**
 * Wires a clipboard reader/writer pair to the [LoopGuard]. Platform-agnostic, like its Windows
 * counterpart `AirClip.Core.Sync.ClipboardSyncEngine` — Android just has several readers instead of
 * one message-loop monitor, so publishing is pull-based rather than event-based.
 */
class ClipboardSyncEngine(
    private val reader: ClipboardReader,
    private val writer: ClipboardWriter,
    val loopGuard: LoopGuard,
    private val options: () -> ClipboardOptions,
) {
    private val _published = MutableSharedFlow<ClipContent>(extraBufferCapacity = 16)
    private val _applied = MutableSharedFlow<ClipContent>(extraBufferCapacity = 16)
    private val _lastReadSource = MutableStateFlow<PublishSource?>(null)

    /** Local content that passed every filter and should go out to peers. */
    val published: SharedFlow<ClipContent> = _published.asSharedFlow()

    /** Remote content that was actually written to this device's clipboard. */
    val applied: SharedFlow<ClipContent> = _applied.asSharedFlow()

    /**
     * Which backend last read the clipboard successfully. The whole point of shipping four read
     * paths is that the user can see which one their ROM actually honours.
     */
    val lastReadSource: StateFlow<PublishSource?> = _lastReadSource.asStateFlow()

    suspend fun publishCurrent(source: PublishSource): PublishResult {
        val content = reader.read()
            ?: return PublishResult.Failed(reader.lastFailure ?: ClipboardReadFailure.EMPTY)
        _lastReadSource.value = source
        return publish(content, source)
    }

    suspend fun publish(content: ClipContent, source: PublishSource = PublishSource.MANUAL): PublishResult {
        withinLimits(content)?.let { return PublishResult.Failed(it) }

        if (!loopGuard.tryBeginPublish(content.hash)) {
            return PublishResult.Suppressed
        }

        _lastReadSource.value = source

        _published.emit(content)
        return PublishResult.Sent(content)
    }

    /**
     * Writes peer content to the local clipboard behind the loop guard. `false` means the write was
     * skipped (echo or over the limits) or the platform refused it.
     */
    suspend fun applyRemote(content: ClipContent): Boolean {
        if (withinLimits(content) != null) return false

        val scope = loopGuard.tryBeginApply(content.hash) ?: return false
        val written = try {
            writer.write(content)
        } finally {
            scope.close()
        }

        if (written) {
            _applied.emit(content)
        }
        return written
    }

    /**
     * Puts content back on the clipboard *without* republishing it — the history screen's "copy"
     * action. The hash is remembered so the resulting clipboard change is not bounced to peers.
     */
    suspend fun writeLocalOnly(content: ClipContent): Boolean {
        loopGuard.remember(content.hash)
        return writer.write(content)
    }

    /** `null` when the content may be synced, otherwise the reason it may not. */
    private fun withinLimits(content: ClipContent): ClipboardReadFailure? {
        val current = options()
        if (content.kind == ClipKind.IMAGE && !current.syncImages) {
            return ClipboardReadFailure.UNSUPPORTED_MIME
        }

        val limit = if (content.kind == ClipKind.TEXT) current.maxTextBytes else current.maxImageBytes
        return if (content.byteSize <= limit) null else ClipboardReadFailure.TOO_LARGE
    }
}
