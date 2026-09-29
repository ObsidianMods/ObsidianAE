package com.obsidian.apkeditor.ui.main.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.obsidian.apkeditor.ui.theme.LocalAccents

/** Frosted panel: gradient wash, hairline violet border, deep corners. */
@Composable
fun ObsidianCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        scheme.surface.copy(alpha = 0.92f),
                        scheme.surfaceVariant.copy(alpha = 0.55f),
                    ),
                ),
            )
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        scheme.outline.copy(alpha = 0.35f),
                        scheme.outline.copy(alpha = 0.08f),
                    ),
                ),
                shape = RoundedCornerShape(24.dp),
            )
            .padding(18.dp),
        content = content,
    )
}

/** Eyebrow label with a short gradient rule. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    val accents = LocalAccents.current
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(width = 18.dp, height = 2.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(accents.violet, Color.Transparent)),
                    CircleShape,
                ),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Pulsing-free status orb: layered radial glow + core dot. */
@Composable
fun StatusOrb(running: Boolean, size: Dp = 54.dp, modifier: Modifier = Modifier) {
    val accents = LocalAccents.current
    val core = if (running) accents.teal else MaterialTheme.colorScheme.onSurfaceVariant
    val glow = if (running) accents.teal else accents.crimson
    val alpha by animateFloatAsState(
        targetValue = if (running) 1f else 0.55f,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "orb",
    )
    Canvas(modifier = modifier
        .size(size)
        .alpha(alpha)) {
        drawCircle(
            brush = Brush.radialGradient(
                listOf(glow.copy(alpha = 0.55f), Color.Transparent),
                center = center, radius = size.toPx() * 0.5f),
            radius = size.toPx() * 0.5f,
        )
        drawCircle(
            brush = Brush.radialGradient(
                listOf(Color.White.copy(alpha = 0.9f), core),
                center = center, radius = size.toPx() * 0.22f),
            radius = size.toPx() * 0.22f,
        )
        // Orbit arc for craft.
        drawArc(
            color = glow.copy(alpha = 0.8f),
            startAngle = -60f, sweepAngle = 220f, useCenter = false,
            topLeft = Offset(size.toPx() * 0.12f, size.toPx() * 0.12f),
            size = androidx.compose.ui.geometry.Size(
                size.toPx() * 0.76f, size.toPx() * 0.76f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = size.toPx() * 0.045f, cap = StrokeCap.Round),
        )
    }
}

/** Monospace endpoint chip with copy affordance. */
@Composable
fun EndpointChip(text: String, onCopy: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(scheme.surfaceVariant.copy(alpha = 0.7f))
            .border(1.dp, scheme.outline.copy(alpha = 0.18f), RoundedCornerShape(14.dp))
            .clickable(onClick = onCopy)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text.ifEmpty { "—" },
            style = androidx.compose.ui.text.TextStyle(
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                fontSize = androidx.compose.ui.unit.TextUnit.Unspecified,
            ),
            color = scheme.onSurfaceVariant,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

/** Primary gradient action. */
@Composable
fun GradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val accents = LocalAccents.current
    val radius by animateDpAsState(
        targetValue = 16.dp,
        animationSpec = spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium),
        label = "btn",
    )
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = RoundedCornerShape(radius),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = Color.Transparent,
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        listOf(accents.violet, MaterialTheme.colorScheme.secondary)),
                    RoundedCornerShape(radius),
                )
                .padding(vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Ghost action. */
@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = RoundedCornerShape(16.dp),
    ) {
        Text(text)
    }
}

/** Small stat tile with gradient numeral. */
@Composable
fun StatTile(
    value: String,
    caption: String,
    brush: Brush,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .border(
                1.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = 0.14f),
                RoundedCornerShape(18.dp),
            )
            .padding(14.dp),
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall.copy(brush = brush),
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = caption,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Evenly spaced row helper. */
@Composable
fun TileRow(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}
