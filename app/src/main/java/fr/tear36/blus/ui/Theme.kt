package fr.tear36.blus.ui

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

val NaolibGreen = Color(0xFF00A05C)
val NaolibGreenDark = Color(0xFF007A45)
val BlusBackground = Color(0xFF0E1418)
val BlusSurface = Color(0xFF182027)
val BlusSurfaceHigh = Color(0xFF222C34)
val BlusOnSurface = Color(0xFFE8F0F6)
val BlusMuted = Color(0xFF93A4B3)

private val DarkColors = darkColorScheme(
    primary = NaolibGreen,
    onPrimary = Color.White,
    primaryContainer = NaolibGreenDark,
    onPrimaryContainer = Color.White,
    secondary = Color(0xFF4FC3F7),
    background = BlusBackground,
    onBackground = BlusOnSurface,
    surface = BlusSurface,
    onSurface = BlusOnSurface,
    surfaceVariant = BlusSurfaceHigh,
    onSurfaceVariant = BlusMuted,
    error = Color(0xFFFF6B6B),
)

private val LightColors = lightColorScheme(
    primary = NaolibGreenDark,
    onPrimary = Color.White,
    secondary = Color(0xFF0277BD),
    background = Color(0xFFF7F9FA),
    onBackground = Color(0xFF11181C),
    surface = Color.White,
    onSurface = Color(0xFF11181C),
    surfaceVariant = Color(0xFFE8EEF2),
    onSurfaceVariant = Color(0xFF4A5A66),
)

private val BlusTypography = Typography(
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    labelLarge = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun BlusTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colors.background.toArgb()
            window.navigationBarColor = colors.background.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }
    MaterialTheme(colorScheme = colors, typography = BlusTypography, content = content)
}