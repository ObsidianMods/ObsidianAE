package com.obsidian.apkeditor.ui.screens.mcp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.obsidian.apkeditor.app.ObsidianApp
import com.obsidian.apkeditor.tools.Capability
import com.obsidian.apkeditor.tools.ToolHealth
import com.obsidian.apkeditor.ui.components.HealthDot
import com.obsidian.apkeditor.ui.components.ObActionButton
import com.obsidian.apkeditor.ui.components.ObButtonKind
import com.obsidian.apkeditor.ui.components.ObCard
import com.obsidian.apkeditor.ui.components.ObSwitch
import com.obsidian.apkeditor.ui.components.SectionLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class CapGroup(
    val id: Capability,
    val title: String,
    val description: String,
    val category: String,
)

private val GROUPS = listOf(
    CapGroup(Capability.APK, "APK", "Open, list, inspect and close workspaces.", "APK"),
    CapGroup(Capability.DEX, "DEX / Smali", "Outline, search, xrefs, disassemble, assemble.", "APK"),
    CapGroup(Capability.RES, "Resources", "XML decode, ARSC inspect and resolve.", "Resources"),
    CapGroup(Capability.EDIT, "Edit / Build", "Stage edits, rebuild, sign and verify.", "APK"),
    CapGroup(Capability.FILE_READ, "File read", "List, stat and read inside the scope.", "Files"),
    CapGroup(Capability.FILE_WRITE, "File write", "Write, patch and append inside the scope.", "Files"),
    CapGroup(Capability.FILE_CREATE, "File create", "Copy and create directories.", "Files"),
    CapGroup(Capability.FILE_DELETE, "File delete", "Delete files and directories.", "Files"),
    CapGroup(Capability.FILE_MOVE, "File move", "Move entries inside the scope.", "Files"),
    CapGroup(Capability.FILE_RENAME, "File rename", "Rename entries inside the scope.", "Files"),
)

/**
 * CENTER tab: agent capability permission sets. Ported from the backup UI
 * and adapted to the rebuilt registry/prefs (synchronous reads; toggles
 * re-sync the registry immediately).
 */
@Composable
fun CapabilitiesScreen() {
    val app = LocalContext.current.applicationContext as ObsidianApp
    val registry = app.container.tools
    val prefs = app.container.prefs
    val scope = rememberCoroutineScope()
    // Bumped on every toggle so sync prefs reads recompose.
    var nonce by remember { mutableIntStateOf(0) }
    var verifying by remember { mutableStateOf(false) }

    fun refresh() {
        registry.syncDisabled(prefs.disabledTools(), prefs.disabledCapabilities())
        nonce++
    }

    suspend fun verifyAll() {
        verifying = true
        withContext(Dispatchers.IO) { runCatching { registry.verifyAllSafe() } }
        verifying = false
        nonce++
    }

    // Safe dry-runs on open: badges reflect this session's live probes.
    LaunchedEffect(Unit) { verifyAll() }

    // Prefs reads are synchronous; key them on nonce so toggles recompose.
    val disabledTools = remember(nonce) { prefs.disabledTools() }
    val disabledCaps = remember(nonce) { prefs.disabledCapabilities() }
    val tools = remember(nonce) { registry.all().sortedBy { it.name } }
    val health = remember(nonce) {
        tools.associate { it.name to registry.healthOf(it.name) }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("Installed tools (${tools.size})", Modifier.weight(1f))
                ObActionButton(
                    if (verifying) "Verifying…" else "Verify",
                    onClick = { scope.launch { verifyAll() } },
                    enabled = !verifying,
                    kind = ObButtonKind.Ghost,
                )
            }
        }
        item {
            ObCard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (tools.isEmpty()) {
                        Text(
                            "No tools registered.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        tools.forEach { t ->
                            val on = t.name !in disabledTools && t.capability.id !in disabledCaps
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(t.name, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        t.title,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    HealthDot(health[t.name] ?: ToolHealth.UNVERIFIED)
                                }
                                ObSwitch(
                                    checked = on,
                                    onCheckedChange = { checked ->
                                        prefs.setToolEnabled(t.name, checked)
                                        refresh()
                                    }
                                )
                            }
                        }
                    }
                    Text(
                        "Toggles apply immediately to the running endpoint. Disabled tools return DISABLED.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        for (category in listOf("APK", "Resources", "Files")) {
            item { SectionLabel(category) }
            item {
                ObCard {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        GROUPS.filter { it.category == category }.forEach { cap ->
                            val on = cap.id.id !in disabledCaps
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(cap.title, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        cap.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                ObSwitch(
                                    checked = on,
                                    onCheckedChange = { checked ->
                                        prefs.setCapabilityEnabled(cap.id.id, checked)
                                        refresh()
                                    }
                                )
                            }
                        }
                        if (category == "Files") {
                            Text(
                                "File gates apply to ae_file_* tools. " +
                                    "Disabled capabilities return DISABLED_CAPABILITY.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        item {
            ObCard {
                Column(Modifier.fillMaxWidth()) {
                    Text("How gating works", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "tools/call checks capability first, then the per-tool switch.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
