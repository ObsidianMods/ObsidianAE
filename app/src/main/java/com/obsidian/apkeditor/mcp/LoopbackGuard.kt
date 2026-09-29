package com.obsidian.apkeditor.mcp

/** DNS-rebind guard: only loopback origins (or no Origin) are served. */
object LoopbackGuard {

    fun isAllowed(origin: String?): Boolean {
        if (origin.isNullOrBlank()) return true
        val host = origin.substringAfter("://").substringBefore(':')
            .substringBefore('/').lowercase()
        return host == "127.0.0.1" || host == "localhost" || host == "::1"
    }
}
