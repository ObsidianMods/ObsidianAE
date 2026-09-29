package com.obsidian.apkeditor.ui.main.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.obsidian.apkeditor.R
import com.obsidian.apkeditor.app.ObsidianApp
import com.obsidian.apkeditor.mcp.ServerStatus
import com.obsidian.apkeditor.mcp.endpointUrl
import com.obsidian.apkeditor.mcp.label
import com.obsidian.apkeditor.ui.main.components.EndpointChip
import com.obsidian.apkeditor.ui.main.components.GhostButton
import com.obsidian.apkeditor.ui.main.components.GradientButton
import com.obsidian.apkeditor.ui.main.components.ObsidianCard
import com.obsidian.apkeditor.ui.main.components.SectionLabel
import com.obsidian.apkeditor.ui.main.components.StatusOrb
import kotlinx.coroutines.launch

@Composable
fun ServiceScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as ObsidianApp
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<ServerStatus>(ServerStatus.Stopped) }

    fun refresh() {
        status = runCatching { app.container.service.status() }.getOrDefault(ServerStatus.Stopped)
    }

    LaunchedEffect(Unit) { refresh() }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    val running = status is ServerStatus.Running
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(top = 28.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SectionLabel("Agent access")
        Text(
            "Loopback MCP",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
        )
        ObsidianCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusOrb(running = running)
                androidx.compose.foundation.layout.Spacer(
                    Modifier.padding(horizontal = 6.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        status.label,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (running) "Serving tools on this device only."
                        else "Start to serve tools on loopback.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            EndpointChip(
                text = status.endpointUrl,
                onCopy = {
                    if (status.endpointUrl.isNotEmpty()) {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                            as ClipboardManager
                        cm.setPrimaryClip(
                            ClipData.newPlainText("endpoint", status.endpointUrl))
                        Toast.makeText(context,
                            context.getString(R.string.copied),
                            Toast.LENGTH_SHORT).show()
                    }
                },
            )
        }
        if (running) {
            GhostButton(text = "Stop", onClick = {
                scope.launch {
                    runCatching { app.container.service.stop() }
                    refresh()
                }
            })
        } else {
            GradientButton(text = "Start agent access", onClick = {
                scope.launch {
                    runCatching { app.container.service.start() }
                    refresh()
                }
            })
        }
    }
}
