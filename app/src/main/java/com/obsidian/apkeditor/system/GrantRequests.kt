package com.obsidian.apkeditor.system

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Transient folder-grant requests. The service raises a path (never touches
 * UI); [com.obsidian.apkeditor.ui.AppNav] collects and opens the storage
 * bottom sheet; the notification tap path re-raises via intent extra.
 * Empty string = manually opened sheet (no banner).
 */
object GrantRequests {
    const val EXTRA_GRANT_PATH = "com.obsidian.apkeditor.GRANT_PATH"

    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending.asStateFlow()

    /** Raises [path]. Returns true if this is a new request (notify-worthy). */
    fun raise(path: String): Boolean {
        val clean = path.trim()
        if (clean.isEmpty() || _pending.value == clean) return false
        _pending.value = clean
        return true
    }

    /** Manual open (no banner). No-op if a request is already pending. */
    fun open() {
        if (_pending.value == null) _pending.value = ""
    }

    fun clear() {
        _pending.value = null
    }
}
