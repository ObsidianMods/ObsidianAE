package com.obsidian.apkeditor.ui.screens.mcp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.obsidian.apkeditor.app.ObsidianApp
import com.obsidian.apkeditor.mcp.ServerStatus
import com.obsidian.apkeditor.mcp.endpointUrl
import com.obsidian.apkeditor.mcp.label
import com.obsidian.apkeditor.mcp.overlay.OverlayPermission
import com.obsidian.apkeditor.system.GrantRequests
import com.obsidian.apkeditor.ui.components.EndpointRow
import com.obsidian.apkeditor.ui.components.ObActionButton
import com.obsidian.apkeditor.ui.components.ObButtonKind
import com.obsidian.apkeditor.ui.components.ObSwitch
import com.obsidian.apkeditor.ui.components.SectionLabel
import com.obsidian.apkeditor.ui.components.StatusDot
import kotlinx.coroutines.launch

/**
 * MCP connection page: endpoint, service control, floating assistant toggle.
 * Ported from the backup UI and adapted to the rebuilt container/prefs
 * (sync reads, suspend control). Loopback only; no remote endpoints.
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

    // Overlay gate: no system dialog exists for this permission, so the start
    // flow sends the user to Settings and resumes when they come back.
    var pendingOverlayStart by remember { mutableStateOf(false) }
    val overlayLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (OverlayPermission.granted(ctx)) {
            pendingOverlayStart = false
            scope.launch { runCatching { app.container.service.start() } }
        }
    }

    fun startWithOverlayGate() {
        ensureNotifPerm()
        if (OverlayPermission.granted(ctx)) {
            scope.launch { runCatching { app.container.service.start() } }
        } else {
            pendingOverlayStart = true
            overlayLauncher.launch(OverlayPermission.requestIntent(ctx))
        }
    }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && pendingOverlayStart &&
                OverlayPermission.granted(ctx)
            ) {
                pendingOverlayStart = false
                scope.launch { runCatching { app.container.service.start() } }
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
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
                        onClick = { startWithOverlayGate() },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (pendingOverlayStart) {
                    Text(
                        "Enable “Display over other apps” for Obsidian AE, then return here — " +
                            "the service starts automatically.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
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

        item {
            McpCard {
                SectionLabel("Floating assistant")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Bubble overlay", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Draggable MCP bubble with a quick menu. Needs “Display over other apps”.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    var overlayOn by remember {
                        mutableStateOf(app.container.prefs.showOverlay)
                    }
                    ObSwitch(
                        checked = overlayOn,
                        onCheckedChange = { checked ->
                            if (checked && !OverlayPermission.granted(ctx)) {
                                pendingOverlayStart = true
                                overlayLauncher.launch(OverlayPermission.requestIntent(ctx))
                            }
                            overlayOn = checked
                            app.container.prefs.showOverlay = checked
                            scope.launch {
                                runCatching { app.container.service.refreshOverlay() }
                            }
                        }
                    )
                }
            }
        }

        item {
            McpCard {
                SectionLabel("Storage")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Folder access", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "MCP folder plus any folders you grant. Extra folders are addressed as @Alias/path.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    ObActionButton(
                        "Manage",
                        onClick = { GrantRequests.open() },
                        kind = ObButtonKind.Ghost,
                    )
                }
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
