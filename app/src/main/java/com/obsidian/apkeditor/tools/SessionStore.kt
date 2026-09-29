package com.obsidian.apkeditor.tools

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Short-lived edit-session bindings (sessionId → workspaceId).
 * Capped and explicitly closed; the reference leak (unbounded, never
 * cleared) is what this replaces.
 */
class SessionStore {

    private val map = ConcurrentHashMap<String, String>()

    fun open(workspaceId: String): String {
        if (map.size > MAX_SESSIONS) map.clear()
        val id = UUID.randomUUID().toString().take(8)
        map[id] = workspaceId
        return id
    }

    fun resolve(session: String): String? = map[session]

    fun close(session: String) {
        map.remove(session)
    }

    companion object {
        private const val MAX_SESSIONS = 64
    }
}
