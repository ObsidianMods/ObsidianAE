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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.obsidian.apkeditor.app.ObsidianApp
import com.obsidian.apkeditor.ui.main.components.ObsidianCard
import com.obsidian.apkeditor.ui.main.components.SectionLabel
import com.obsidian.apkeditor.ui.main.components.StatTile
import com.obsidian.apkeditor.ui.main.components.TileRow
import com.obsidian.apkeditor.ui.theme.LocalAccents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun HomeScreen() {
    val app = LocalContext.current.applicationContext as ObsidianApp
    val accents = LocalAccents.current
    var workspaces by remember { mutableIntStateOf(0) }
    var tools by remember { mutableIntStateOf(0) }
    var staged by remember { mutableStateOf("—") }

    LaunchedEffect(Unit) {
        val counts = withContext(Dispatchers.IO) {
            runCatching {
                val c = app.container
                Triple(c.workspaces.list().size, c.tools.all().size, c.operations.ops.value.size)
            }.getOrDefault(Triple(0, 0, 0))
        }
        workspaces = counts.first
        tools = counts.second
        staged = counts.third.toString()
    }

    val titleBrush = Brush.horizontalGradient(
        listOf(
            MaterialTheme.colorScheme.onBackground,
            MaterialTheme.colorScheme.onBackground,
            accents.violet,
        ),
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(top = 28.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SectionLabel("Obsidian AE")
        Text(
            text = "Shape APKs\nwith precision.",
            style = MaterialTheme.typography.displaySmall.copy(brush = titleBrush),
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Import an APK into a workspace, inspect and reshape it, then rebuild and sign — driven by touch or by agent tools.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TileRow {
            StatTile(
                value = workspaces.toString(), caption = "workspaces",
                brush = Brush.horizontalGradient(
                    listOf(accents.violet, accents.teal)),
                modifier = Modifier.weight(1f),
            )
            StatTile(
                value = tools.toString(), caption = "agent tools",
                brush = Brush.horizontalGradient(
                    listOf(accents.teal, accents.violet)),
                modifier = Modifier.weight(1f),
            )
            StatTile(
                value = staged, caption = "operations",
                brush = Brush.horizontalGradient(
                    listOf(accents.amber, accents.crimson)),
                modifier = Modifier.weight(1f),
            )
        }
        ObsidianCard {
            SectionLabel("Flow")
            Spacer(Modifier.height(8.dp))
            FlowStep("01", "Import", "Drop an APK in the MCP folder and open it.")
            FlowStep("02", "Reshape", "Stage edits, resources and smali patches.")
            FlowStep("03", "Ship", "Rebuild, align, sign and verify.")
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun FlowStep(index: String, title: String, body: String) {
    val accents = LocalAccents.current
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Text(
            text = index,
            style = MaterialTheme.typography.titleMedium.copy(
                brush = Brush.verticalGradient(
                    listOf(accents.violet, accents.teal))),
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(end = 12.dp),
        )
        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
