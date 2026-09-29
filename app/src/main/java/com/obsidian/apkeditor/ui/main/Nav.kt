package com.obsidian.apkeditor.ui.main

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.obsidian.apkeditor.ui.theme.LocalAccents

enum class Tab(val icon: ImageVector) {
    Home(Icons.Filled.Home),
    Workspace(Icons.Filled.Folder),
    Service(Icons.Filled.PlayArrow),
    Settings(Icons.Filled.Settings),
}

/**
 * Floating pill navigator: translucent shell, hairline border, and a spring
 * dot that glides under the active glyph.
 */
@Composable
fun ObsidianBottomBar(
    current: Tab,
    onSelect: (Tab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val accents = LocalAccents.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 22.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(28.dp))
                .background(scheme.surface.copy(alpha = 0.82f))
                .border(
                    1.dp,
                    Brush.horizontalGradient(
                        listOf(
                            accents.violet.copy(alpha = 0.35f),
                            scheme.outline.copy(alpha = 0.08f),
                            accents.teal.copy(alpha = 0.3f),
                        ),
                    ),
                    RoundedCornerShape(28.dp),
                )
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            for (tab in Tab.entries) {
                val selected = tab == current
                val dot by animateDpAsState(
                    targetValue = if (selected) 18.dp else 0.dp,
                    animationSpec = spring(
                        Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium),
                    label = "dot",
                )
                val interaction = remember(tab) { MutableInteractionSource() }
                androidx.compose.foundation.layout.Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .clickable(
                            interactionSource = interaction,
                            indication = null,
                        ) { onSelect(tab) }
                        .padding(horizontal = 18.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = tab.name,
                        tint = if (selected) accents.violet
                        else scheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.size(24.dp),
                    )
                    Box(
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .size(width = dot, height = 3.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.horizontalGradient(
                                    listOf(accents.violet, accents.teal)),
                            ),
                    )
                }
            }
        }
    }
}

/** Tint helper for selected glyphs (kept out of the bar for previews). */
@Composable
fun tabTint(selected: Boolean): Color =
    if (selected) LocalAccents.current.violet
    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
