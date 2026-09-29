package com.obsidian.apkeditor.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Obsidian signature hues shared by gradients, orbs and borders. */
data class ObsidianAccents(
    val violet: Color,
    val teal: Color,
    val amber: Color,
    val crimson: Color,
)

private val DarkAccents = ObsidianAccents(
    violet = Color(0xFFB79CFF),
    teal = Color(0xFF5EEAD4),
    amber = Color(0xFFFFC86B),
    crimson = Color(0xFFFF7A90),
)

private val LightAccents = ObsidianAccents(
    violet = Color(0xFF6A4BD6),
    teal = Color(0xFF0E7C6B),
    amber = Color(0xFF9A6200),
    crimson = Color(0xFFC22744),
)

val LocalAccents = staticCompositionLocalOf { DarkAccents }

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFB79CFF),
    onPrimary = Color(0xFF241547),
    primaryContainer = Color(0xFF2E2350),
    onPrimaryContainer = Color(0xFFE6DCFF),
    secondary = Color(0xFF5EEAD4),
    background = Color(0xFF0E0C14),
    onBackground = Color(0xFFF1ECFA),
    surface = Color(0xFF16121F),
    onSurface = Color(0xFFF1ECFA),
    surfaceVariant = Color(0xFF221C30),
    onSurfaceVariant = Color(0xFFC9BFD9),
    outline = Color(0xFFB79CFF),
    error = Color(0xFFFF7A90),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF6A4BD6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6DCFF),
    onPrimaryContainer = Color(0xFF2A1A6B),
    secondary = Color(0xFF0E7C6B),
    background = Color(0xFFF6F3FA),
    onBackground = Color(0xFF1B1530),
    surface = Color.White,
    onSurface = Color(0xFF1B1530),
    surfaceVariant = Color(0xFFEAE4F5),
    onSurfaceVariant = Color(0xFF4E4365),
    outline = Color(0xFF6A4BD6),
)

private val ObsidianType = Typography(
    displaySmall = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.2.sp),
)

@Composable
fun ObsidianAETheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalAccents provides if (darkTheme) DarkAccents else LightAccents) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            typography = ObsidianType,
            content = content,
        )
    }
}
