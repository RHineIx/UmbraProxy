package io.github.umbraproxy.ui

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

val UmbraTypography = Typography(
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 32.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 22.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 16.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 12.sp)
)

private val UmbraDarkColorScheme = darkColorScheme(
    primary = Color(0xFF00E5FF),
    onPrimary = Color(0xFF000000),
    primaryContainer = Color(0xFF00B0FF).copy(alpha = 0.2f),
    onPrimaryContainer = Color(0xFF84FFFF),
    secondary = Color(0xFFB388FF),
    onSecondary = Color(0xFF000000),
    background = Color(0xFF050505),
    onBackground = Color(0xFFE0E0E0),
    surface = Color(0xFF121212),
    onSurface = Color(0xFFEEEEEE),
    surfaceVariant = Color(0xFF1E1E1E),
    onSurfaceVariant = Color(0xFFAAAAAA),
    outline = Color(0xFF333333),
    outlineVariant = Color(0xFF222222),
    error = Color(0xFFFF5252)
)

object UmbraColors {
    val ActiveGlow = Color(0xFF00E5FF)
    val InactiveGlow = Color(0xFF424242)
    val LogInfo = Color(0xFF69F0AE)
    val LogError = Color(0xFFFF5252)
    val LogWarning = Color(0xFFFFD740)
}

@Composable
fun UmbraProxyTheme(
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val context = LocalContext.current
        dynamicDarkColorScheme(context)
    } else {
        UmbraDarkColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window

            // 🚀 Suppress deprecation warnings for API 35+, maintaining backwards compatibility
            @Suppress("DEPRECATION")
            window.statusBarColor = Color.Transparent.toArgb()

            @Suppress("DEPRECATION")
            window.navigationBarColor = Color.Transparent.toArgb()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                @Suppress("DEPRECATION")
                window.isNavigationBarContrastEnforced = false
                @Suppress("DEPRECATION")
                window.isStatusBarContrastEnforced = false
            }

            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = UmbraTypography,
        content = content
    )
}