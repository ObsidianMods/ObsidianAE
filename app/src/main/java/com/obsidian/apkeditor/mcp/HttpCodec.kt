package com.obsidian.apkeditor.mcp

import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.charset.StandardCharsets

/** Minimal HTTP/1.0 codec. Split from routing (the reference fused both). */
object HttpCodec {

    const val MAX_BODY = 1024 * 1024
    private const val MAX_LINE = 8192

    data class Request(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: ByteArray,
    )

    fun readRequest(sock: Socket, ins: InputStream): Request? {
        val start = readLine(ins) ?: return null
        val parts = start.split(' ')
        if (parts.size < 2) return null
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(ins) ?: return null
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
            if (headers.size > 64) break
        }
        val len = headers["content-length"]?.toIntOrNull()?.coerceIn(0, MAX_BODY) ?: 0
        val chunked = headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true
        val body = when {
            chunked -> readChunked(ins) ?: return null
            len > 0 -> ins.readN(len)
            else -> ByteArray(0)
        }
        return Request(parts[0].uppercase(), parts[1], headers, body)
    }

    fun writeJson(out: OutputStream, status: Int, json: String, origin: String? = null) {
        val bytes = json.toByteArray(StandardCharsets.UTF_8)
        val head = "HTTP/1.0 $status ${reason(status)}\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            corsAllow(origin) +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray(StandardCharsets.US_ASCII))
        out.write(bytes)
        out.flush()
    }

    fun writeEmpty(out: OutputStream, status: Int, origin: String? = null) {
        out.write(("HTTP/1.0 $status ${reason(status)}\r\n" + corsAllow(origin) +
            "Connection: close\r\n\r\n").toByteArray(StandardCharsets.US_ASCII))
        out.flush()
    }

    /** Preflight answer. Only loopback origins get an allow header (DNS-rebind guard). */
    fun writeOptions(out: OutputStream, origin: String?) {
        val head = "HTTP/1.0 204 No Content\r\n" +
            corsAllow(origin) +
            "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
            "Access-Control-Allow-Headers: Content-Type\r\n" +
            "Access-Control-Max-Age: 86400\r\n" +
            "Content-Length: 0\r\n" +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray(StandardCharsets.US_ASCII))
        out.flush()
    }

    private fun corsAllow(origin: String?): String {
        if (origin.isNullOrBlank() || !LoopbackGuard.isAllowed(origin)) return ""
        return "Access-Control-Allow-Origin: $origin\r\n"
    }

    private fun reason(status: Int): String = when (status) {
        200 -> "OK"
        202 -> "Accepted"
        400 -> "Bad Request"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        else -> "Error"
    }

    /** Chunked request bodies (OkHttp/httpx streaming clients). Null when oversized/broken. */
    private fun readChunked(ins: InputStream): ByteArray? {
        val out = java.io.ByteArrayOutputStream(minOf(MAX_BODY, 65_536))
        var total = 0
        while (true) {
            val sizeLine = readLine(ins)?.substringBefore(';')?.trim() ?: return null
            val size = sizeLine.toIntOrNull(16) ?: return null
            if (size < 0 || total + size > MAX_BODY) return null
            if (size == 0) {
                // Consume trailers to the blank line.
                while (true) {
                    val trailer = readLine(ins) ?: return null
                    if (trailer.isEmpty()) break
                }
                break
            }
            out.write(ins.readN(size))
            total += size
            // Each chunk is followed by CRLF.
            val cr = ins.read()
            val lf = ins.read()
            if (cr != '\r'.code || lf != '\n'.code) return null
        }
        return out.toByteArray()
    }

    private fun readLine(ins: InputStream): String? {
        val sb = StringBuilder()
        var prev = -1
        while (sb.length < MAX_LINE) {
            val b = ins.read()
            if (b < 0) return if (sb.isEmpty() && prev < 0) null else sb.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
            prev = b
        }
        return sb.toString()
    }

    private fun InputStream.readN(n: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(minOf(n, 65_536))
        val buf = ByteArray(8192)
        var remaining = n
        while (remaining > 0) {
            val r = read(buf, 0, minOf(buf.size, remaining))
            if (r < 0) break
            out.write(buf, 0, r)
            remaining -= r
        }
        return out.toByteArray()
    }
}
