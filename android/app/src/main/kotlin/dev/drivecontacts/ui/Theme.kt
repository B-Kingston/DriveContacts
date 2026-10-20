package dev.drivecontacts.ui

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

private val LightColors = lightColorScheme(
    primary = Color(0xFF1E5B4F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB7F0E0),
    onPrimaryContainer = Color(0xFF00201A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7BD6C2),
    onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF00504A),
    onPrimaryContainer = Color(0xFFB7F0E0),
)

/** Material 3 theme. It uses dynamic color on Android 12 and later. */
@Composable
fun DriveContactsTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
