package com.obsidian.apkeditor.ui.main

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.obsidian.apkeditor.R
import com.obsidian.apkeditor.app.ObsidianApp
import com.obsidian.apkeditor.databinding.FragmentServiceBinding
import com.obsidian.apkeditor.mcp.endpointUrl
import com.obsidian.apkeditor.mcp.label
import kotlinx.coroutines.launch

/** Service control tab: endpoint status + start/stop. */
class ServiceFragment : Fragment() {

    private var _binding: FragmentServiceBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentServiceBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        refresh()
        binding.btnStart.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                runCatching {
                    val app = requireContext().applicationContext as ObsidianApp
                    app.container.service.start()
                }
                if (isAdded && _binding != null) refresh()
            }
        }
        binding.btnStop.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                runCatching {
                    (requireContext().applicationContext as ObsidianApp).container.service.stop()
                }
                if (isAdded && _binding != null) refresh()
            }
        }
    }

    private fun refresh() {
        val b = _binding ?: return
        val app = requireContext().applicationContext as ObsidianApp
        val st = runCatching { app.container.service.status() }.getOrNull()
        b.serviceStatus.text = st?.label ?: getString(R.string.unknown)
        b.serviceEndpoint.text = st?.endpointUrl ?: ""
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
