package com.airclip.data

import android.content.Context
import com.airclip.core.clipboard.ClipContent
import com.airclip.core.clipboard.ClipImage
import com.airclip.core.clipboard.ClipKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * One row of the history list. Text lives in the index; image bytes live next to it as a PNG file,
 * because a few megabytes of base64 per entry would make the index unusable.
 */
@Serializable
data class HistoryEntry(
    val id: String = UUID.randomUUID().toString(),
    val hash: String = "",
    val kind: ClipKind = ClipKind.TEXT,
    val text: String? = null,
    /** File name under `files/history`, or `null` once the payload has been pruned. */
    val imageFile: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val byteSize: Int = 0,
    /** `null` for content this device copied itself. */
    val fromDeviceName: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
) {
    val isLocal: Boolean get() = fromDeviceName == null

    /** Whether the payload is still around, i.e. whether 复制 / 重新发送 can work on this row. */
    val isRestorable: Boolean
        get() = if (kind == ClipKind.TEXT) !text.isNullOrEmpty() else imageFile != null

    fun preview(maxChars: Int = 160): String = when (kind) {
        ClipKind.TEXT -> text.orEmpty().replace('\n', ' ').trim().take(maxChars)
        ClipKind.IMAGE -> "${width ?: 0} × ${height ?: 0}"
    }
}

/**
 * Clipboard history, newest first, persisted under `files/history`. Deliberately not a DataStore:
 * the payloads are large and mostly binary, and the list is rewritten wholesale on every change.
 *
 * Re-copying an entry is the reason payloads are kept at all, so an entry whose PNG was pruned
 * reports [HistoryEntry.isRestorable] `false` rather than silently failing later.
 */
class HistoryStore(context: Context, private val limit: () -> Int) {

    private val directory = File(context.filesDir, DIRECTORY)
    private val index = File(directory, INDEX_FILE)
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    private val _entries = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val entries: StateFlow<List<HistoryEntry>> = _entries.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val stored = runCatching {
                if (index.isFile) json.decodeFromString<List<HistoryEntry>>(index.readText()) else emptyList()
            }.getOrDefault(emptyList())

            // Drop references to files that are gone (cleared cache, restored backup, manual delete).
            _entries.value = stored.map { entry ->
                if (entry.imageFile != null && !File(directory, entry.imageFile).isFile) {
                    entry.copy(imageFile = null)
                } else {
                    entry
                }
            }
        }
    }

    /** Adds [content] at the top, replacing any older row with the same hash. Returns the new row. */
    suspend fun record(content: ClipContent, fromDeviceName: String?): HistoryEntry =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val imageFile = content.image?.let { image ->
                    val name = "${content.hash.take(16)}.png"
                    runCatching {
                        directory.mkdirs()
                        File(directory, name).writeBytes(image.png)
                        name
                    }.getOrNull()
                }

                val entry = HistoryEntry(
                    hash = content.hash,
                    kind = content.kind,
                    text = content.text,
                    imageFile = imageFile,
                    width = content.image?.width,
                    height = content.image?.height,
                    byteSize = content.byteSize,
                    fromDeviceName = fromDeviceName,
                )

                val kept = _entries.value.filterNot { it.hash == entry.hash }
                val merged = (listOf(entry) + kept).take(limit().coerceAtLeast(1))
                _entries.value = pruneImages(merged)
                persist()
                entry
            }
        }

    /** Rebuilds the clipboard content behind a row, or `null` if its payload is gone. */
    suspend fun restore(entry: HistoryEntry): ClipContent? = withContext(Dispatchers.IO) {
        when (entry.kind) {
            ClipKind.TEXT -> entry.text?.takeIf { it.isNotEmpty() }?.let(ClipContent::fromText)
            ClipKind.IMAGE -> {
                val file = entry.imageFile?.let { File(directory, it) } ?: return@withContext null
                val png = runCatching { file.readBytes() }.getOrNull() ?: return@withContext null
                val image = ClipImage(entry.width ?: 0, entry.height ?: 0, png, entry.hash)
                if (image.width <= 0 || image.height <= 0) null else ClipContent.fromImage(image)
            }
        }
    }

    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            _entries.value = _entries.value.filterNot { it.id == id }
            deleteOrphanFiles()
            persist()
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock {
            _entries.value = emptyList()
            deleteOrphanFiles()
            persist()
        }
    }

    /** Applies a shrunken history limit; called when the setting changes. */
    suspend fun trim() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val trimmed = _entries.value.take(limit().coerceAtLeast(1))
            if (trimmed.size != _entries.value.size) {
                _entries.value = trimmed
                deleteOrphanFiles()
                persist()
            }
        }
    }

    /** Keeps the newest [MAX_IMAGE_PAYLOADS] PNGs; older image rows survive as metadata only. */
    private fun pruneImages(all: List<HistoryEntry>): List<HistoryEntry> {
        var images = 0
        val result = all.map { entry ->
            if (entry.imageFile == null) return@map entry
            images++
            if (images <= MAX_IMAGE_PAYLOADS) entry else entry.copy(imageFile = null)
        }
        deleteOrphanFiles(result)
        return result
    }

    private fun deleteOrphanFiles(reference: List<HistoryEntry> = _entries.value) {
        val referenced = reference.mapNotNull { it.imageFile }.toSet()
        directory.listFiles()?.forEach { file ->
            if (file.name != INDEX_FILE && file.name !in referenced) {
                file.delete()
            }
        }
    }

    private fun persist() {
        runCatching {
            directory.mkdirs()
            index.writeText(json.encodeToString(_entries.value))
        }
    }

    private companion object {
        const val DIRECTORY = "history"
        const val INDEX_FILE = "index.json"

        /** 8 MB per image; more than a couple of dozen would be an unreasonable amount of storage. */
        const val MAX_IMAGE_PAYLOADS = 24
    }
}
