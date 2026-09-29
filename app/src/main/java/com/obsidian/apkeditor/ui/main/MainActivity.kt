package com.obsidian.apkeditor.ui.main

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.obsidian.apkeditor.R
import com.obsidian.apkeditor.app.ObsidianApp
import com.obsidian.apkeditor.recovery.CrashStore
import com.obsidian.apkeditor.recovery.RecoveryActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Single launcher activity. Deliberately NOT Compose and NOT a splash proxy:
 * it renders its XML frame immediately, warms the container off-main, and
 * never blocks the first frame on backends (unlike the reference, there is
 * no second init pass from a splash screen).
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Crash-first: pending report wins over normal boot.
        val pending = runCatching { CrashStore.consumePending(this) }.getOrNull()
        if (pending != null) {
            startActivity(Intent(this, RecoveryActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_main)

        if (savedInstanceState == null) {
            showTab(HomeFragment())
        }
        findViewById<BottomNavigationView>(R.id.bottom_nav).setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.tab_home -> showTab(HomeFragment())
                R.id.tab_workspace -> showTab(WorkspaceFragment())
                R.id.tab_service -> showTab(ServiceFragment())
                R.id.tab_settings -> showTab(SettingsFragment())
                else -> return@setOnItemSelectedListener false
            }
            true
        }

        // Off-main warm: pure registration, no I/O. Safe-mode skips service resume.
        lifecycleScope.launch {
            val safe = withContext(Dispatchers.IO) {
                runCatching {
                    val app = application as ObsidianApp
                    app.container.warm()
                    CrashStore.inSafeMode(app)
                }.getOrDefault(false)
            }
            if (!safe) {
                runCatching {
                    (application as ObsidianApp).container.service.resumeIfWanted()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        runCatching {
            (application as ObsidianApp).container.service.resumeIfWanted()
        }
    }

    private fun showTab(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_host, fragment)
            .commit()
    }
}
