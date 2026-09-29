package com.obsidian.apkeditor.ui.main.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.obsidian.apkeditor.app.ObsidianApp
import com.obsidian.apkeditor.ui.main.components.ObsidianCard
import com.obsidian.apkeditor.ui.main.components.SectionLabel
import com.obsidian.apkeditor.ui.theme.LocalAccents
import com.obsidian.apkeditor.work.Workspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun WorkspaceScreen() {
    val app = LocalContext.current.applicationContext as ObsidianApp
    var items by remember { mutableStateOf<List<Workspace>>(emptyList()) }
    var nonce by remember { mutableStateOf(0) }

    LaunchedEffect(nonce) {
        items = withContext(Dispatchers.IO) {
            runCatching { app.container.workspaces.list() }.getOrDefault(emptyList())
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(top = 28.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                SectionLabel("Workspaces")
                Text(
                    text = "APK bench",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
            IconButton(onClick = { nonce++ }) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(14.dp))
        if (items.isEmpty()) {
            ObsidianCard {
                EmptyGlyph()
                Spacer(Modifier.height(10.dp))
                Text("No workspaces yet", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Import flow lives in the agent tools — drop an APK in the MCP folder, then ae_apk_open it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(items, key = { it.id }) { ws ->
                    WorkspaceRow(ws)
                }
            }
        }
    }
}

@Composable
private fun WorkspaceRow(ws: Workspace) {
    val accents = LocalAccents.current
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(scheme.surface.copy(alpha = 0.85f))
            .border(1.dp, scheme.outline.copy(alpha = 0.14f), RoundedCornerShape(18.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(listOf(accents.violet, accents.teal))),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                ws.displayName.take(1).uppercase(),
                color = scheme.onPrimary,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(ws.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Text(
                ws.id,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyGlyph() {
    val accents = LocalAccents.current
    androidx.compose.foundation.Canvas(
        modifier = Modifier.size(width = 120.dp, height = 64.dp),
    ) {
        val w = size.width
        val h = size.height
        drawLine(
            brush = Brush.horizontalGradient(
                listOf(accents.violet.copy(alpha = 0.7f), accents.teal.copy(alpha = 0.7f))),
            start = androidx.compose.ui.geometry.Offset(w * 0.1f, h * 0.5f),
            end = androidx.compose.ui.geometry.Offset(w * 0.9f, h * 0.5f),
            strokeWidth = 3f,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
        drawCircle(
            color = accents.violet,
            radius = 9f,
            center = androidx.compose.ui.geometry.Offset(w * 0.28f, h * 0.5f),
        )
        drawCircle(
            color = accents.teal,
            radius = 6f,
            center = androidx.compose.ui.geometry.Offset(w * 0.66f, h * 0.5f),
        )
    }
}
