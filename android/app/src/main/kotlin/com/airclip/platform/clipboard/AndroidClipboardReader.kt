package com.airclip.platform.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import androidx.core.content.getSystemService
import com.airclip.core.clipboard.ClipContent
import com.airclip.core.clipboard.ClipboardOptions
import com.airclip.core.clipboard.ClipboardReadFailure
import com.airclip.core.clipboard.ClipboardReader
import com.airclip.platform.shizuku.ShizukuClipboardBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads the system clipboard through whichever door is currently open.
 *
 * Android 10+ only lets an app read the clipboard when it is the default IME or its window has
 * focus, and `getPrimaryClip()` returns `null` rather than throwing when it refuses. So the order
 * is: framework read (works from the IME, the accessibility overlay, and
 * `ClipboardRelayActivity`), then the shell-privileged Shizuku backend, and only then give up with
 * [ClipboardReadFailure.DENIED_BACKGROUND] so the UI can tell the user which switch to flip.
 */
class AndroidClipboardReader(
    private val context: Context,
    private val options: () -> ClipboardOptions,
    private val shizuku: ShizukuClipboardBackend,
    /** `true` when this process currently satisfies the platform's focus/IME rule. */
    private val foregroundReadAllowed: () -> Boolean = { false },
) : ClipboardReader {

    @Volatile
    override var lastFailure: ClipboardReadFailure? = null
        private set

    private val clipboard: ClipboardManager? get() = context.getSystemService()

    override suspend fun read(): ClipContent? {
        lastFailure = null

        val clip = withContext(Dispatchers.Main.immediate) {
            runCatching { clipboard?.primaryClip }.getOrNull()
        }
        if (clip != null && clip.itemCount > 0) {
            return fromClipData(clip)
        }

        shizuku.getText()?.let { text ->
            return if (text.isEmpty()) fail(ClipboardReadFailure.EMPTY) else ClipContent.fromText(text)
        }

        // An allowed-but-empty read is a genuinely empty clipboard; otherwise the platform said no.
        return fail(
            if (foregroundReadAllowed()) ClipboardReadFailure.EMPTY else ClipboardReadFailure.DENIED_BACKGROUND,
        )
    }

    private suspend fun fromClipData(clip: ClipData): ClipContent? {
        if (options().honorSensitiveMarkers && isSensitive(clip)) {
            return fail(ClipboardReadFailure.FILTERED_SENSITIVE)
        }

        val item = clip.getItemAt(0)

        item.text?.toString()?.takeIf { it.isNotEmpty() }?.let { return ClipContent.fromText(it) }

        val uri = item.uri
        if (uri != null) {
            val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull().orEmpty()
            if (mime.startsWith("image/")) {
                if (!options().syncImages) return fail(ClipboardReadFailure.UNSUPPORTED_MIME)
                return readImage(clip)
            }
        }

        // Intents and non-image URIs still often coerce to something useful (a link, a file name).
        val coerced = withContext(Dispatchers.IO) {
            runCatching { item.coerceToText(context)?.toString() }.getOrNull()
        }
        return if (coerced.isNullOrEmpty()) fail(ClipboardReadFailure.EMPTY) else ClipContent.fromText(coerced)
    }

    private suspend fun readImage(clip: ClipData): ClipContent? = withContext(Dispatchers.IO) {
        val uri = clip.getItemAt(0).uri ?: return@withContext fail(ClipboardReadFailure.EMPTY)
        val bitmap = runCatching {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                // getPixels() cannot touch a HARDWARE bitmap, and the canonical hash needs pixels.
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = false
            }
        }.getOrNull() ?: return@withContext fail(ClipboardReadFailure.ERROR)

        val argb = if (bitmap.config == Bitmap.Config.ARGB_8888) {
            bitmap
        } else {
            bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return@withContext fail(ClipboardReadFailure.ERROR)
        }

        val image = ImageCodec.toClipImage(argb)
        if (argb !== bitmap) bitmap.recycle()

        if (image.png.size > options().maxImageBytes) {
            fail(ClipboardReadFailure.TOO_LARGE)
        } else {
            ClipContent.fromImage(image)
        }
    }

    /**
     * Password managers mark their clips with `ClipDescription.EXTRA_IS_SENSITIVE`. The literal key
     * is used deliberately: the constant is API 33+, but managers have been setting the same string
     * since long before that.
     */
    private fun isSensitive(clip: ClipData): Boolean {
        val extras = clip.description?.extras ?: return false
        return extras.getBoolean("android.content.extra.IS_SENSITIVE", false) ||
            extras.getBoolean("android.content.extra.IS_REMOTE_DEVICE", false)
    }

    private fun fail(reason: ClipboardReadFailure): ClipContent? {
        lastFailure = reason
        return null
    }
}
