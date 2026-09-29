package com.obsidian.apkeditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.obsidian.apkeditor.R
import com.obsidian.apkeditor.app.ObsidianApp
import com.obsidian.apkeditor.system.GrantRequests
import com.obsidian.apkeditor.ui.components.BarIcon
import com.obsidian.apkeditor.ui.components.CurveTab
import com.obsidian.apkeditor.ui.components.CurvedBottomBar
import com.obsidian.apkeditor.ui.components.DebugSheet
import com.obsidian.apkeditor.ui.components.StorageGate
import com.obsidian.apkeditor.ui.components.StorageSheet
import com.obsidian.apkeditor.ui.screens.mcp.CapabilitiesScreen
import com.obsidian.apkeditor.ui.screens.mcp.McpScreen
import com.obsidian.apkeditor.ui.screens.mcp.SettingsScreen

object Routes {
    const val MCP = "mcp"
    const val CAPABILITIES = "capabilities"
    const val SETTINGS = "settings"
}

/**
 * Bottom-bar destinations. Weight-distributed in [CurvedBottomBar], so new
 * screens just append here — no layout rework. Capabilities stays CENTER.
 *
 * State-based routing (no navigation-compose dependency): the selected route
 * is plain state, so tab switches never touch the back stack.
 */
private val TABS = listOf(
    CurveTab(Routes.MCP, "MCP", BarIcon.Res(R.drawable.ic_mcp)),
    CurveTab(Routes.CAPABILITIES, "Capabilities", BarIcon.Vec(Icons.Filled.Lock)),
    CurveTab(Routes.SETTINGS, "Settings", BarIcon.Vec(Icons.Filled.Settings)),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNav() {
    var cur by remember { mutableStateOf(Routes.MCP) }
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    val cs = MaterialTheme.colorScheme
    // Folder-grant sheet: raised by tool calls (foreground) or by the
    // grant notification tap (background). Dismiss resolves the request.
    val app = LocalContext.current.applicationContext as ObsidianApp
    val pendingGrant by GrantRequests.pending.collectAsState()
    var debugOpen by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(cs.background).systemBarsPadding()) {
        Column(Modifier.weight(1f).fillMaxWidth().nestedScroll(scrollBehavior.nestedScrollConnection)) {
            TopAppBar(
                title = {
                    Text(
                        when (cur) {
                            Routes.SETTINGS -> "Settings"
                            Routes.CAPABILITIES -> "Capabilities"
                            else -> "MCP"
                        }
                    )
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = cs.background),
                actions = {
                    IconButton(onClick = { debugOpen = true }) {
                        Icon(Icons.Filled.BugReport, "Debug panel")
                    }
                }
            )
            // Accent edge under the bar — the accent stays visible everywhere.
            Box(Modifier.fillMaxWidth().height(2.dp).background(cs.primary.copy(alpha = 0.55f)))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (cur) {
                    Routes.MCP -> McpScreen()
                    Routes.CAPABILITIES -> CapabilitiesScreen()
                    Routes.SETTINGS -> SettingsScreen()
                }
            }
        }
        CurvedBottomBar(tabs = TABS, selectedRoute = cur, onSelect = { cur = it })
    }
    // All-files-access gate floats above every tab until granted.
    StorageGate()
    if (pendingGrant != null) {
        StorageSheet(
            pendingPath = pendingGrant?.takeIf { it.isNotEmpty() },
            onDismiss = { runCatching { app.container.service.onGrantResolved() } },
        )
    }
    if (debugOpen) {
        DebugSheet(onDismiss = { debugOpen = false })
    }
}
