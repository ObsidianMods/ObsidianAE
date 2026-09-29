package com.obsidian.apkeditor.ui.main.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.obsidian.apkeditor.ui.theme.LocalAccents
import androidx.compose.material3.MaterialTheme

/**
 * Ambient backdrop: deep base wash plus two blurred radial glows that drift
 * with nothing (static, zero animation cost) — violet crown, teal abyss.
 */
@Composable
fun GlowBackdrop(modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val accents = LocalAccents.current
    Canvas(modifier = modifier.fillMaxSize()) {
        drawRect(scheme.background)
        // Violet crown, top-left bleeding in.
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    accents.violet.copy(alpha = 0.22f),
                    accents.violet.copy(alpha = 0.05f),
                    Color.Transparent,
                ),
                center = Offset(size.width * 0.12f, -size.height * 0.05f),
                radius = size.width * 0.95f,
            ),
            radius = size.width * 0.95f,
            center = Offset(size.width * 0.12f, -size.height * 0.05f),
        )
        // Teal abyss, bottom-right.
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    accents.teal.copy(alpha = 0.13f),
                    accents.teal.copy(alpha = 0.03f),
                    Color.Transparent,
                ),
                center = Offset(size.width * 0.95f, size.height * 1.02f),
                radius = size.width * 0.9f,
            ),
            radius = size.width * 0.9f,
            center = Offset(size.width * 0.95f, size.height * 1.02f),
        )
        // Hairline horizon two-thirds down.
        drawLine(
            brush = Brush.horizontalGradient(
                colors = listOf(
                    Color.Transparent,
                    accents.violet.copy(alpha = 0.25f),
                    Color.Transparent,
                ),
            ),
            start = Offset(0f, size.height * 0.62f),
            end = Offset(size.width, size.height * 0.62f),
            strokeWidth = 2f,
        )
    }
}
