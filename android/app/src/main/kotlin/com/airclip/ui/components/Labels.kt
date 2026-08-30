package com.airclip.ui.components

import android.content.Context
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.annotation.StringRes
import com.airclip.R
import com.airclip.core.protocol.DevicePlatform
import com.airclip.core.sync.PublishSource

/** Which of the read plans actually produced the last clipboard read, in the user's words. */
@StringRes
fun sourceLabelRes(source: PublishSource): Int = when (source) {
    PublishSource.LISTENER -> R.string.source_listener
    PublishSource.IME -> R.string.source_ime
    PublishSource.ACCESSIBILITY -> R.string.source_accessibility
    PublishSource.TILE -> R.string.source_tile
    PublishSource.SHIZUKU -> R.string.source_shizuku
    PublishSource.MANUAL -> R.string.source_manual
    PublishSource.HISTORY -> R.string.source_history
}

/** Product names, so they are deliberately not translated. */
fun platformLabel(platform: DevicePlatform): String = when (platform) {
    DevicePlatform.WINDOWS -> "Windows"
    DevicePlatform.ANDROID -> "Android"
    DevicePlatform.UNKNOWN -> "—"
}

/** Both of these come from the platform, which already knows the user's locale and units. */
fun relativeTime(timestamp: Long): String = DateUtils
    .getRelativeTimeSpanString(timestamp, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
    .toString()

fun shortSize(context: Context, bytes: Int): String =
    Formatter.formatShortFileSize(context, bytes.toLong())
