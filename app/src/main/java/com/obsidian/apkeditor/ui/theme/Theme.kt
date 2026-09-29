package com.obsidian.apkeditor.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.obsidian.apkeditor.mcp.Accent
import com.obsidian.apkeditor.mcp.ThemeMode

/** Monospace for endpoints, logs, ids — the developer-tool voice. */
val Mono = FontFamily.Monospace

/** Accent triple: bright primary, on-primary, and a tinted container surface. */
private data class AccentSet(val primary: Color, val onPrimary: Color, val container: Color)

private fun accentColors(accent: Accent, dark: Boolean): AccentSet = when (accent) {
    Accent.VIOLET -> if (dark) AccentSet(Color(0xFFC4A8FF), Color(0xFF2A1A6B), Color(0xFF35245A))
    else AccentSet(Color(0xFF6A4BD6), Color.White, Color(0xFFE5DAFF))
    Accent.TEAL -> if (dark) AccentSet(Color(0xFF7FD8CC), Color(0xFF0B3B36), Color(0xFF143D38))
    else AccentSet(Color(0xFF0E7C6F), Color.White, Color(0xFFBDEEE6))
    Accent.AMBER -> if (dark) AccentSet(Color(0xFFFFC46B), Color(0xFF4A2C00), Color(0xFF4E3408))
    else AccentSet(Color(0xFFB26A00), Color.White, Color(0xFFFFE3B3))
    Accent.CRIMSON -> if (dark) AccentSet(Color(0xFFFF9AA6), Color(0xFF5B1220), Color(0xFF5A1624))
    else AccentSet(Color(0xFFC22744), Color.White, Color(0xFFFFD3DB))
}

private val ObsidianTypography = Typography(
    headlineMedium = TextStyle(fontSize = 22.sp, lineHeight = 28.sp),
    headlineSmall = TextStyle(fontSize = 18.sp, lineHeight = 24.sp),
    titleMedium = TextStyle(fontSize = 15.sp, lineHeight = 20.sp),
    titleSmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 15.sp)
)

/** Rail width, radii and hairline used across the toolkit UI. */
object ObsidianDp {
    val Rail = 60.dp
    val RadiusS = 8.dp
    val RadiusM = 12.dp
    val RadiusL = 16.dp
}

@Composable
fun ObsidianAETheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    accent: Accent = Accent.VIOLET,
    uiScale: Float = 1f,
    content: @Composable () -> Unit
) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val (primary, onPrimary) = accentColors(accent, dark).let { it.primary to it.onPrimary }
    val container = accentColors(accent, dark).container
    val scheme = if (dark) darkColorScheme(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = container,
        onPrimaryContainer = primary,
        secondary = primary.copy(alpha = 0.85f),
        background = Color(0xFF121016),
        surface = Color(0xFF17141C),
        surfaceVariant = Color(0xFF221E29),
        surfaceContainerLowest = Color(0xFF0E0C11),
        surfaceContainerLow = Color(0xFF17141C),
        surfaceContainer = Color(0xFF1E1A24),
        surfaceContainerHigh = Color(0xFF262128),
        outline = Color(0xFF3A3444),
        outlineVariant = Color(0xFF2A2632),
        onBackground = Color(0xFFE8E2F2),
        onSurface = Color(0xFFE8E2F2),
        onSurfaceVariant = Color(0xFFB9B0C9),
        error = Color(0xFFFF9AA6)
    ) else lightColorScheme(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = container,
        onPrimaryContainer = primary,
        secondary = primary,
        background = Color(0xFFF6F3FA),
        surface = Color(0xFFFFFFFF),
        surfaceVariant = Color(0xFFEDE8F4),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFF6F3FA),
        surfaceContainer = Color(0xFFEFEAF5),
        surfaceContainerHigh = Color(0xFFE6DFEE),
        outline = Color(0xFFD5CBE0),
        outlineVariant = Color(0xFFE3DAEE),
        onBackground = Color(0xFF1D1A24),
        onSurface = Color(0xFF1D1A24),
        onSurfaceVariant = Color(0xFF5B5468)
    )

    // Edge-to-edge status bar matching the surface.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = scheme.surface.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !dark
            if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        }
    }

    // UI scale: scale dp density so S/M/L layouts actually resize.
    val base = LocalDensity.current
    val scaled = Density(density = base.density * uiScale.coerceIn(0.85f, 1.3f), fontScale = base.fontScale)

    CompositionLocalProvider(LocalDensity provides scaled) {
        MaterialTheme(colorScheme = scheme, typography = ObsidianTypography, content = content)
    }
}
