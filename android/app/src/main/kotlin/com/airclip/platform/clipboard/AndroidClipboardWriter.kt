package com.airclip.platform.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.core.content.FileProvider
import androidx.core.content.getSystemService
import com.airclip.core.clipboard.ClipContent
import com.airclip.core.clipboard.ClipKind
import com.airclip.core.clipboard.ClipboardWriter
import com.airclip.platform.shizuku.ShizukuClipboardBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Writing the clipboard is the easy half on Android 10+: unlike reads, `setPrimaryClip` is allowed
 * from the background. Some OEM builds still block it, so a Shizuku write is kept as a fallback for
 * text. Images always go through a `FileProvider` URI, because `ClipData` cannot carry a bitmap.
 */
class AndroidClipboardWriter(
    private val context: Context,
    private val shizuku: ShizukuClipboardBackend,
) : ClipboardWriter {

    private val clipboard: ClipboardManager? get() = context.getSystemService()

    override suspend fun write(content: ClipContent): Boolean {
        val clip = withContext(Dispatchers.IO) { buildClip(content) } ?: return false

        val direct = withContext(Dispatchers.Main.immediate) {
            runCatching { clipboard?.setPrimaryClip(clip) }.isSuccess
        }
        if (direct) return true

        // Text-only fallback: the shell-side setPrimaryClip cannot grant a content URI.
        return content.kind == ClipKind.TEXT && shizuku.setText(content.text!!)
    }

    private fun buildClip(content: ClipContent): ClipData? = when (content.kind) {
        ClipKind.TEXT -> ClipData.newPlainText(LABEL, content.text)
        ClipKind.IMAGE -> imageClip(content)
    }

    private fun imageClip(content: ClipContent): ClipData? {
        val image = content.image ?: return null
        val file = cacheFile("${content.hash.take(16)}.png")
        runCatching {
            file.parentFile?.mkdirs()
            file.writeBytes(image.png)
        }.getOrElse { return null }

        pruneCache(file)

        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        }.getOrNull() ?: return null

        return ClipData.newUri(context.contentResolver, LABEL, uri)
    }

    private fun cacheFile(name: String) = File(File(context.cacheDir, CLIP_DIR), name)

    /** Keeps the newest [MAX_CACHED_IMAGES] images; the current one is never a deletion candidate. */
    private fun pruneCache(keep: File) {
        val dir = File(context.cacheDir, CLIP_DIR)
        val files = dir.listFiles()?.sortedByDescending(File::lastModified) ?: return
        files.drop(MAX_CACHED_IMAGES).forEach { candidate ->
            if (candidate.absolutePath != keep.absolutePath) {
                candidate.delete()
            }
        }
    }

    private companion object {
        const val LABEL = "AirClip"
        const val CLIP_DIR = "clips"
        const val MAX_CACHED_IMAGES = 12
    }
}
