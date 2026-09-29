package com.obsidian.apkeditor.ui.screens.mcp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.obsidian.apkeditor.app.ObsidianApp
import com.obsidian.apkeditor.mcp.ServerStatus
import com.obsidian.apkeditor.mcp.endpointUrl
import com.obsidian.apkeditor.mcp.label
import com.obsidian.apkeditor.ui.components.EndpointRow
import com.obsidian.apkeditor.ui.components.ObActionButton
import com.obsidian.apkeditor.ui.components.ObButtonKind
import com.obsidian.apkeditor.ui.components.SectionLabel
import com.obsidian.apkeditor.ui.components.StatusDot
import kotlinx.coroutines.launch

/**
 * MCP connection page: endpoint, service control. Ported from the backup UI
 * and adapted to the rebuilt container/prefs (sync reads, suspend control).
 * Overlay gate and remote endpoints are dropped — this build serves loopback
 * only, with no floating bubble.
 */
@Composable
fun McpScreen() {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as ObsidianApp
    val scope = rememberCoroutineScope()
    val status by app.container.service.statusFlow.collectAsState()
    // Prefs reads are cheap and synchronous — read fresh every composition.
    val port = app.container.prefs.servicePort
    val endPath = app.container.prefs.endpointPath

    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun ensureNotifPerm() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val running = status as? ServerStatus.Running
    val endpoint = running?.endpointUrl
        ?: "http://127.0.0.1:$port/$endPath"

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            McpCard {
                SectionLabel("Status")
                StatusDot(
                    running = running != null,
                    label = when (val s = status) {
                        is ServerStatus.Running -> "Service running — 127.0.0.1:${s.port}"
                        ServerStatus.Starting -> "Service starting…"
                        is ServerStatus.Error -> "Service error — ${s.message}"
                        ServerStatus.Stopped -> "Service stopped"
                    }
                )
                EndpointRow("MCP endpoint", endpoint)
            }
        }

        item {
            McpCard {
                SectionLabel("Service")
                when (status) {
                    is ServerStatus.Running -> ObActionButton(
                        "Stop service",
                        onClick = { scope.launch { runCatching { app.container.service.stop() } } },
                        kind = ObButtonKind.Danger, modifier = Modifier.fillMaxWidth()
                    )
                    ServerStatus.Starting -> ObActionButton(
                        "Starting…", onClick = {}, enabled = false,
                        modifier = Modifier.fillMaxWidth()
                    )
                    else -> ObActionButton(
                        "Start service",
                        onClick = {
                            ensureNotifPerm()
                            scope.launch { runCatching { app.container.service.start() } }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (status is ServerStatus.Error) {
                    Text(
                        (status as ServerStatus.Error).message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Text(
                    "A foreground service hosts the loopback endpoint and keeps it " +
                        "alive when the app is minimized, with a persistent " +
                        "notification — stop from here or from the notification.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun McpCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.22f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            content()
        }
    }
}
