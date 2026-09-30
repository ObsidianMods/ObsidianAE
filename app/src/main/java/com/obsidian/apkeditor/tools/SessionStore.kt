package com.obsidian.apkeditor.tools

import android.content.Context
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject

/**
 * Edit-session bindings (sessionId → workspaceId). Capped and explicitly
 * closed; file-backed so a process restart (crash, OEM kill) does not turn
 * live sessions into "unknown session" — the map is reloaded on init.
 */
class SessionStore(app: Context? = null) {

    private val map = ConcurrentHashMap<String, String>()
    private val storeFile = app?.applicationContext?.filesDir?.let { java.io.File(it, FILE_NAME) }

    init {
        runCatching {
            val f = storeFile?.takeIf { it.isFile } ?: return@runCatching
            val json = JSONObject(f.readText())
            val keys = json.keys()
            var n = 0
            while (keys.hasNext() && n < MAX_SESSIONS) {
                val k = keys.next()
                val v = json.optString(k, "")
                if (k.isNotEmpty() && v.isNotEmpty()) {
                    map[k] = v
                    n++
                }
            }
        }
    }

    fun open(workspaceId: String): String {
        if (map.size > MAX_SESSIONS) map.clear()
        val id = UUID.randomUUID().toString().take(8)
        map[id] = workspaceId
        persist()
        return id
    }

    fun resolve(session: String): String? = map[session]

    fun close(session: String) {
        map.remove(session)
        persist()
    }

    private fun persist() {
        runCatching {
            val f = storeFile ?: return@runCatching
            val json = JSONObject()
            for ((k, v) in map) json.put(k, v)
            f.writeText(json.toString())
        }
    }

    companion object {
        private const val MAX_SESSIONS = 64
        private const val FILE_NAME = "sessions.json"
    }
}
