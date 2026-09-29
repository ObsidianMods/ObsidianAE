package com.obsidian.apkeditor.ui.main

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import androidx.fragment.app.Fragment
import com.obsidian.apkeditor.R
import com.obsidian.apkeditor.app.ObsidianApp

/** Port/prefix settings. Applied to prefs; server rebinds on next start. */
class SettingsFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val app = requireContext().applicationContext as ObsidianApp
        val port = view.findViewById<EditText>(R.id.edit_port)
        val path = view.findViewById<EditText>(R.id.edit_path)
        port.setText(runCatching { app.container.prefs.servicePort }.getOrDefault(4123).toString())
        path.setText(runCatching { app.container.prefs.endpointPath }.getOrDefault("mcp"))
        view.findViewById<Button>(R.id.btn_save).setOnClickListener {
            runCatching {
                port.text.toString().toIntOrNull()?.let { app.container.prefs.servicePort = it }
                app.container.prefs.endpointPath = path.text.toString()
            }
        }
    }
}
