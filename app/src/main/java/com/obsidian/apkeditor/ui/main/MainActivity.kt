package com.obsidian.apkeditor.ui.main

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.databinding.DataBindingUtil
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.obsidian.apkeditor.R
import com.obsidian.apkeditor.app.ObsidianApp
import com.obsidian.apkeditor.databinding.ActivityMainBinding
import com.obsidian.apkeditor.recovery.CrashStore
import com.obsidian.apkeditor.recovery.RecoveryActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Single launcher activity (Data Binding, no Compose, no splash proxy).
 *
 * The fragment container is registered once: each tab is added under a stable
 * tag on first selection and shown/hidden afterwards, so fragment state
 * survives tab switches and rotation instead of being recreated every tap.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var currentTag: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Launch guard: any boot failure routes to Recovery with a report
        // instead of hanging on the system splash.
        val bootError = runCatching { boot(savedInstanceState) }.exceptionOrNull()
        if (bootError != null) {
            runCatching {
                CrashStore.writeCrash(this, Thread.currentThread(), bootError)
                startActivity(Intent(this, RecoveryActivity::class.java))
            }
            finish()
        }
    }

    private fun boot(savedInstanceState: Bundle?) {
        // Crash-first: pending report wins over normal boot.
        val pending = runCatching { CrashStore.consumePending(this) }.getOrNull()
        if (pending != null) {
            startActivity(Intent(this, RecoveryActivity::class.java))
            finish()
            return
        }
        binding = DataBindingUtil.setContentView(this, R.layout.activity_main)

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.tab_home -> selectTab(TAG_HOME) { HomeFragment() }
                R.id.tab_workspace -> selectTab(TAG_WORKSPACE) { WorkspaceFragment() }
                R.id.tab_service -> selectTab(TAG_SERVICE) { ServiceFragment() }
                R.id.tab_settings -> selectTab(TAG_SETTINGS) { SettingsFragment() }
                else -> false
            }
        }
        binding.bottomNav.setOnItemReselectedListener { /* keep current tab */ }

        if (savedInstanceState == null) {
            binding.bottomNav.selectedItemId = R.id.tab_home
        } else {
            currentTag = savedInstanceState.getString(KEY_TAB)
            // Re-attach the visible tab after rotation; fragments are
            // retained by the FragmentManager under their tags.
            val tag = currentTag
            if (tag != null && supportFragmentManager.findFragmentByTag(tag) != null) {
                showOnly(tag)
            } else {
                binding.bottomNav.selectedItemId = R.id.tab_home
            }
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
        if (!::binding.isInitialized) return
        lifecycleScope.launch {
            runCatching {
                (application as ObsidianApp).container.service.resumeIfWanted()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_TAB, currentTag)
    }

    /**
     * Registers [tag] in the container on first use, then shows only it.
     * Returns true so the BottomNavigationView marks the item selected.
     */
    private fun selectTab(tag: String, create: () -> Fragment): Boolean {
        if (supportFragmentManager.isStateSaved) return false
        val tx = supportFragmentManager.beginTransaction()
        val existing = supportFragmentManager.findFragmentByTag(tag)
        if (existing == null) {
            tx.add(R.id.fragment_host, create(), tag)
        }
        for (t in ALL_TAGS) {
            val f = supportFragmentManager.findFragmentByTag(t) ?: continue
            if (t == tag) tx.show(f) else tx.hide(f)
        }
        tx.commit()
        currentTag = tag
        return true
    }

    private fun showOnly(tag: String) {
        val tx = supportFragmentManager.beginTransaction()
        for (t in ALL_TAGS) {
            val f = supportFragmentManager.findFragmentByTag(t) ?: continue
            if (t == tag) tx.show(f) else tx.hide(f)
        }
        tx.commit()
        currentTag = tag
    }

    companion object {
        private const val KEY_TAB = "current_tab"
        private const val TAG_HOME = "tab_home"
        private const val TAG_WORKSPACE = "tab_workspace"
        private const val TAG_SERVICE = "tab_service"
        private const val TAG_SETTINGS = "tab_settings"
        private val ALL_TAGS = arrayOf(TAG_HOME, TAG_WORKSPACE, TAG_SERVICE, TAG_SETTINGS)
    }
}
