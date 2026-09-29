package com.obsidian.apkeditor.ui.main

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.obsidian.apkeditor.app.ObsidianApp
import com.obsidian.apkeditor.databinding.FragmentSettingsBinding

/** Port/prefix settings. Applied to prefs; server rebinds on next start. */
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val app = requireContext().applicationContext as ObsidianApp
        binding.editPort.setText(
            runCatching { app.container.prefs.servicePort }.getOrDefault(4123).toString())
        binding.editPath.setText(
            runCatching { app.container.prefs.endpointPath }.getOrDefault("mcp"))
        binding.btnSave.setOnClickListener {
            runCatching {
                binding.editPort.text.toString().toIntOrNull()?.let {
                    app.container.prefs.servicePort = it
                }
                app.container.prefs.endpointPath = binding.editPath.text.toString()
            }
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
