package it.stradasafe.liguria.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

object StradaColors {
    val Cyan = Color(0xFF00D9FF)
    val CyanDark = Color(0xFF0EA5E9)
    val Purple = Color(0xFF7C3AED)
    val Green = Color(0xFF10B981)
    val GreenBright = Color(0xFF34D399)
    val Amber = Color(0xFFF59E0B)
    val AmberLight = Color(0xFFFBBF24)
    val Red = Color(0xFFEF4444)
    val RedLight = Color(0xFFF87171)
    val DarkBg = Color(0xFF0A0E1A)
    val DarkSurface = Color(0xF01A2233)
    val DarkSurfaceElevated = Color(0xFF1E293B)
    val DarkBorder = Color(0x33FFFFFF)
    val LightBg = Color(0xFFF8FAFC)
    val LightSurface = Color(0xF2FFFFFF)
    val LightSurfaceElevated = Color(0xFFFFFFFF)
    val LightBorder = Color(0x1F000000)
    val TextPrimaryDark = Color(0xFFFFFFFF)
    val TextSecondaryDark = Color(0xFF94A3B8)
    val TextPrimaryLight = Color(0xFF0F172A)
    val TextSecondaryLight = Color(0xFF64748B)
}

object StradaGradients {
    val Primary = Brush.linearGradient(colors = listOf(StradaColors.Cyan, StradaColors.Purple))
    val Success = Brush.linearGradient(colors = listOf(StradaColors.Green, StradaColors.GreenBright))
    val Danger = Brush.linearGradient(colors = listOf(StradaColors.Red, StradaColors.RedLight))
    val Warning = Brush.linearGradient(colors = listOf(StradaColors.Amber, StradaColors.AmberLight))
}

object StradaShapes {
    val Small = RoundedCornerShape(12.dp)
    val Medium = RoundedCornerShape(20.dp)
    val Large = RoundedCornerShape(28.dp)
    val Pill = RoundedCornerShape(50)
}

val LocalIsDark = staticCompositionLocalOf { true }

private val DarkColorScheme = darkColorScheme(
    primary = StradaColors.Cyan,
    onPrimary = Color.Black,
    secondary = StradaColors.Purple,
    onSecondary = Color.White,
    background = StradaColors.DarkBg,
    onBackground = StradaColors.TextPrimaryDark,
    surface = StradaColors.DarkSurface,
    onSurface = StradaColors.TextPrimaryDark,
    surfaceVariant = StradaColors.DarkSurfaceElevated,
    onSurfaceVariant = StradaColors.TextSecondaryDark,
    error = StradaColors.Red,
    onError = Color.White,
)

private val LightColorScheme = lightColorScheme(
    primary = StradaColors.CyanDark,
    onPrimary = Color.White,
    secondary = StradaColors.Purple,
    onSecondary = Color.White,
    background = StradaColors.LightBg,
    onBackground = StradaColors.TextPrimaryLight,
    surface = StradaColors.LightSurface,
    onSurface = StradaColors.TextPrimaryLight,
    surfaceVariant = StradaColors.LightSurfaceElevated,
    onSurfaceVariant = StradaColors.TextSecondaryLight,
    error = StradaColors.Red,
    onError = Color.White,
)

@Composable
fun StradaSafeTheme(isDark: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalIsDark provides isDark) {
        MaterialTheme(
            colorScheme = if (isDark) DarkColorScheme else LightColorScheme,
            content = content
        )
    }
}
