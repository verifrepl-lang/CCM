package com.crabcode.ide.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Visual language of CrabCode — colors are taken verbatim from the desktop
 * themes.ts (crab-dark / crab-light).
 */
object CrabColors {
    // crab-dark
    val DARK_BG = Color(0xFF0D0D0D)
    val DARK_CHROME = Color(0xFF171717)
    val DARK_SURFACE = Color(0xFF121212)
    val DARK_RAISED = Color(0xFF1E1E1E)
    val DARK_OVERLAY = Color(0xFF2A2A2A)
    val DARK_BORDER = Color(0xFF2B2B2B)
    val DARK_BORDER_STRONG = Color(0xFF3B3B3B)
    val DARK_TEXT = Color(0xFFECECEC)
    val DARK_TEXT_SEC = Color(0xFFA6A6A6)
    val DARK_TEXT_MUTED = Color(0xFF6E6E6E)
    val DARK_ACCENT = Color(0xFF10A37F)
    val DARK_ACCENT_HOVER = Color(0xFF1CC09A)
    val DARK_SUCCESS = Color(0xFF10A37F)
    val DARK_DANGER = Color(0xFFF0524A)
    val DARK_WARNING = Color(0xFFD8A13A)

    // crab-light
    val LIGHT_BG = Color(0xFFFCFCFC)
    val LIGHT_CHROME = Color(0xFFF2F2F2)
    val LIGHT_SURFACE = Color(0xFFFFFFFF)
    val LIGHT_RAISED = Color(0xFFF5F5F5)
    val LIGHT_OVERLAY = Color(0xFFEBEBEB)
    val LIGHT_BORDER = Color(0xFFE0E0E0)
    val LIGHT_BORDER_STRONG = Color(0xFFC9C9C9)
    val LIGHT_TEXT = Color(0xFF1F1F1F)
    val LIGHT_TEXT_SEC = Color(0xFF5F5F5F)
    val LIGHT_TEXT_MUTED = Color(0xFF8B8B8B)
    val LIGHT_ACCENT = Color(0xFF0D8A6C)
    val LIGHT_ACCENT_HOVER = Color(0xFF10A37F)
    val LIGHT_SUCCESS = Color(0xFF1A7F37)
    val LIGHT_DANGER = Color(0xFFD1242F)
    val LIGHT_WARNING = Color(0xFF9A6700)
}

private val DarkScheme: ColorScheme = darkColorScheme(
    primary = CrabColors.DARK_ACCENT,
    onPrimary = Color.White,
    primaryContainer = CrabColors.DARK_RAISED,
    onPrimaryContainer = CrabColors.DARK_TEXT,
    secondary = CrabColors.DARK_TEXT_SEC,
    onSecondary = Color.Black,
    background = CrabColors.DARK_BG,
    onBackground = CrabColors.DARK_TEXT,
    surface = CrabColors.DARK_SURFACE,
    onSurface = CrabColors.DARK_TEXT,
    surfaceVariant = CrabColors.DARK_RAISED,
    onSurfaceVariant = CrabColors.DARK_TEXT_SEC,
    outline = CrabColors.DARK_BORDER,
    outlineVariant = CrabColors.DARK_BORDER_STRONG,
    error = CrabColors.DARK_DANGER,
    onError = Color.White
)

private val LightScheme: ColorScheme = lightColorScheme(
    primary = CrabColors.LIGHT_ACCENT,
    onPrimary = Color.White,
    primaryContainer = CrabColors.LIGHT_RAISED,
    onPrimaryContainer = CrabColors.LIGHT_TEXT,
    secondary = CrabColors.LIGHT_TEXT_SEC,
    background = CrabColors.LIGHT_BG,
    onBackground = CrabColors.LIGHT_TEXT,
    surface = CrabColors.LIGHT_SURFACE,
    onSurface = CrabColors.LIGHT_TEXT,
    surfaceVariant = CrabColors.LIGHT_RAISED,
    onSurfaceVariant = CrabColors.LIGHT_TEXT_SEC,
    outline = CrabColors.LIGHT_BORDER,
    outlineVariant = CrabColors.LIGHT_BORDER_STRONG,
    error = CrabColors.LIGHT_DANGER,
    onError = Color.White
)

val CrabTypography = Typography(
    titleLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.Default, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.Default, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 11.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 12.sp)
)

val CodeStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 13.sp,
    lineHeight = 19.sp
)

@Composable
fun CrabTheme(themeId: String = "crab-dark", content: @Composable () -> Unit) {
    val dark = when (themeId) {
        "crab-dark" -> true
        "crab-light" -> false
        else -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        typography = CrabTypography,
        content = content
    )
}
