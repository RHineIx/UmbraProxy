package io.github.umbraproxy.ui

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// 🌙 Dark Palette (Telegram Dark Blue Identity)
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF33A8E6), // Telegram-like bright blue for dark mode
    onPrimary = Color(0xFF000000),
    primaryContainer = Color(0xFF004A77),
    onPrimaryContainer = Color(0xFFD1EEFC),
    background = Color(0xFF0F0F0F),
    onBackground = Color(0xFFE0E0E0),
    surface = Color(0xFF1A1A1A),
    onSurface = Color(0xFFE0E0E0),
    surfaceVariant = Color(0xFF2C2C2C),
    onSurfaceVariant = Color(0xFFA0A0A0),
    outline = Color(0xFF33A8E6),
    error = Color(0xFFFF5252)
)

// ☀️ Light Palette (Telegram Classic Blue Identity)
private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF0088CC), // Classic Telegram Blue
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD1EEFC),
    onPrimaryContainer = Color(0xFF003355),
    background = Color(0xFFF0F2F5),
    onBackground = Color(0xFF121212),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF121212),
    surfaceVariant = Color(0xFFE0E0E0),
    onSurfaceVariant = Color(0xFF424242),
    outline = Color(0xFF0088CC),
    error = Color(0xFFD32F2F)
)

@Composable
fun UmbraProxyTheme(
    themeMode: String = "system",
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window

            // Make Status Bar & Navigation Bar transparent
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()

            // Allow drawing under system bars
            WindowCompat.setDecorFitsSystemWindows(window, false)

            // Adjust icon colors (dark icons for light theme, light icons for dark theme)
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !darkTheme
            insetsController.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}