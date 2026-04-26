package io.github.umbraproxy.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

val UmbraTypography = Typography(
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 28.sp, letterSpacing = 2.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 16.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 16.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.5.sp)
)

object UmbraColors {
    val TelegramBlue = Color(0xFF24A1DE)

    // Material 3 Dark Colors
    val BackgroundDark = Color(0xFF141414) // أسود رمادي مريح
    val SurfaceDark = Color(0xFF1E1E1E)
    val SurfaceVariantDark = Color(0xFF2C2C2C)
    val OnSurfaceVariantDark = Color(0xFFCACACA)
}

private val DarkColorScheme = darkColorScheme(
    primary = UmbraColors.TelegramBlue,
    onPrimary = Color.White,
    primaryContainer = UmbraColors.TelegramBlue.copy(alpha = 0.2f),
    onPrimaryContainer = UmbraColors.TelegramBlue,
    background = UmbraColors.BackgroundDark,
    surface = UmbraColors.SurfaceDark,
    surfaceVariant = UmbraColors.SurfaceVariantDark,
    onSurfaceVariant = UmbraColors.OnSurfaceVariantDark
)

@Composable
fun UmbraProxyTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // نجعل شريط الحالة شفاف ليتماشى مع Material 3 Edge-to-Edge
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }

    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = UmbraTypography,
        content = content
    )
}