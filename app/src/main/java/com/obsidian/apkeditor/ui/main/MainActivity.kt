package com.obsidian.apkeditor.ui.main

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.remember
import androidx.lifecycle.lifecycleScope
import com.obsidian.apkeditor.app.ObsidianApp
import com.obsidian.apkeditor.mcp.Accent
import com.obsidian.apkeditor.mcp.ThemeMode
import com.obsidian.apkeditor.ui.AppNav
import com.obsidian.apkeditor.ui.theme.ObsidianAETheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Single launcher activity. Crash handling lives exclusively in the
 * application class + the XML recovery screen — this activity references
 * no recovery types, so a broken crash subsystem can never stall startup.
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
            // Prefs snapshot per launch; theme changes apply on restart.
            val mode = remember {
                runCatching { ThemeMode.valueOf(app.container.prefs.themeMode) }
                    .getOrDefault(ThemeMode.SYSTEM)
            }
            val accent = remember {
                runCatching { Accent.valueOf(app.container.prefs.accent) }
                    .getOrDefault(Accent.VIOLET)
            }
            val scale = remember {
                runCatching { app.container.prefs.uiScale }.getOrDefault(1f)
            }
            ObsidianAETheme(mode = mode, accent = accent, uiScale = scale) {
                AppNav()
            }
        }
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
