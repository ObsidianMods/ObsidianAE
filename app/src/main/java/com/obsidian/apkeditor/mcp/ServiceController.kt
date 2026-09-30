package com.obsidian.apkeditor.mcp

import android.content.Context
import android.content.Intent
import com.obsidian.apkeditor.mcp.overlay.AssistantOverlay
import com.obsidian.apkeditor.mcp.overlay.OverlayPermission
import com.obsidian.apkeditor.system.GrantRequests
import com.obsidian.apkeditor.system.Prefs
import com.obsidian.apkeditor.tools.ToolRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
     * Home for every overlay call: the MAIN thread. WindowManager.addView and
     * AlertDialog need a Looper; the previous plain executor thread had none,
     * so addView threw (swallowed by runCatching) and the bubble never showed.
     */
    private val overlayScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var serverJob: Job? = null
    private val lock = Mutex()

    private var server: McpServer? = null
    private var registryProvider: (() -> ToolRegistry)? = null
    private val overlay = AssistantOverlay(app.applicationContext)

    private val overlayHost = object : AssistantOverlay.Host {
        override val prefs: Prefs get() = this@ServiceController.prefs
        override fun status(): ServerStatus = _status.value
        override fun isRunning(): Boolean = _status.value is ServerStatus.Running
        override fun endpointUrl(): String = _status.value.endpointUrl
        override fun hasPendingGrant(): Boolean = GrantRequests.pending.value != null
        override fun tools(): List<com.obsidian.apkeditor.tools.ToolDefinition> =
            runCatching { registryProvider?.invoke()?.all().orEmpty() }.getOrDefault(emptyList())
        override fun onToggleService() {
            scope.launch {
                // Panel Stop only stops the endpoint: the bubble stays so the
                // user can start it again from the same panel.
                if (_status.value is ServerStatus.Running) {
                    runCatching { stop(exit = false) }
                } else {
                    runCatching { start() }
                }
                refreshOverlay()
            }
        }
        override fun onApplyConfig(port: Int, path: String) {
            scope.launch { runCatching { applyConfig(port, path) } }
        }
        override fun onGatingChanged() {
            runCatching {
                registryProvider?.invoke()
                    ?.syncDisabled(prefs.disabledTools(), prefs.disabledCapabilities())
            }
        }
        override fun onHideBubble() {
            prefs.showOverlay = false
            refreshOverlay()
            if (_status.value !is ServerStatus.Running) {
                runCatching { appContext.stopService(Intent(appContext, McpService::class.java)) }
            }
        }
    }


    private val _status = MutableStateFlow<ServerStatus>(ServerStatus.Stopped)
    val statusFlow: StateFlow<ServerStatus> = _status.asStateFlow()

    init {
        // Any status transition repaints bubble dot + open control panel.
        scope.launch { _status.collect { refreshOverlay() } }
    }

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

    /**
     * [exit] = true tears everything down (server, bubble, service).
     * false stops only the endpoint and keeps the bubble/service so the
     * control panel can start it again.
     */
    suspend fun stop(exit: Boolean = true) {
        lock.withLock { stopLocked(exit) }
    }

    /** Persists a new port/path and restarts the endpoint if it was running. */
    suspend fun applyConfig(port: Int, path: String) {
        val wasRunning = _status.value is ServerStatus.Running
        prefs.servicePort = port
        prefs.endpointPath = path
        if (wasRunning) start() else refreshOverlay()
    }

    /** True while the bubble should exist (wanted + permission granted). */
    fun overlayWanted(): Boolean = prefs.showOverlay && OverlayPermission.granted(appContext)

    fun stopAsync() {
        scope.launch { stop() }
    }

    suspend fun resumeIfWanted() {
        if (!prefs.serviceWanted) return
        if (_status.value is ServerStatus.Running) return
        start()
    }

    fun onTaskRemoved() {
        // Foreground survival: a wanted endpoint outlives a swipe-away
        // (Mod-Menu pattern uses stopWithTask=true because a game crash is
        // worse than a kill; for an MCP server the opposite holds — a kill
        // IS the bug). Only honor stopOnTaskRemoved when nothing is wanted.
        if (isWanted()) return
        if (prefs.stopOnTaskRemoved) stopAsync()
    }

    private fun stopServerLocked() {
        runCatching { server?.destroy() }
        server = null
    }

    private fun stopLocked(exit: Boolean) {
        serverJob?.cancel()
        serverJob = null
        stopServerLocked()
        prefs.serviceWanted = false
        _status.value = ServerStatus.Stopped
        McpNotifications.cancel(appContext)
        if (exit || !overlayWanted()) {
            overlayScope.launch { runCatching { overlay.detach() } }
            runCatching { appContext.stopService(Intent(appContext, McpService::class.java)) }
        } else {
            refreshOverlay()
        }
    }

    fun isWanted(): Boolean = prefs.serviceWanted

    /** Called from McpService.onCreate: re-anchor overlay + notification. */
    fun onServiceCreated() {
        if (_status.value is ServerStatus.Running) {
            McpNotifications.show(appContext, _status.value.endpointUrl)
        }
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
            if (overlayWanted()) {
                // The bubble lives in the service: make sure one exists so the
                // window (and process) survive the app leaving the foreground.
                if (!overlay.isShowing()) startService(appContext)
                runCatching { overlay.attach(overlayHost) }
                    .onFailure { android.util.Log.w("ObsidianOverlay", "attach failed", it) }
            } else {
                runCatching { overlay.hideBubble() }
            }
            runCatching { overlay.refresh() }
        }
    }

    fun setOverlayVisible(visible: Boolean) {
        prefs.showOverlay = visible
        if (visible) {
            // Make sure the service exists to host the window, then attach.
            startService(appContext)
        }
        refreshOverlay()
    }

    /**
     * Called when a tool hits an ungranted folder. Raises the grant sheet
     * (immediate if the app is foregrounded) and pings once per distinct
     * path via notification + amber bubble dot. Never throws.
     */
    fun requestFolderGrant(path: String) {
        val fresh = runCatching { GrantRequests.raise(path) }.getOrDefault(false)
        runCatching { refreshOverlay() }
        if (fresh) runCatching { McpNotifications.grantRequest(appContext, path) }
    }

    /** Called when the grant sheet resolves (granted or dismissed). */
    fun onGrantResolved() {
        runCatching { GrantRequests.clear() }
        runCatching { McpNotifications.cancelGrant(appContext) }
        runCatching { refreshOverlay() }
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
        /**
         * Foreground start (Mod-Menu pattern: Activity is UI-only, Service
         * owns the server+overlay). startForegroundService on O+ is
         * mandatory — plain startService from background throws
         * BackgroundServiceStartNotAllowedException on API 31+ and the
         * service dies minimized. The Service must call startForeground
         * within ~5s (McpService.onCreate does).
         */
        fun startService(context: Context) {
            val intent = Intent(context, McpService::class.java)
            try {
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (t: Throwable) {
                // Last resort: plain start (foreground app, old API).
                runCatching { context.startService(intent) }
                    .onFailure { android.util.Log.w("ObsidianService", "startService refused", it) }
                if (t is IllegalStateException) {
                    android.util.Log.w("ObsidianService", "FGS start refused", t)
                }
            }
        }
    }
}
