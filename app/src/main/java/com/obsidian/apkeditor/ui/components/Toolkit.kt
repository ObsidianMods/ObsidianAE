package com.obsidian.apkeditor.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.obsidian.apkeditor.ui.theme.Mono
import com.obsidian.apkeditor.ui.theme.ObsidianDp
import kotlinx.coroutines.delay

/** Small uppercase section header with a trailing hairline rule. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Row(modifier.padding(top = 4.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f).height(1.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)))
    }
}

/** Live status dot + label. Color animates on state change. */
@Composable
fun StatusDot(running: Boolean, label: String, modifier: Modifier = Modifier) {
    val color by animateColorAsState(
        if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        label = "statusColor"
    )
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

/** Tool working-state badge: green verified, red failed, grey untested. */
@Composable
fun HealthDot(
    health: com.obsidian.apkeditor.tools.ToolHealth,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    val (color, label) = when (health) {
        com.obsidian.apkeditor.tools.ToolHealth.VERIFIED -> cs.primary to "Verified"
        com.obsidian.apkeditor.tools.ToolHealth.FAILED -> cs.error to "Failed"
        else -> cs.outline to "Untested"
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
    }
}

/**
 * Endpoint row: bordered container, label + mono URL + status, copy chip
 * pinned right. Chip morphs to a check for 2s after copying.
 */
@Composable
fun EndpointRow(
    label: String,
    url: String,
    status: String? = null,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2000)
            copied = false
        }
    }
    ObRow(modifier = modifier) {
        Row(
            Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(2.dp))
                Text(
                    url,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (status != null) {
                    Text(status, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(8.dp))
            CopyChip(copied = copied, onCopy = {
                (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("mcp-endpoint", url))
                copied = true
            })
        }
    }
}

/** Small bordered copy button that swaps its icon on success. */
@Composable
private fun CopyChip(copied: Boolean, onCopy: () -> Unit) {
    val border by animateColorAsState(
        if (copied) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
        label = "chipBorder"
    )
    Box(
        Modifier.clip(RoundedCornerShape(ObsidianDp.RadiusS))
            .border(1.dp, border, RoundedCornerShape(ObsidianDp.RadiusS))
            .clickable(onClick = onCopy)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        AnimatedContent(targetState = copied, label = "copySwap") { done ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (done) Icons.Filled.Check else Icons.Filled.ContentCopy,
                    contentDescription = if (done) "copied" else "copy",
                    tint = if (done) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    if (done) "Copied" else "Copy",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (done) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Key/value stat line with mono value. */
@Composable
fun StatRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = Mono),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Real memory visualization: PSS history as an area chart with min/max/current
 * labels. Renders the actual sampler output — flat when idle, spiky under load.
 */
@Composable
fun MemoryGraph(samples: List<Pair<Long, Long>>, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val onVar = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier) {
        val valid = samples.filter { it.second >= 0 }
        if (valid.size < 2) {
            Box(Modifier.fillMaxWidth().height(96.dp), contentAlignment = Alignment.Center) {
                Text("collecting samples…", style = MaterialTheme.typography.bodySmall, color = onVar)
            }
            return
        }
        val min = valid.minOf { it.second }.toFloat()
        val max = valid.maxOf { it.second }.toFloat()
        val span = (max - min).coerceAtLeast(1f)
        Box(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(ObsidianDp.RadiusM))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.22f), RoundedCornerShape(ObsidianDp.RadiusM))
                .padding(12.dp)
        ) {
            Canvas(Modifier.fillMaxWidth().height(110.dp)) {
                val w = size.width
                val h = size.height
                // gridlines
                for (i in 1..3) {
                    val y = h * i / 4f
                    drawLine(grid, start = androidx.compose.ui.geometry.Offset(0f, y),
                        end = androidx.compose.ui.geometry.Offset(w, y), strokeWidth = 1f)
                }
                val stepX = if (valid.size > 1) w / (valid.size - 1) else 0f
                val pts = valid.mapIndexed { i, s ->
                    androidx.compose.ui.geometry.Offset(i * stepX, h - ((s.second - min) / span) * (h - 8f) - 4f)
                }
                val area = Path().apply {
                    moveTo(pts.first().x, h)
                    pts.forEach { lineTo(it.x, it.y) }
                    lineTo(pts.last().x, h)
                    close()
                }
                drawPath(
                    area,
                    Brush.verticalGradient(
                        listOf(primary.copy(alpha = 0.35f), primary.copy(alpha = 0.04f))
                    )
                )
                val line = Path().apply {
                    moveTo(pts.first().x, pts.first().y)
                    pts.drop(1).forEach { lineTo(it.x, it.y) }
                }
                drawPath(line, primary, style = Stroke(width = 2.5f))
                // glow dot on the live point
                drawCircle(primary.copy(alpha = 0.25f), radius = 9f, center = pts.last())
                drawCircle(primary, radius = 4f, center = pts.last())
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("min ${min.toLong()} KB", style = MaterialTheme.typography.labelSmall.copy(fontFamily = Mono), color = onVar)
            Text("now ${valid.last().second} KB", style = MaterialTheme.typography.labelSmall.copy(fontFamily = Mono), color = onVar)
            Text("max ${max.toLong()} KB", style = MaterialTheme.typography.labelSmall.copy(fontFamily = Mono), color = onVar)
        }
    }
}

/** Generic settings row: title + subtitle left, arbitrary control right. */@Composable
fun SettingRow(
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    control: @Composable (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val inner: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium)
                if (subtitle != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(subtitle, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (control != null) {
                Spacer(Modifier.width(12.dp))
                control()
            }
        }
    }
    if (onClick != null) {
        Box(modifier.clickable(onClick = onClick)) { inner() }
    } else {
        Box(modifier) { inner() }
    }
}

/* ------------------------------------------------------------------ */
/* Custom widget set — the app's own visual identity.                 */
/* Hairline borders over flat tonal fills, one radius system,         */
/* mono voice for machine text, spring motion, no default-widget look. */
/* ------------------------------------------------------------------ */

/**
 * Bordered content container: hairline accent-tinted border on a quiet
 * surface. The default block for rows, groups and panels.
 */
@Composable
fun ObRow(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(ObsidianDp.RadiusM)
    val box = Modifier.fillMaxWidth()
        .clip(shape)
        .background(MaterialTheme.colorScheme.surfaceContainerLow)
        .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.22f), shape)
    if (onClick != null) {
        val src = remember { MutableInteractionSource() }
        Box(box.then(modifier).clickable(interactionSource = src, indication = null, onClick = onClick)) { content() }
    } else {
        Box(box.then(modifier)) { content() }
    }
}

enum class ObButtonKind { Primary, Danger, Ghost }

/**
 * Full-character action button: fixed 48dp height, one radius, semibold
 * label. Primary / Danger / Ghost(hairline) variants.
 */
@Composable
fun ObActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ObButtonKind = ObButtonKind.Primary,
    enabled: Boolean = true,
    icon: ImageVector? = null
) {
    val cs = MaterialTheme.colorScheme
    val (bg, fg, border) = when (kind) {
        ObButtonKind.Primary -> Triple(cs.primary, cs.onPrimary, Color.Transparent)
        ObButtonKind.Danger -> Triple(cs.errorContainer, cs.onErrorContainer, Color.Transparent)
        ObButtonKind.Ghost -> Triple(Color.Transparent, cs.primary, cs.primary.copy(alpha = 0.35f))
    }
    val bgAnim by animateColorAsState(if (enabled) bg else cs.surfaceContainerHigh, label = "obBtnBg")
    val shape = RoundedCornerShape(ObsidianDp.RadiusM)
    Box(
        modifier
            .height(48.dp)
            .clip(shape)
            .background(bgAnim)
            .border(1.dp, border, shape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, tint = if (enabled) fg else cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text,
                style = MaterialTheme.typography.labelLarge,
                color = if (enabled) fg else cs.onSurfaceVariant
            )
        }
    }
}

/**
 * Custom toggle: spring-loaded thumb on a track that fills with the accent.
 * Same contract as Switch, none of its look.
 */
@Composable
fun ObSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    val track by animateColorAsState(
        if (checked) cs.primary else cs.surfaceContainerHigh, label = "obSwTrack"
    )
    val thumbOffset by animateDpAsState(
        if (checked) 22.dp else 2.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "obSwThumb"
    )
    Box(
        modifier.size(width = 50.dp, height = 30.dp)
            .clip(CircleShape)
            .background(track)
            .border(1.dp, if (checked) Color.Transparent else cs.primary.copy(alpha = 0.35f), CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Checkbox,
                onClick = { onCheckedChange(!checked) }
            )
    ) {
        Box(
            Modifier.offset(x = thumbOffset).align(Alignment.CenterStart)
                .size(24.dp).clip(CircleShape)
                .background(if (checked) cs.onPrimary else cs.onSurfaceVariant)
        )
    }
}

/** Text field in the house style: hairline border, one radius, mono option. */
@Composable
fun ObTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    prefix: @Composable (() -> Unit)? = null,
    supporting: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    singleLine: Boolean = true,
    mono: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        prefix = prefix,
        supportingText = supporting,
        isError = isError,
        singleLine = singleLine,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        textStyle = if (mono) MaterialTheme.typography.bodyMedium.copy(fontFamily = Mono)
        else MaterialTheme.typography.bodyMedium,
        shape = RoundedCornerShape(ObsidianDp.RadiusM),
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f),
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            focusedLabelColor = MaterialTheme.colorScheme.primary,
            cursorColor = MaterialTheme.colorScheme.primary,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        modifier = modifier.fillMaxWidth()
    )
}

/** Segmented single-choice chips with animated selection fill. */
@Composable
fun <T> ObSegmentRow(
    options: List<Pair<String, T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((label, v) in options) {
            val sel = v == selected
            val bg by animateColorAsState(
                if (sel) MaterialTheme.colorScheme.primary
                else Color.Transparent,
                label = "obSeg"
            )
            Box(
                Modifier.weight(1f).height(40.dp)
                    .clip(RoundedCornerShape(ObsidianDp.RadiusS))
                    .background(bg)
                    .border(
                        1.dp,
                        if (sel) Color.Transparent else MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                        RoundedCornerShape(ObsidianDp.RadiusS)
                    )
                    .clickable { onSelect(v) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (sel) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Tool row: left accent edge (lit when enabled), mono name, dimmed body
 * when off, custom switch. Tap opens details.
 */
@Composable
fun ToolRow(
    name: String,
    title: String,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    val edge by animateColorAsState(
        if (enabled) cs.primary else Color.Transparent, label = "toolEdge"
    )
    val shape = RoundedCornerShape(ObsidianDp.RadiusM)
    Row(
        modifier.fillMaxWidth()
            .clip(shape)
            .background(cs.surfaceContainerLow)
            .border(1.dp, cs.primary.copy(alpha = 0.22f), shape)
            .clickable(onClick = onOpen)
            .padding(start = 0.dp, top = 13.dp, bottom = 13.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(3.dp).height(34.dp).clip(RoundedCornerShape(0.dp, 3.dp, 3.dp, 0.dp)).background(edge))
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(
                name,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = Mono),
                color = if (enabled) cs.onSurface else cs.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                title,
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(12.dp))
        ObSwitch(checked = enabled, onCheckedChange = onToggle)
    }
}

/** Centered empty state: icon, title, hint. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    hint: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier.fillMaxWidth().padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.size(56.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/* ------------------------------------------------------------------ */
/* Reusable cards — bordered containers for grouped content.           */
/* ------------------------------------------------------------------ */

/**
 * Bordered card: 1dp accent-tinted hairline, 14dp corners,
 * surfaceContainerLow fill, 16dp inner padding. Optional tap handler.
 */
@Composable
fun ObCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(14.dp)
    val base = Modifier.fillMaxWidth()
        .clip(shape)
        .background(MaterialTheme.colorScheme.surfaceContainerLow)
        .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.22f), shape)
    if (onClick != null) {
        val src = remember { MutableInteractionSource() }
        Box(
            base.then(modifier)
                .clickable(interactionSource = src, indication = null, onClick = onClick)
                .padding(16.dp)
        ) { content() }
    } else {
        Box(base.then(modifier).padding(16.dp)) { content() }
    }
}

/**
 * Row with icon in a tinted circle, small title, mono value.
 * Whole tile is clickable when onClick is provided.
 */
@Composable
fun InfoTile(
    icon: ImageVector,
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    ObCard(modifier = modifier, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    value,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = Mono),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * ObCard with a small header (title + optional subtitle) then content.
 */
@Composable
fun SectionCard(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    ObCard(modifier = modifier) {
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}
