package com.obsidian.apkeditor.ui.main.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.obsidian.apkeditor.app.ObsidianApp
import com.obsidian.apkeditor.ui.main.components.GradientButton
import com.obsidian.apkeditor.ui.main.components.ObsidianCard
import com.obsidian.apkeditor.ui.main.components.SectionLabel

@Composable
fun SettingsScreen() {
    val app = LocalContext.current.applicationContext as ObsidianApp
    var port by remember {
        mutableStateOf(runCatching { app.container.prefs.servicePort }
            .getOrDefault(4123).toString())
    }
    var path by remember {
        mutableStateOf(runCatching { app.container.prefs.endpointPath }
            .getOrDefault("mcp"))
    }
    var savedTick by remember { mutableStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(top = 28.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SectionLabel("Settings")
        Text(
            "Tune the loop",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
        )
        ObsidianCard {
            SectionLabel("Endpoint")
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = port,
                onValueChange = { port = it.filter(Char::isDigit).take(5) },
                label = { Text("Port (1024–65535)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = path,
                onValueChange = { path = it.take(64) },
                label = { Text("Path segment") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            GradientButton(text = "Save", onClick = {
                runCatching {
                    port.toIntOrNull()?.let { app.container.prefs.servicePort = it }
                    app.container.prefs.endpointPath = path
                    savedTick++
                }
            })
            if (savedTick > 0) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Saved — restart the server to rebind.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        ObsidianCard {
            SectionLabel("Scope")
            Spacer(Modifier.height(8.dp))
            Text(
                "Agent file tools stay inside the MCP folder on shared storage. " +
                    "Workspaces live in private app storage.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
