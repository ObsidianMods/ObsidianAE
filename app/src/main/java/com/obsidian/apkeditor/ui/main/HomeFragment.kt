package com.obsidian.apkeditor.ui.main

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.obsidian.apkeditor.R
import com.obsidian.apkeditor.app.ObsidianApp
import com.obsidian.apkeditor.recovery.CrashStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Landing tab: backend state + workspace count. No backend init here. */
class HomeFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_home, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val status = view.findViewById<TextView>(R.id.home_status)
        val detail = view.findViewById<TextView>(R.id.home_detail)
        status.text = getString(R.string.ready)
        viewLifecycleOwner.lifecycleScope.launch {
            val (safe, count) = withContext(Dispatchers.IO) {
                val app = requireContext().applicationContext as ObsidianApp
                val s = runCatching { CrashStore.inSafeMode(app) }.getOrDefault(false)
                val c = runCatching { app.container.workspaces.list().size }.getOrDefault(0)
                s to c
            }
            if (!isAdded) return@launch
            if (safe) status.text = getString(R.string.safe_mode_on)
            detail.text = getString(R.string.home_detail, count)
        }
    }
}
