package com.obsidian.apkeditor.system

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Settings backed by SharedPreferences (not DataStore: the recovery path and
 * the service entry points must read settings without a warm datastore).
 */
class Prefs(ctx: Context) {

    private val sp: SharedPreferences =
        ctx.applicationContext.getSharedPreferences("obsidian", Context.MODE_PRIVATE)

    var servicePort: Int
        get() = sp.getInt(KEY_PORT, DEFAULT_PORT).coerceIn(1024, 65535)
        set(value) = sp.edit { putInt(KEY_PORT, value.coerceIn(1024, 65535)) }

    var endpointPath: String
        get() = sanitize(sp.getString(KEY_PATH, DEFAULT_PATH).orEmpty())
        set(value) = sp.edit { putString(KEY_PATH, sanitize(value)) }

    var serviceWanted: Boolean
        get() = sp.getBoolean(KEY_WANTED, false)
        set(value) = sp.edit { putBoolean(KEY_WANTED, value) }

    var stopOnTaskRemoved: Boolean
        get() = sp.getBoolean(KEY_STOP_REMOVED, true)
        set(value) = sp.edit { putBoolean(KEY_STOP_REMOVED, value) }

    var themeMode: String
        get() = sp.getString(KEY_THEME, "SYSTEM").orEmpty().takeIf { it.isNotEmpty() } ?: "SYSTEM"
        set(value) = sp.edit { putString(KEY_THEME, value) }

    var accent: String
        get() = sp.getString(KEY_ACCENT, "VIOLET").orEmpty().takeIf { it.isNotEmpty() } ?: "VIOLET"
        set(value) = sp.edit { putString(KEY_ACCENT, value) }

    var uiScale: Float
        get() = sp.getFloat(KEY_SCALE, 1f).coerceIn(0.85f, 1.3f)
        set(value) = sp.edit { putFloat(KEY_SCALE, value.coerceIn(0.85f, 1.3f)) }

    fun disabledTools(): Set<String> =
        sp.getStringSet(KEY_DISABLED_TOOLS, emptySet()).orEmpty().toSet()

    fun setToolEnabled(name: String, enabled: Boolean) {
        val next = disabledTools().toMutableSet()
        if (enabled) next.remove(name) else next.add(name)
        sp.edit { putStringSet(KEY_DISABLED_TOOLS, next) }
    }

    fun disabledCapabilities(): Set<String> =
        sp.getStringSet(KEY_DISABLED_CAPS, emptySet()).orEmpty().toSet()

    fun setCapabilityEnabled(id: String, enabled: Boolean) {
        val next = disabledCapabilities().toMutableSet()
        if (enabled) next.remove(id) else next.add(id)
        sp.edit { putStringSet(KEY_DISABLED_CAPS, next) }
    }

    companion object {
        const val DEFAULT_PORT = 4123
        const val DEFAULT_PATH = "mcp"

        private const val KEY_PORT = "port"
        private const val KEY_PATH = "path"
        private const val KEY_WANTED = "service_wanted"
        private const val KEY_STOP_REMOVED = "stop_on_removed"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_ACCENT = "accent"
        private const val KEY_SCALE = "ui_scale"
        private const val KEY_DISABLED_TOOLS = "disabled_tools"
        private const val KEY_DISABLED_CAPS = "disabled_caps"

        /** Single path segment, `[A-Za-z0-9][A-Za-z0-9_-]*`, max 64 chars. */
        fun sanitize(raw: String): String {
            val seg = raw.trim().trim('/').take(64)
            if (seg.isEmpty()) return DEFAULT_PATH
            if (seg[0].isLetterOrDigit().not()) return DEFAULT_PATH
            if (seg.any { !(it.isLetterOrDigit() || it == '_' || it == '-' || it == '/') }) return DEFAULT_PATH
            if ('/' in seg) return DEFAULT_PATH
            return seg
        }
    }
}
