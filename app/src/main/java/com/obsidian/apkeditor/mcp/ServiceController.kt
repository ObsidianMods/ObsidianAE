package com.obsidian.apkeditor.mcp

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import com.obsidian.apkeditor.mcp.overlay.AssistantOverlay
import com.obsidian.apkeditor.mcp.overlay.OverlayPermission
import com.obsidian.apkeditor.system.Prefs
import com.obsidian.apkeditor.tools.ToolRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
    /**
     * Single-thread home for every overlay call. WindowManager views must be
     * added/updated/removed on the thread that created them; the shared IO
     * pool cannot guarantee that, so the overlay gets its own thread.
     */
    private val overlayScope = CoroutineScope(SupervisorJob() +
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "obsidian-overlay").apply { isDaemon = true }
        }.asCoroutineDispatcher())
    private var serverJob: Job? = null
    private val lock = Mutex()

    private var server: McpServer? = null
    private var registryProvider: (() -> ToolRegistry)? = null
    private val overlay = AssistantOverlay(app.applicationContext)

    private val overlayHost = object : AssistantOverlay.Host {
        override fun isRunning(): Boolean = _status.value is ServerStatus.Running
        override fun endpointUrl(): String = _status.value.endpointUrl
        override fun onToggleService() {
            scope.launch {
                if (_status.value is ServerStatus.Running) {
                    runCatching { stop() }
                } else {
                    runCatching { start() }
                }
                refreshOverlay()
            }
        }
    }

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
        // Network + socket work must never run on the caller's dispatcher:
        // UI entry points call this from Main, where blocking I/O throws
        // NetworkOnMainThreadException and the start always "fails".
        withContext(Dispatchers.IO) {
            lock.withLock {
                startLocked()
            }
        }
    }

    private suspend fun startLocked() {
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
        // Bind first, then verify the real agent path (not just the socket):
        // a POST initialize + tools/list handshake like the reference build.
        val startError: String? = try {
            server.start(port, path)
            handshake(server.port, path)
            null
        } catch (t: Throwable) {
            explainStart(t, port)
        }
        if (startError != null) {
            runCatching { server.destroy() }
            _status.value = ServerStatus.Error(startError)
            return
        }
        this.server = server
        prefs.serviceWanted = true
        _status.value = ServerStatus.Running(
            server.port, endpointUrl(server.port, path), System.currentTimeMillis())
        McpService.start(appContext)
        McpNotifications.show(appContext, endpointUrl(server.port, path))
        refreshOverlay()
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
        overlayScope.launch {
            runCatching { overlay.detach() }
        }
        runCatching { appContext.stopService(Intent(appContext, McpService::class.java)) }
    }

    fun isWanted(): Boolean = prefs.serviceWanted

    /** Called from McpService.onCreate: re-anchor overlay + notification. */
    fun onServiceCreated() {
        McpNotifications.show(appContext, _status.value.endpointUrl)
        refreshOverlay()
    }

    /** Called from McpService.onDestroy: release all overlay views. */
    fun onServiceDestroyed() {
        overlayScope.launch {
            runCatching { overlay.detach() }
        }
    }

    /**
     * Shows the bubble when the user wants it and the overlay permission
     * allows; hides otherwise. Always runs on the overlay thread.
     */
    fun refreshOverlay() {
        overlayScope.launch {
            if (prefs.showOverlay && OverlayPermission.granted(appContext)) {
                runCatching { overlay.attach(overlayHost) }
            } else {
                runCatching { overlay.hideBubble() }
            }
            runCatching { overlay.refresh() }
        }
    }

    fun setOverlayVisible(visible: Boolean) {
        prefs.showOverlay = visible
        if (visible && _status.value !is ServerStatus.Running) {
            // Bubble without a server is pointless — start everything.
            scope.launch { runCatching { start() } }
            return
        }
        refreshOverlay()
    }

    /**
     * Backup-grade self-test: a real MCP handshake over loopback (POST
     * initialize + tools/list), retried briefly so a slow accept loop on
     * low-end devices can't flunk a healthy bind. Throws with the cause.
     */
    private suspend fun handshake(port: Int, path: String) {
        var last: Throwable? = null
        repeat(3) { attempt ->
            try {
                probeOnce(port, path)
                return
            } catch (t: Throwable) {
                last = t
                if (attempt < 2) delay(250)
            }
        }
        throw last ?: IllegalStateException("handshake failed")
    }

    private fun probeOnce(port: Int, path: String) {
        val init = postRpc(
            port, path, 1, "initialize",
            """"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"self-test","version":"1"}""",
        )
        check(init.contains("\"protocolVersion\"")) { "initialize returned: ${init.take(120)}" }
        val list = postRpc(port, path, 2, "tools/list", "")
        check(list.contains("\"tools\"")) { "tools/list returned: ${list.take(120)}" }
    }

    private fun postRpc(port: Int, path: String, id: Int, method: String, paramsJson: String): String {
        val body = if (paramsJson.isEmpty()) {
            """{"jsonrpc":"2.0","id":$id,"method":"$method"}"""
        } else {
            """{"jsonrpc":"2.0","id":$id,"method":"$method","params":{$paramsJson}}"""
        }
        java.net.Socket().use { s ->
            s.connect(
                java.net.InetSocketAddress(java.net.InetAddress.getByName("127.0.0.1"), port),
                2000,
            )
            s.soTimeout = 4000
            val b = body.toByteArray(Charsets.UTF_8)
            val out = s.getOutputStream()
            out.write(
                ("POST /$path HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\n" +
                    "Content-Length: ${b.size}\r\nConnection: close\r\n\r\n")
                    .toByteArray(Charsets.US_ASCII),
            )
            out.write(b)
            out.flush()
            val resp = s.getInputStream().readBytes().toString(Charsets.UTF_8)
            // Server answers HTTP/1.0; accept either framing on the 200.
            val statusLine = resp.lineSequence().firstOrNull().orEmpty()
            check(statusLine.contains(" 200")) { "HTTP $statusLine" }
            return resp.substringAfter("\r\n\r\n")
        }
    }

    private fun explainStart(t: Throwable, port: Int): String {
        val m = (t.message ?: t::class.java.simpleName).take(280)
        return when {
            m.contains("EPERM") ->
                "Android blocked the network socket (EPERM). The INTERNET permission is missing or revoked."
            t is java.net.BindException || m.contains("EADDRINUSE") ->
                "Port $port is already in use by another app. Choose a different port in Settings → MCP config."
            else -> "Start failed on :$port — $m"
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
