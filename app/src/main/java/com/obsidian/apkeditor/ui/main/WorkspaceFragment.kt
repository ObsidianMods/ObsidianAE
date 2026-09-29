package com.obsidian.apkeditor.ui.main

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.obsidian.apkeditor.R
import com.obsidian.apkeditor.app.ObsidianApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Lists imported APK workspaces. Reads only; mutations live in tools/ops. */
class WorkspaceFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_workspace, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val list = view.findViewById<ListView>(R.id.workspace_list)
        val empty = view.findViewById<TextView>(R.id.workspace_empty)
        viewLifecycleOwner.lifecycleScope.launch {
            val names = withContext(Dispatchers.IO) {
                runCatching {
                    val app = requireContext().applicationContext as ObsidianApp
                    app.container.workspaces.list().map { it.displayName }
                }.getOrDefault(emptyList())
            }
            if (!isAdded) return@launch
            empty.visibility = if (names.isEmpty()) View.VISIBLE else View.GONE
            list.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, names)
        }
    }
}
