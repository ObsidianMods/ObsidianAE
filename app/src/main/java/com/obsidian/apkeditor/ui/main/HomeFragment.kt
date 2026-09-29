package com.obsidian.apkeditor.ui.main

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.obsidian.apkeditor.R
import com.obsidian.apkeditor.app.ObsidianApp
import com.obsidian.apkeditor.databinding.FragmentHomeBinding
import com.obsidian.apkeditor.recovery.CrashStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Landing tab: backend state + workspace count. No backend init here. */
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.homeStatus.text = getString(R.string.ready)
        viewLifecycleOwner.lifecycleScope.launch {
            val (safe, count) = withContext(Dispatchers.IO) {
                val app = requireContext().applicationContext as ObsidianApp
                val s = runCatching { CrashStore.inSafeMode(app) }.getOrDefault(false)
                val c = runCatching { app.container.workspaces.list().size }.getOrDefault(0)
                s to c
            }
            if (!isAdded || _binding == null) return@launch
            if (safe) binding.homeStatus.text = getString(R.string.safe_mode_on)
            binding.homeDetail.text = getString(R.string.home_detail, count)
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
