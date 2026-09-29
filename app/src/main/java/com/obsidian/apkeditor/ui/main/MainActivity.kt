package com.obsidian.apkeditor.ui.main

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.lifecycleScope
import com.obsidian.apkeditor.app.ObsidianApp
import com.obsidian.apkeditor.ui.main.components.GlowBackdrop
import com.obsidian.apkeditor.ui.main.screens.HomeScreen
import com.obsidian.apkeditor.ui.main.screens.ServiceScreen
import com.obsidian.apkeditor.ui.main.screens.SettingsScreen
import com.obsidian.apkeditor.ui.main.screens.WorkspaceScreen
import com.obsidian.apkeditor.ui.theme.ObsidianAETheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Single launcher activity. Compose UI over the XML-free main surface
 * (crash handling stays exclusively in the application class + the XML
 * recovery screen — this activity references no recovery types).
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
            ObsidianAETheme {
                var tab by remember { mutableStateOf(Tab.Home) }
                Box(Modifier.fillMaxSize()) {
                    GlowBackdrop()
                    Scaffold(
                        containerColor = Color.Transparent,
                        bottomBar = {
                            ObsidianBottomBar(current = tab, onSelect = { tab = it })
                        },
                    ) { inner ->
                        Box(Modifier
                            .fillMaxSize()
                            .padding(inner)) {
                            Crossfade(
                                targetState = tab,
                                animationSpec = tween(220),
                                label = "tab",
                            ) { active ->
                                when (active) {
                                    Tab.Home -> HomeScreen()
                                    Tab.Workspace -> WorkspaceScreen()
                                    Tab.Service -> ServiceScreen()
                                    Tab.Settings -> SettingsScreen()
                                }
                            }
                        }
                    }
                }
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
