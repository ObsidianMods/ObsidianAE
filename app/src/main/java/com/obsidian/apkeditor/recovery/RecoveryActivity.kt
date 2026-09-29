package com.obsidian.apkeditor.recovery

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Process
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.obsidian.apkeditor.R
import com.obsidian.apkeditor.ui.theme.Mono
import com.obsidian.apkeditor.ui.theme.ObsidianAETheme
import kotlin.system.exitProcess

/**
 * Isolated recovery surface, now Compose (no XML, no DataBinding).
 * Reads the log from the intent extra, else the last stored log.
 * References [CrashStore] and nothing else — safe when everything else
 * is broken. Never referenced from any other activity.
 */
class RecoveryActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val log = intent.getStringExtra(EXTRA_LOG)
            ?.takeIf { it.isNotEmpty() }
            ?: runCatching { CrashStore.readLastLog(this) }.getOrDefault("")
        val safeMode = runCatching { CrashStore.inSafeMode(this) }.getOrDefault(false)
        setContent {
            // Defaults only (SYSTEM/VIOLET/1f): never touches Prefs/container,
            // so a broken settings stack can't break the crash screen.
            ObsidianAETheme {
                RecoveryScreen(logText = log, safeMode = safeMode)
            }
        }
    }

    companion object {
        const val EXTRA_LOG = "extra_log"
    }
}

@Composable
private fun RecoveryScreen(logText: String, safeMode: Boolean) {
    val ctx = LocalContext.current
    val log = remember(logText) {
        logText.takeIf { it.isNotEmpty() }
            ?: ctx.getString(R.string.recovery_no_details)
    }
    val status = remember(log, safeMode) {
        when {
            safeMode -> ctx.getString(R.string.recovery_safe_mode)
            log.startsWith("=== ANR ===") -> ctx.getString(R.string.recovery_anr)
            else -> ctx.getString(R.string.recovery_crash)
        }
    }

    fun copyReport() {
        if (log.isEmpty()) return
        runCatching {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("obsidian-crash", log.take(24_000)))
            Toast.makeText(ctx, R.string.copied, Toast.LENGTH_SHORT).show()
        }
    }

    fun restartApp() {
        runCatching {
            val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
            if (launch != null) {
                launch.addFlags(
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK,
                )
                ctx.startActivity(launch)
            }
        }
        Process.killProcess(Process.myPid())
        exitProcess(0)
    }

    fun closeApp() {
        Process.killProcess(Process.myPid())
        exitProcess(0)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.recovery_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = status,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        ) {
            SelectionContainer {
                Text(
                    text = log,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
        Button(
            onClick = ::restartApp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.restart))
        }
        OutlinedButton(
            onClick = ::copyReport,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.copy_report))
        }
        TextButton(
            onClick = ::closeApp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.close))
        }
    }
}
