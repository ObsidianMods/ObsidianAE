package com.obsidian.apkeditor.mcp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers

/**
 * Loopback-only HTTP server. Fixes the reference failure modes:
 * - Accept failures back off instead of killing the server after 10.
 * - Concurrent connections capped ([MAX_CONNECTIONS]); overflow gets 503-style 400.
 * - Per-connection socket timeout so stalled clients cannot pin threads.
 */
class McpServer(
    private val router: JsonRpcRouter,
    parentContext: CoroutineContext,
) {
    private val scope = CoroutineScope(parentContext + Job())
    private val inFlight = Semaphore(MAX_CONNECTIONS)
    private val served = AtomicLong(0)

    @Volatile
    private var socket: ServerSocket? = null

    @Volatile
    var port: Int = -1
        private set

    fun isAlive(): Boolean = socket?.let { !it.isClosed && it.isBound } ?: false

    fun servedCalls(): Long = served.get()

    suspend fun start(port: Int, path: String) {
        stop()
        val server = ServerSocket()
        server.reuseAddress = true
        server.bind(InetSocketAddress("127.0.0.1", port))
        socket = server
        this.port = server.localPort
        val prefix = "/" + path.trim('/')
        scope.launch(Dispatchers.IO) { acceptLoop(server, prefix) }
    }

    fun stop() {
        runCatching { socket?.close() }
        socket = null
        port = -1
    }

    fun destroy() {
        stop()
        scope.coroutineContext[Job]?.cancel()
    }

    private suspend fun acceptLoop(server: ServerSocket, prefix: String) {
        var backoffMs = 100L
        while (scope.isActive && !server.isClosed) {
            try {
                val client = server.accept()
                backoffMs = 100L
                scope.launch(Dispatchers.IO) { handle(client, prefix) }
            } catch (e: IOException) {
                if (server.isClosed || !scope.isActive) break
                // Back off, never die (reference broke the loop after 10).
                kotlinx.coroutines.delay(backoffMs)
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
