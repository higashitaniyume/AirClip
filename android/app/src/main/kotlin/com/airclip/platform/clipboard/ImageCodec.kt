package com.airclip.platform.clipboard

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.airclip.core.clipboard.ClipImage
import com.airclip.core.clipboard.ContentHasher
import java.io.ByteArrayOutputStream

/**
 * Bridges Android bitmaps and AirClip's canonical image form: top-down rows of straight-alpha BGRA
 * quads, hashed rather than the PNG bytes. `Bitmap.getPixels` already returns non-premultiplied
 * sRGB ARGB ints in top-down row order, so the conversion is a byte shuffle.
 */
object ImageCodec {

    fun toClipImage(bitmap: Bitmap): ClipImage {
        val pixels = toCanonicalBgra(bitmap)
        return ClipImage(
            width = bitmap.width,
            height = bitmap.height,
            png = encodePng(bitmap),
            pixelHash = ContentHasher.hashImagePixels(bitmap.width, bitmap.height, pixels),
        )
    }

    fun toCanonicalBgra(bitmap: Bitmap): ByteArray {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val out = ByteArray(width * height * 4)
        var offset = 0
        var opaqueSeen = false
        for (pixel in pixels) {
            out[offset++] = (pixel and 0xFF).toByte()
            out[offset++] = ((pixel shr 8) and 0xFF).toByte()
            out[offset++] = ((pixel shr 16) and 0xFF).toByte()
            val alpha = (pixel ushr 24) and 0xFF
            out[offset++] = alpha.toByte()
            if (alpha != 0) opaqueSeen = true
        }

        // Same rescue as the Windows client's Bgra32Image.WithOpaqueAlphaIfFullyTransparent: some
        // producers leave alpha zeroed. Both sides must apply it or the hashes disagree.
        if (!opaqueSeen && out.isNotEmpty()) {
            var i = 3
            while (i < out.size) {
                out[i] = -1 // 0xFF
                i += 4
            }
        }

        return out
    }

    fun encodePng(bitmap: Bitmap): ByteArray = ByteArrayOutputStream(bitmap.byteCount / 2).use { stream ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        stream.toByteArray()
    }

    fun decodePng(png: ByteArray): Bitmap? = runCatching {
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPremultiplied = false
        }
        BitmapFactory.decodeByteArray(png, 0, png.size, options)
    }.getOrNull()
}
