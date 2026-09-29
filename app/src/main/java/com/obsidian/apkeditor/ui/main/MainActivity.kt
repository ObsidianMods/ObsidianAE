package com.obsidian.apkeditor.ui.main

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import com.obsidian.apkeditor.app.ObsidianApp
import com.obsidian.apkeditor.mcp.Accent
import com.obsidian.apkeditor.mcp.ThemeMode
import com.obsidian.apkeditor.system.GrantRequests
import com.obsidian.apkeditor.ui.AppNav
import com.obsidian.apkeditor.ui.theme.ObsidianAETheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Single launcher activity. Crash handling lives exclusively in the
 * application class + the Compose recovery screen — this activity references
 * no recovery types, so a broken crash subsystem can never stall startup.
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Grant notification tap (or any grant deep-link) lands here.
        raiseGrant(intent)
        // Off-main warm: pure registration, no I/O.
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { (application as ObsidianApp).container.warm() }
            }
            runCatching {
                (application as ObsidianApp).container.service.resumeIfWanted()
            }
        }
        setContent {
            val app = application as ObsidianApp
            // Hot theme state: mode/accent/scale recompose live on change,
            // so the switcher and scaler take effect immediately.
            val modeName by app.container.prefs.themeFlow.collectAsState()
            val accentName by app.container.prefs.accentFlow.collectAsState()
            val scale by app.container.prefs.scaleFlow.collectAsState()
            val mode = runCatching { ThemeMode.valueOf(modeName) }
                .getOrDefault(ThemeMode.SYSTEM)
            val accent = runCatching { Accent.valueOf(accentName) }
                .getOrDefault(Accent.VIOLET)
            ObsidianAETheme(mode = mode, accent = accent, uiScale = scale) {
                AppNav()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        raiseGrant(intent)
    }

    private fun raiseGrant(intent: Intent?) {
        val path = intent?.getStringExtra(GrantRequests.EXTRA_GRANT_PATH).orEmpty()
        if (path.isNotEmpty()) runCatching { GrantRequests.raise(path) }
    }

    override fun onStart() {
        super.onStart()
        lifecycleScope.launch {
            runCatching {
                (application as ObsidianApp).container.service.resumeIfWanted()
            }
        }
    }
}
