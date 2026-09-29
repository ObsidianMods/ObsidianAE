package com.obsidian.apkeditor.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background

sealed interface BarIcon {
    data class Vec(val v: ImageVector) : BarIcon
    data class Res(@DrawableRes val id: Int) : BarIcon
}

data class CurveTab(val route: String, val label: String, val icon: BarIcon)

/**
 * Curved bottom navigation: the bar's top edge scoops into a cradle under
 * the selected tab, and the cradle glides between tabs with a spring.
 * Tabs are weight-distributed, so new destinations just append to the list.
 */
@Composable
fun CurvedBottomBar(
    tabs: List<CurveTab>,
    selectedRoute: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    val selIdx = tabs.indexOfFirst { it.route == selectedRoute }.coerceAtLeast(0)
    val dipX by animateFloatAsState(
        targetValue = selIdx.toFloat(),
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "dipGlide"
    )

    BoxWithConstraints(
        modifier.fillMaxWidth().navigationBarsPadding().height(88.dp)
    ) {
        val density = LocalDensity.current
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val itemW = if (tabs.isEmpty()) w else w / tabs.size
        val cx = (dipX + 0.5f) * itemW
        val hw = itemW * 0.38f
        val depth = with(density) { 26.dp.toPx() }
        val corner = with(density) { 22.dp.toPx() }

        Canvas(Modifier.fillMaxSize()) {
            val path = Path().apply {
                moveTo(0f, h)
                lineTo(0f, corner)
                quadraticTo(0f, 0f, corner, 0f)
                lineTo(cx - hw, 0f)
                // down into the cradle (horizontal tangents both ends)
                cubicTo(
                    cx - hw * 0.45f, 0f,
                    cx - hw * 0.65f, depth,
                    cx, depth
                )
                // back up, mirrored
                cubicTo(
                    cx + hw * 0.65f, depth,
                    cx + hw * 0.45f, 0f,
                    cx + hw, 0f
                )
                lineTo(w - corner, 0f)
                quadraticTo(w, 0f, w, corner)
                lineTo(w, h)
                close()
            }
            drawPath(path, cs.surfaceContainerLow)
        }

        Row(Modifier.fillMaxSize()) {
            tabs.forEachIndexed { i, t ->
                val sel = i == selIdx
                val lift by animateDpAsState(
                    if (sel) (-6).dp else 0.dp,
                    animationSpec = spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow),
                    label = "tabLift"
                )
                Column(
                    Modifier.weight(1f).fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Tab,
                            onClick = { onSelect(t.route) }
                        )
                        .offset(y = lift),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    val fg = if (sel) cs.onPrimaryContainer else cs.onSurfaceVariant
                    if (sel) {
                        Box(
                            Modifier.size(46.dp).clip(CircleShape).background(cs.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            BarGlyph(t.icon, d = t.label, tintFg = fg, size = 24.dp)
                        }
                    } else {
                        BarGlyph(t.icon, d = t.label, tintFg = fg, size = 22.dp)
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(t.label, fontSize = 10.sp, color = fg, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun BarGlyph(icon: BarIcon, d: String, tintFg: Color, size: Dp) {
    when (icon) {
        is BarIcon.Vec -> Icon(icon.v, d, tint = tintFg, modifier = Modifier.size(size))
        is BarIcon.Res -> Icon(painterResource(icon.id), d, tint = tintFg, modifier = Modifier.size(size))
    }
}
