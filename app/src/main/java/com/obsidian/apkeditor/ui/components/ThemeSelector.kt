package com.obsidian.apkeditor.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Sliding-pill segmented control. The indicator measures the selected cell
 * and glides to it with a spring — no static boxes.
 */
@Composable
fun <T> SlidingSegmented(
    options: List<Pair<String, T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val centers = remember { mutableStateMapOf<Int, Pair<Dp, Dp>>() }
    val selIndex = options.indexOfFirst { it.second == selected }.coerceAtLeast(0)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(scheme.surfaceContainerHigh.copy(alpha = 0.7f))
            .border(
                1.dp, scheme.primary.copy(alpha = 0.22f),
                RoundedCornerShape(18.dp),
            )
            .padding(4.dp),
    ) {
        // Gliding indicator FIRST so labels render above it.
        val (ix, iw) = centers[selIndex] ?: (0.dp to 0.dp)
        val animX by animateDpAsState(
            targetValue = ix,
            animationSpec = spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMediumLow),
            label = "segX",
        )
        val animW by animateDpAsState(
            // Width never goes bouncy: an overshoot below zero would hand a
            // negative size to layout and crash measurement.
            targetValue = iw.coerceAtLeast(0.dp),
            animationSpec = spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow),
            label = "segW",
        )
        if (iw > 0.dp) {
            Box(
                modifier = Modifier
                    .offset(x = animX)
                    .size(width = animW.coerceAtLeast(0.dp), height = 42.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                scheme.primary,
                                scheme.primary.copy(alpha = 0.75f),
                            ),
                        ),
                    ),
            )
        }
        Row {
            options.forEachIndexed { i, (label, value) ->
                val on = i == selIndex
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(42.dp)
                        .onGloballyPositioned { coords ->
                            with(density) {
                                centers[i] = coords.positionInParent().x.toDp() to
                                    coords.size.width.toDp()
                            }
                        }
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(
                            interactionSource = remember(value) { MutableInteractionSource() },
                            indication = null,
                        ) { onSelect(value) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (on) scheme.onPrimary
                        else scheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Accent orbit: dots with a spring ring that lands on the active hue. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AccentOrbit(
    options: List<Pair<Color, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // FlowRow: wraps to a second line on narrow screens instead of
    // overflowing or squeezing dots to zero width.
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        for ((color, name) in options) {
            val on = name == selected
            val scale by animateFloatAsState(
                targetValue = if (on) 1.18f else 1f,
                animationSpec = spring(
                    Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium),
                label = "dot",
            )
            val ring by animateFloatAsState(
                targetValue = if (on) 1f else 0f,
                animationSpec = spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMedium),
                label = "ring",
            )
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .scale(scale)
                    .clip(CircleShape)
                    .background(color)
                    .then(
                        if (ring > 0.02f) {
                            Modifier.border(
                                (2 * ring).dp,
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f * ring),
                                CircleShape,
                            )
                        } else {
                            Modifier.border(
                                1.dp,
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                                CircleShape,
                            )
                        }
                    )
                    .clickable(
                        interactionSource = remember(name) { MutableInteractionSource() },
                        indication = null,
                    ) { onSelect(name) }
                    .padding(6.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (on) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.95f)),
                    )
                }
            }
        }
    }
}
