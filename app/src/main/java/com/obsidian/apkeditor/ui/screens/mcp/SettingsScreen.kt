package com.obsidian.apkeditor.ui.screens.mcp

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.obsidian.apkeditor.app.ObsidianApp
import com.obsidian.apkeditor.mcp.Accent
import com.obsidian.apkeditor.mcp.ThemeMode
import com.obsidian.apkeditor.system.Prefs
import com.obsidian.apkeditor.ui.components.AccentOrbit
import com.obsidian.apkeditor.ui.components.EndpointRow
import com.obsidian.apkeditor.ui.components.ObTextField
import com.obsidian.apkeditor.ui.components.SectionLabel
import com.obsidian.apkeditor.ui.components.SlidingSegmented
import kotlin.math.roundToInt

/**
 * Settings: theme, accent, scale, endpoint config, LAN URLs. Ported from the
 * backup UI and adapted to synchronous prefs (writes apply instantly to
 * storage; theme restarts with the activity).
 */
@Composable
fun SettingsScreen() {
    val app = LocalContext.current.applicationContext as ObsidianApp
    val prefs = app.container.prefs

    // Live theme state — edits recompose MainActivity's theme immediately.
    val theme by prefs.themeFlow.collectAsState()
    val accent by prefs.accentFlow.collectAsState()
    val scale by prefs.scaleFlow.collectAsState()
    var portText by remember { mutableStateOf(prefs.servicePort.toString()) }
    var prefixText by remember { mutableStateOf(prefs.endpointPath) }
    var portError by remember { mutableStateOf<String?>(null) }
    var prefixError by remember { mutableStateOf<String?>(null) }
    val lanIps = remember { lanAddresses() }
    val port = portText.toIntOrNull()?.takeIf { it in 1024..65535 }
        ?: Prefs.DEFAULT_PORT

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { SectionLabel("Theme") }
        item {
            SettingsCard {
                Text(
                    "Appearance",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Follow the system or force light / dark. Applies instantly.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                SlidingSegmented(
                    options = listOf(
                        "System" to ThemeMode.SYSTEM.name,
                        "Light" to ThemeMode.LIGHT.name,
                        "Dark" to ThemeMode.DARK.name
                    ),
                    selected = theme,
                    onSelect = { prefs.themeMode = it }
                )
            }
        }

        item { SectionLabel("Accent") }
        item {
            SettingsCard {
                Text(
                    "Accent color",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Used for highlights, sliders and active states. Applies instantly.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                AccentOrbit(
                    options = Accent.entries.map { accentDot(it) to it.name },
                    selected = accent,
                    onSelect = { prefs.accent = it }
                )
            }
        }

        item { SectionLabel("Interface scale") }
        item {
            SettingsCard {
                Text(
                    "Text and layout size",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Scales the whole interface — watch it resize live.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Slider(
                        value = scale,
                        onValueChange = { prefs.uiScale = it },
                        valueRange = 0.85f..1.3f,
                        steps = 8,
                        modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHigh
                        )
                    )
                    Text(
                        "${(scale * 100).roundToInt()}%",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 12.dp)
                    )
                }
            }
        }

        item { SectionLabel("MCP config") }
        item {
            val prefix = prefixText
            SettingsCard {
                Text(
                    "Endpoint",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Port and URL prefix. Restart the service to rebind.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                ObTextField(
                    value = portText,
                    onValueChange = {
                        portText = it
                        val v = it.trim().toIntOrNull()
                        if (v == null || v !in 1024..65535) {
                            portError = "1024–65535"
                        } else {
                            portError = null
                            prefs.servicePort = v
                        }
                    },
                    label = "MCP port",
                    mono = true,
                    keyboardType = KeyboardType.Number,
                    isError = portError != null,
                    supporting = {
                        if (portError != null) Text(portError!!)
                        else Text("Loopback bind on restart")
                    }
                )
                Spacer(Modifier.height(8.dp))
                ObTextField(
                    value = prefixText,
                    onValueChange = {
                        prefixText = it
                        if (Prefs.sanitize(it) != it.trim().trim('/') || it.isBlank()) {
                            prefixError = "Single segment: letters, digits, - or _"
                        } else {
                            prefixError = null
                            prefs.endpointPath = it
                        }
                    },
                    label = "URL prefix",
                    mono = true,
                    isError = prefixError != null,
                    supporting = {
                        if (prefixError != null) Text(prefixError!!)
                        else Text("Single segment: letters, digits, - or _")
                    }
                )
            }
        }

        item { SectionLabel("Remote LAN URLs") }
        item {
            val prefix = prefixText.ifBlank { Prefs.DEFAULT_PATH }
            SettingsCard {
                Text(
                    "LAN-reachable endpoints",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Display only — this build binds loopback. Port or prefix edits update every URL here instantly.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    EndpointRow("Loopback", "http://127.0.0.1:$port/$prefix")
                    if (lanIps.isEmpty()) {
                        Text(
                            "No LAN address found — connect to Wi-Fi to expose LAN URLs.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        lanIps.forEach { ip ->
                            EndpointRow("LAN", "http://$ip:$port/$prefix")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.22f), shape)
            .padding(16.dp)
    ) {
        Column(Modifier.fillMaxWidth()) { content() }
    }
}

private fun accentDot(a: Accent): Color = when (a) {
    Accent.VIOLET -> Color(0xFF8B5CF6)
    Accent.TEAL -> Color(0xFF14B8A6)
    Accent.AMBER -> Color(0xFFF59E0B)
    Accent.CRIMSON -> Color(0xFFF43F5E)
    Accent.BLUE -> Color(0xFF3B82F6)
    Accent.GREEN -> Color(0xFF22C55E)
    Accent.PINK -> Color(0xFFEC4899)
    Accent.ORANGE -> Color(0xFFF97316)
}

/** Site-local IPv4 addresses for LAN URL construction (no loopback, no IPv6). */
private fun lanAddresses(): List<String> {
    return try {
        val out = mutableListOf<String>()
        val ifs = java.net.NetworkInterface.getNetworkInterfaces() ?: return emptyList()
        while (ifs.hasMoreElements()) {
            val nic = ifs.nextElement() ?: continue
            if (!nic.isUp || nic.isLoopback) continue
            val addrs = nic.inetAddresses ?: continue
            while (addrs.hasMoreElements()) {
                val a = addrs.nextElement() ?: continue
                if (a.isLoopbackAddress || a is java.net.Inet6Address) continue
                val ip = a.hostAddress ?: continue
                if (ip.startsWith("192.168.") || ip.startsWith("10.") ||
                    Regex("^172\\.(1[6-9]|2[0-9]|3[0-1])\\.").containsMatchIn(ip)
                ) {
                    if (ip !in out) out.add(ip)
                }
            }
        }
        out.sorted()
    } catch (_: Throwable) {
        emptyList()
    }
}
