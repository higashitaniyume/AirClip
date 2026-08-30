package com.airclip.ui.components

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A camera preview that reports the first QR code it sees. The caller owns the decision to show it —
 * this composable assumes `CAMERA` has already been granted, because binding without it throws.
 */
@Composable
fun QrScanner(onDecoded: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    // The analyser outlives a recomposition, so it must not capture a stale callback.
    val callback by rememberUpdatedState(onDecoded)
    val executor = remember { Executors.newSingleThreadExecutor() }
    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }

    // Frames keep arriving while the camera tears down; a second hit would re-apply the same key and
    // announce a second pairing for one scan.
    val handled = remember { AtomicBoolean(false) }

    DisposableEffect(owner) {
        val main = ContextCompat.getMainExecutor(context)
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = runCatching { future.get() }.getOrNull() ?: return@addListener
            val preview = Preview.Builder().build()
                .apply { setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                // Decoding is slower than 30fps; queueing frames would only add latency to the hit.
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(executor, QrAnalyzer { text ->
                if (handled.compareAndSet(false, true)) main.execute { callback(text) }
            })
            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }
        }, main)

        onDispose {
            // Leaving the screen has to release the camera even though the lifecycle is still resumed.
            if (future.isDone) runCatching { future.get().unbindAll() } else future.cancel(false)
            executor.shutdown()
        }
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}

/**
 * Decodes the Y plane only. Chroma tells a QR reader nothing, and copying it out of every frame is
 * pure cost; frame rotation is ignored for the same reason — the finder patterns are found in any of
 * the four orientations anyway.
 */
private class QrAnalyzer(private val onDecoded: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val reader = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
    }

    override fun analyze(image: ImageProxy) {
        try {
            decode(image)?.let(onDecoded)
        } finally {
            image.close()
        }
    }

    /** `null` for "no code in this frame", which is the normal case for almost every frame. */
    private fun decode(image: ImageProxy): String? = runCatching {
        val plane = image.planes.first()
        val bytes = ByteArray(plane.buffer.remaining())
        plane.buffer.get(bytes)

        // The buffer is row-strided and the last row may carry no padding, so the usable height is
        // derived from what actually arrived rather than from the image size.
        val stride = plane.rowStride
        val rows = minOf(image.height, bytes.size / stride)
        val width = minOf(image.width, stride)
        val source = PlanarYUVLuminanceSource(bytes, stride, rows, 0, 0, width, rows, false)
        reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
    }.getOrNull().also { reader.reset() }
}
