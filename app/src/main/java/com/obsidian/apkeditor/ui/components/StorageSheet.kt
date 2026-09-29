package com.obsidian.apkeditor.ui.components

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.obsidian.apkeditor.app.ObsidianApp
import com.obsidian.apkeditor.system.StorageAccess
import com.obsidian.apkeditor.system.TreePaths
import com.obsidian.apkeditor.ui.theme.Mono

/**
 * Storage access bottom sheet: the user-facing side of folder grants.
 *
 * - Banner when the agent raised a pending path (notification tap or
 *   foreground raise): pick that folder (or Grant all-files) and the agent
 *   retries.
 * - Default MCP dir row with all-files grant state + Grant button.
 * - Granted SAF folders with remove; [+ Add folder] opens the system
 *   folder picker (persistable read/write grant).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageSheet(pendingPath: String?, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as ObsidianApp
    val prefs = app.container.prefs
    val owner = LocalLifecycleOwner.current
    var nonce by remember { mutableIntStateOf(0) }

    fun refresh() {
        nonce++
    }

    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    val fullAccess = remember(nonce) { StorageAccess.hasFullAccess(ctx) }
    val grants = remember(nonce) { prefs.folderGrants().toList().sorted() }

    val allFilesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { refresh() }
    val treeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            prefs.addFolderGrant(uri.toString())
        }
        refresh()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("Storage access", style = MaterialTheme.typography.headlineSmall)
            }
            if (!pendingPath.isNullOrEmpty()) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text(
                                "The agent needs a folder",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                pendingPath,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = Mono,
                                ),
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Pick that folder below (or Grant all-files) — then tell the agent to retry.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                }
            }
            item {
                SectionLabel("MCP folder")
                SettingRow(
                    title = "/storage/emulated/0/ObsidianAE/mcp/",
                    subtitle = if (fullAccess) "All-files access granted"
                    else "Needs “All files access” (Settings screen, no dialog)",
                    control = if (!fullAccess) {
                        {
                            ObActionButton("Grant", onClick = {
                                runCatching {
                                    allFilesLauncher.launch(StorageAccess.requestIntent(ctx))
                                }.onFailure {
                                    allFilesLauncher.launch(StorageAccess.fallbackIntent())
                                }
                            })
                        }
                    } else null,
                )
            }
            item {
                SectionLabel("Granted folders (${grants.size})")
            }
            if (grants.isEmpty()) {
                item {
                    Text(
                        "No folders granted yet. The agent can only see the MCP folder.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(grants, key = { it }) { uriStr ->
                    val uri = remember(uriStr) { runCatching { Uri.parse(uriStr) }.getOrNull() }
                    val alias = remember(uri) {
                        uri?.let { TreePaths.alias(it) } ?: "shared"
                    }
                    val dir = remember(uri) {
                        uri?.let { TreePaths.toFile(it)?.path } ?: "(unsupported provider)"
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(alias, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                dir,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        ObActionButton("Remove", onClick = {
                            runCatching {
                                uri?.let {
                                    ctx.contentResolver.releasePersistableUriPermission(
                                        it,
                                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                                    )
                                }
                            }
                            prefs.removeFolderGrant(uriStr)
                            refresh()
                        }, kind = ObButtonKind.Ghost)
                    }
                }
            }
            item {
                ObActionButton(
                    "Add folder",
                    onClick = { treeLauncher.launch(null) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}
