package com.obsidian.apkeditor.mcp

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import com.obsidian.apkeditor.system.Prefs
import com.obsidian.apkeditor.tools.ToolRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface ServerStatus {
    data object Stopped : ServerStatus
    data object Starting : ServerStatus
    data class Running(val port: Int, val endpointUrl: String, val startedAt: Long) : ServerStatus
    data class Error(val message: String) : ServerStatus
}

val ServerStatus.label: String
    get() = when (this) {
        ServerStatus.Stopped -> "Stopped"
        ServerStatus.Starting -> "Starting…"
        is ServerStatus.Running -> "Running"
        is ServerStatus.Error -> "Error"
    }

val ServerStatus.endpointUrl: String
    get() = (this as? ServerStatus.Running)?.endpointUrl.orEmpty()

/**
 * Process-scoped service owner. Cancellable start (the reference queued
 * stop-behind-start behind a mutex with no cancellation), and a self-test
 * failure reports Error WITHOUT persist-disabling auto-resume.
 */
class ServiceController(
    app: Context,
    private val prefs: Prefs,
) {
    val appContext: Context = app.applicationContext

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var serverJob: Job? = null
    private val lock = Mutex()

    private var server: McpServer? = null
    private var registryProvider: (() -> ToolRegistry)? = null

    private val _status = MutableStateFlow<ServerStatus>(ServerStatus.Stopped)
    val statusFlow: StateFlow<ServerStatus> = _status.asStateFlow()

    fun attachRegistry(provider: () -> ToolRegistry) {
        registryProvider = provider
    }

    fun status(): ServerStatus = _status.value

    fun endpointUrl(port: Int, path: String): String = "http://127.0.0.1:$port/$path"

    /** Fire-and-forget entry for UI; never throws (tile/shortcut lesson). */
    fun startAsync() {
        serverJob?.cancel()
        serverJob = scope.launch { start() }
    }

    suspend fun start() {
        lock.withLock {
            _status.value = ServerStatus.Starting
            stopServerLocked()
            val registry = runCatching { registryProvider?.invoke() }.getOrNull()
            if (registry == null) {
                _status.value = ServerStatus.Error("tools not ready")
                return
            }
            registry.syncDisabled(prefs.disabledTools(), prefs.disabledCapabilities())
            val port = prefs.servicePort
            val path = prefs.endpointPath
            val server = McpServer(JsonRpcRouter(registry), scope.coroutineContext)
            val ok = runCatching {
                server.start(port, path)
                selfTest(port, path)
            }.getOrDefault(false)
            if (!ok) {
                runCatching { server.destroy() }
                _status.value = ServerStatus.Error("self-test failed on :$port/$path")
                return
            }
            this.server = server
            prefs.serviceWanted = true
            _status.value = ServerStatus.Running(
                server.port, endpointUrl(server.port, path), System.currentTimeMillis())
            McpService.start(appContext)
            McpNotifications.show(appContext, endpointUrl(server.port, path))
        }
    }

    suspend fun stop() {
        lock.withLock { stopLocked() }
    }

    fun stopAsync() {
        scope.launch { stop() }
    }

    suspend fun resumeIfWanted() {
        if (!prefs.serviceWanted) return
        if (_status.value is ServerStatus.Running) return
        start()
    }

    fun onTaskRemoved() {
        if (prefs.stopOnTaskRemoved) stopAsync()
    }

    private fun stopServerLocked() {
        runCatching { server?.destroy() }
        server = null
    }

    private fun stopLocked() {
        serverJob?.cancel()
        serverJob = null
        stopServerLocked()
        prefs.serviceWanted = false
        _status.value = ServerStatus.Stopped
        McpNotifications.cancel(appContext)
        runCatching { appContext.stopService(Intent(appContext, McpService::class.java)) }
    }

    private fun selfTest(port: Int, path: String): Boolean {
        return try {
            val url = java.net.URL("http://127.0.0.1:$port/$path")
            (url.openConnection() as java.net.HttpURLConnection).run {
                connectTimeout = 2000
                readTimeout = 4000
                requestMethod = "GET"
                val code = responseCode
                disconnect()
                code == 200
            }
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        fun startService(context: Context) {
            val intent = Intent(context, McpService::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
