package dev.glinesb.heygrok.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Branded fallback (Android 10–11, or dynamic color off): monochrome "Grok" look with a blue accent.
private val LightScheme = lightColorScheme(
    primary = Color(0xFF1D1B20), onPrimary = Color.White,
    secondary = Color(0xFF3B5BDB), onSecondary = Color.White,
    background = Color(0xFFFFFBFE), surface = Color(0xFFFFFBFE),
)
private val DarkScheme = darkColorScheme(
    primary = Color(0xFFE6E0E9), onPrimary = Color(0xFF1D1B20),
    secondary = Color(0xFF9DB2FF), onSecondary = Color(0xFF0B1A4D),
    background = Color(0xFF141218), surface = Color(0xFF141218),
)

/** Follows the system light/dark setting; Material You dynamic color on Android 12+. */
@Composable
fun HeyGrokTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val scheme: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme -> DarkScheme
        else -> LightScheme
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
