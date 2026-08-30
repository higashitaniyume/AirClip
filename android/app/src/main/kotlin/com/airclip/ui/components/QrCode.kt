package com.airclip.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.airclip.R
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * The pairing URI as a scannable code. Always black on white, whatever the app theme is: a dark-theme
 * QR is an *inverted* QR as far as most scanners are concerned, and this code only has value if the
 * PC reads it on the first try.
 */
@Composable
fun QrCode(text: String, modifier: Modifier = Modifier, size: Dp = 220.dp) {
    val pixels = with(LocalDensity.current) { size.roundToPx() }
    val image = remember(text, pixels) { encodeQr(text, pixels) } ?: return
    Image(
        bitmap = image,
        contentDescription = stringResource(R.string.pair_qr_image),
        modifier = modifier.size(size),
        // Nearest-neighbour: module edges have to stay hard, and smooth scaling blurs them into
        // something a decoder has to guess at.
        filterQuality = FilterQuality.None,
    )
}

/**
 * `null` when zxing refuses the payload — too long for any QR version, in practice. The caller then
 * has the plain text to fall back on, which is why this never throws.
 */
private fun encodeQr(text: String, pixels: Int): ImageBitmap? = runCatching {
    val hints = mapOf(
        EncodeHintType.CHARACTER_SET to "UTF-8",
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.MARGIN to QUIET_ZONE_MODULES,
    )
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, pixels, pixels, hints)
    val colors = IntArray(matrix.width * matrix.height) { index ->
        if (matrix.get(index % matrix.width, index / matrix.width)) DARK else LIGHT
    }
    Bitmap.createBitmap(colors, matrix.width, matrix.height, Bitmap.Config.ARGB_8888).asImageBitmap()
}.getOrNull()

/** The spec asks for 4; 2 is the smallest that still scans reliably and keeps the code readable. */
private const val QUIET_ZONE_MODULES = 2
private const val DARK = 0xFF000000.toInt()
private const val LIGHT = 0xFFFFFFFF.toInt()
