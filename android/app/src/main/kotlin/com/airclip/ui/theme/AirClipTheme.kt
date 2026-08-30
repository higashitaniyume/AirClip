package com.airclip.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** Matches `@color/airclip_accent`, so the launcher icon and the UI agree. */
private val Accent = Color(0xFF4C8DFF)
private val AccentDark = Color(0xFF1B49A8)
private val Surface = Color(0xFF0F172A)

private val LightScheme = lightColorScheme(
    primary = AccentDark,
    secondary = Accent,
    tertiary = Color(0xFF3F7C6B),
)

private val DarkScheme = darkColorScheme(
    primary = Accent,
    secondary = Color(0xFF9BC0FF),
    tertiary = Color(0xFF7FC8B4),
    background = Surface,
    surface = Surface,
)

/**
 * Material 3 with the system palette where the platform has one (Android 12+), and AirClip's own
 * blue everywhere else. No custom typography: the device default is what users expect to read.
 */
@Composable
fun AirClipTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current

    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        dark -> DarkScheme
        else -> LightScheme
    }

    MaterialTheme(colorScheme = scheme, content = content)
}
