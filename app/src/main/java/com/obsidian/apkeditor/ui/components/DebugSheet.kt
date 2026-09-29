package com.obsidian.apkeditor.ui.components

import android.app.ActivityManager
import android.content.Context
import android.os.SystemClock
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.obsidian.apkeditor.app.ObsidianApp
import com.obsidian.apkeditor.ui.theme.Mono
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Pull-up debug panel: process memory (live graph), in-flight tool calls,
 * and the last 20 completed calls with outcome + duration.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugSheet(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as ObsidianApp
    val registry = app.container.tools

    val history by registry.history.collectAsState()
    val active by registry.active.collectAsState()

    // Live memory trace while open (2s cadence, last 30 points).
    var samples by remember { mutableStateOf(listOf<Pair<Long, Long>>()) }
    var memInfo by remember { mutableStateOf(Triple(0L, 0L, false)) }
    // 1s tick so active-call elapsed times stay fresh.
    var tick by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        var n = 0
        while (isActive) {
            val mem = readMem(ctx)
            memInfo = mem
            samples = (samples + (SystemClock.elapsedRealtime() to mem.first)).takeLast(30)
            n++
            tick = n
            delay(2000)
        }
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
                Text("Debug", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Live process state. Memory samples every 2s while open.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                @Suppress("UNUSED_EXPRESSION")
                tick
                SectionLabel("Memory")
                ObCard {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val (pssKb, availMb, lowMem) = memInfo
                        StatRow("App PSS", "%.1f MB".format(pssKb / 1024f))
                        StatRow("System free", "$availMb MB${if (lowMem) " (LOW)" else ""}")
                        Spacer(Modifier.height(4.dp))
                        MemoryGraph(samples)
                    }
                }
            }
            item {
                SectionLabel("Active calls (${active.size})")
            }
            if (active.isEmpty()) {
                item {
                    Text(
                        "None in flight.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(active, key = { it.seq }) { a ->
                    @Suppress("UNUSED_EXPRESSION")
                    tick
                    val elapsed =
                        (SystemClock.elapsedRealtime() - a.startedAt) / 1000.0
                    ObCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    a.tool,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontFamily = Mono,
                                    ),
                                )
                                Text(
                                    "running %.1fs".format(elapsed),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
            item {
                SectionLabel("Last ${minOf(history.size, 20)} calls")
            }
            if (history.isEmpty()) {
                item {
                    Text(
                        "No calls yet this session.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(history.take(20), key = { it.seq }) { c ->
                    ObCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "#${c.seq} ${c.tool}",
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontFamily = Mono,
                                    ),
                                )
                                Text(
                                    (if (c.ok) "ok" else "error") + " · ${c.ms}ms",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (c.ok) MaterialTheme.colorScheme.onSurfaceVariant
                                    else MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }
}

/** Returns (appPssKb, systemAvailMb, lowMemory). Never throws. */
private fun readMem(ctx: Context): Triple<Long, Long, Boolean> = runCatching {
    val pssKb = android.os.Debug.getPss()
    val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val info = ActivityManager.MemoryInfo()
    am.getMemoryInfo(info)
    Triple(pssKb, info.availMem / (1024 * 1024), info.lowMemory)
}.getOrDefault(Triple(0L, 0L, false))
