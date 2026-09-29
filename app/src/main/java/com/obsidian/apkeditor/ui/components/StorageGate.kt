package com.obsidian.apkeditor.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.obsidian.apkeditor.system.StorageAccess

/**
 * "All files access" prompt shown while the grant is missing.
 *
 * MANAGE_EXTERNAL_STORAGE has no runtime dialog — the only path is the
 * Settings screen. Granting returns here (ON_RESUME refresh), the default
 * MCP tree is created, and the dialog goes away. "Later" snoozes for this
 * session only; next cold start asks again.
 */
@Composable
fun StorageGate() {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(StorageAccess.hasFullAccess(ctx)) }
    var dismissed by remember { mutableStateOf(false) }
    val owner = LocalLifecycleOwner.current

    fun refresh() {
        val ok = StorageAccess.hasFullAccess(ctx)
        granted = ok
        if (ok) StorageAccess.ensureDefaultDirs()
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { refresh() }

    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    if (!granted && !dismissed) {
        AlertDialog(
            onDismissRequest = { dismissed = true },
            title = { Text("Full file access needed") },
            text = {
                Text(
                    "Obsidian AE keeps its MCP folder at /storage/emulated/0/ObsidianAE/mcp/. " +
                        "Grant “All files access” so the app can create it and read APKs you drop there.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    runCatching { launcher.launch(StorageAccess.requestIntent(ctx)) }
                        .onFailure { launcher.launch(StorageAccess.fallbackIntent()) }
                }) { Text("Grant") }
            },
            dismissButton = {
                TextButton(onClick = { dismissed = true }) { Text("Later") }
            },
        )
    }
}
