package com.obsidian.apkeditor.mcp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers

/**
 * Loopback-only HTTP server. Fixes the reference failure modes:
 * - Accept loop is immortal: EVERY throwable is caught and backed off, so
 *   the loop can never die while the socket stays bound (that state accepts
 *   TCP but returns zero bytes — the worst failure mode, now impossible).
 * - A watchdog ([ensureAccepting]) relaunches the loop if it ever stops.
 * - Concurrent connections capped ([MAX_CONNECTIONS]); overflow gets 400.
 * - Per-connection socket timeout so stalled clients cannot pin threads.
 */
class McpServer(
    private val router: JsonRpcRouter,
    parentContext: CoroutineContext,
) {
    private val scope = CoroutineScope(parentContext + Job())
    private val inFlight = Semaphore(MAX_CONNECTIONS)
    private val served = AtomicLong(0)
    private val acceptErrors = AtomicLong(0)
    private val lastAcceptError = AtomicReference("")
    private val startedAt = AtomicLong(0)

    @Volatile
    private var socket: ServerSocket? = null

    @Volatile
    private var prefix = "/mcp"

    @Volatile
    private var loopJob: Job? = null

    @Volatile
    var port: Int = -1
        private set

    /** Bound socket AND a live accept loop — the only true "serving" state. */
    fun isAlive(): Boolean {
        val s = socket
        return s != null && !s.isClosed && s.isBound && loopJob?.isActive == true
    }

    fun servedCalls(): Long = served.get()
    fun acceptErrorCount(): Long = acceptErrors.get()
    fun lastAcceptError(): String = lastAcceptError.get()
    fun uptimeMs(): Long = startedAt.get().let { if (it == 0L) 0 else System.currentTimeMillis() - it }

    suspend fun start(port: Int, path: String) {
        stop()
        val server = ServerSocket()
        server.reuseAddress = true
        server.bind(InetSocketAddress("127.0.0.1", port))
        socket = server
        this.port = server.localPort
        prefix = "/" + path.trim('/')
        startedAt.set(System.currentTimeMillis())
        ensureAccepting()
    }

    /**
     * Relaunches the accept loop if it stopped while the socket is still
     * bound. Called at start and by the controller watchdog.
     */
    fun ensureAccepting() {
        val server = socket ?: return
        if (server.isClosed || !scope.isActive) return
        val job = loopJob
        if (job?.isActive == true) return
        loopJob = scope.launch(Dispatchers.IO) { acceptLoop(server) }
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
        runCatching { socket?.close() }
        socket = null
        port = -1
    }

    fun destroy() {
        stop()
        scope.coroutineContext[Job]?.cancel()
    }

    private suspend fun acceptLoop(server: ServerSocket) {
        var backoffMs = 100L
        while (scope.isActive && !server.isClosed) {
            try {
                val client = server.accept()
                backoffMs = 100L
                scope.launch(Dispatchers.IO) { handle(client, prefix) }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // Anything else (IO failure, runtime bug, launch on a dying
                // scope): count it, back off, and KEEP ACCEPTING. A dead loop
                // on a bound socket is the zero-bytes hang — never again.
                if (server.isClosed || !scope.isActive) break
                acceptErrors.incrementAndGet()
                lastAcceptError.set((t.message ?: t.javaClass.simpleName).take(200))
                try {
                    delay(backoffMs)
                } catch (e: CancellationException) {
                    throw e
                }
                backoffMs = minOf(backoffMs * 2, 5_000L)
            }
        }
    }

    private suspend fun handle(client: Socket, prefix: String) {
        if (!inFlight.tryAcquire()) {
            runCatching {
                client.getOutputStream().use { HttpCodec.writeEmpty(it, 400) }
                client.close()
            }
            return
        }
        try {
            client.soTimeout = 30_000
            client.use { sock ->
                val req = sock.getInputStream().let { HttpCodec.readRequest(sock, it) }
                val out = sock.getOutputStream()
                if (req == null) {
                    HttpCodec.writeEmpty(out, 400)
                    return
                }
                val origin = req.headers["origin"]
                if (!LoopbackGuard.isAllowed(origin)) {
                    HttpCodec.writeJson(out, 403, """{"error":"forbidden origin"}""")
                    return
                }
                if (req.method == "OPTIONS") {
                    HttpCodec.writeOptions(out, origin)
                    return
                }
                val cleanPath = req.path.substringBefore('?')
                if (cleanPath != prefix && cleanPath != "$prefix/") {
                    HttpCodec.writeJson(out, 404, """{"error":"unknown path"}""", origin)
                    return
                }
                when (req.method) {
                    "GET" -> HttpCodec.writeJson(out, 200, router.toolListJson(), origin)
                    "POST" -> {
                        val body = req.body.toString(Charsets.UTF_8)
                        val outcome = router.handleJsonRpc(body)
                        served.incrementAndGet()
                        if (outcome.json == null) HttpCodec.writeEmpty(out, outcome.status, origin)
                        else HttpCodec.writeJson(out, outcome.status, outcome.json, origin)
                    }
                    else -> HttpCodec.writeEmpty(out, 405, origin)
                }
            }
        } catch (_: Exception) {
        } finally {
            inFlight.release()
        }
    }

    companion object {
        private const val MAX_CONNECTIONS = 16
    }
}
