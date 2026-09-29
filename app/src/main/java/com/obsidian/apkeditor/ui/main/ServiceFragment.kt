package com.obsidian.apkeditor.ui.main

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.obsidian.apkeditor.R
import com.obsidian.apkeditor.app.ObsidianApp
import com.obsidian.apkeditor.mcp.endpointUrl
import com.obsidian.apkeditor.mcp.label
import kotlinx.coroutines.launch

/** Service control tab: endpoint status + start/stop. */
class ServiceFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_service, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val status = view.findViewById<TextView>(R.id.service_status)
        val endpoint = view.findViewById<TextView>(R.id.service_endpoint)
        val start = view.findViewById<Button>(R.id.btn_start)
        val stop = view.findViewById<Button>(R.id.btn_stop)

        fun refresh() {
            val app = requireContext().applicationContext as ObsidianApp
            val ctl = app.container.service
            val st = runCatching { ctl.status() }.getOrNull()
            status.text = st?.label ?: getString(R.string.unknown)
            endpoint.text = st?.endpointUrl ?: ""
        }
        refresh()

        start.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                runCatching {
                    val app = requireContext().applicationContext as ObsidianApp
                    app.container.service.start()
                }
                if (isAdded) refresh()
            }
        }
        stop.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                runCatching {
                    (requireContext().applicationContext as ObsidianApp).container.service.stop()
                }
                if (isAdded) refresh()
            }
        }
    }
}
