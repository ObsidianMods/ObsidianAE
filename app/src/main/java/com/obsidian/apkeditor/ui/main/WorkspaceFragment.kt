package com.obsidian.apkeditor.ui.main

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.obsidian.apkeditor.app.ObsidianApp
import com.obsidian.apkeditor.databinding.FragmentWorkspaceBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Lists imported APK workspaces. Reads only; mutations live in tools/ops. */
class WorkspaceFragment : Fragment() {

    private var _binding: FragmentWorkspaceBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentWorkspaceBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        viewLifecycleOwner.lifecycleScope.launch {
            val names = withContext(Dispatchers.IO) {
                runCatching {
                    val app = requireContext().applicationContext as ObsidianApp
                    app.container.workspaces.list().map { it.displayName }
                }.getOrDefault(emptyList())
            }
            if (!isAdded || _binding == null) return@launch
            binding.workspaceEmpty.visibility =
                if (names.isEmpty()) View.VISIBLE else View.GONE
            binding.workspaceList.adapter =
                ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, names)
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
