package com.obsidian.apkeditor.recovery

import android.content.Context
import java.io.File

/**
 * File-backed crash state. No preferences, no threads, no singletons holding
 * contexts — every function takes what it needs and closes what it opens.
 *
 * Files (under `<filesDir>/crash/`):
 * - `crash_log.txt` — last fatal crash report (overwritten per crash).
 * - `anr_log.txt`   — last ANR report (separate file: ANRs never overwrite crashes).
 * - `pending`       — name of the log awaiting display (`crash` or `anr`).
 * - `crashes.idx`   — crash timestamps (millis, one per line, capped at 20).
 */
object CrashStore {

    const val KIND_CRASH = "crash"
    const val KIND_ANR = "anr"

    /** Consecutive crashes inside this window trigger safe mode. */
    private const val SAFE_WINDOW_MS = 60_000L
    private const val SAFE_COUNT = 3
    private const val MAX_LOG_CHARS = 32_768
    private const val MAX_INDEX_ROWS = 20

    data class Pending(val kind: String, val text: String)

    private fun dir(ctx: Context): File = File(ctx.filesDir, "crash")

    private fun file(ctx: Context, name: String): File = File(dir(ctx), name)

    /** Records a fatal crash. Returns true when the app should boot into safe mode. */
    fun writeCrash(ctx: Context, thread: Thread, error: Throwable): Boolean {
        val report = buildReport("FATAL", thread, error).take(MAX_LOG_CHARS)
        return try {
            dir(ctx).mkdirs()
            file(ctx, "crash_log.txt").writeText(report)
            // Marker AFTER the log so a viewer crash cannot re-arm without evidence.
            file(ctx, "pending").writeText(KIND_CRASH)
            appendTimestamp(ctx, System.currentTimeMillis())
            inSafeMode(ctx)
        } catch (_: Exception) {
            false
        }
    }

    /** Records an ANR. Never touches the crash log or the pending marker. */
    fun writeAnr(ctx: Context, detail: String) {
        try {
            dir(ctx).mkdirs()
            val head = "ANR ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date())}\n"
            file(ctx, "anr_log.txt").writeText((head + detail).take(MAX_LOG_CHARS))
        } catch (_: Exception) {
        }
    }

    /**
     * Consumes the pending marker. The marker is deleted BEFORE the log is read,
     * so a crash inside the recovery screen cannot loop.
     */
    fun consumePending(ctx: Context): Pending? {
        return try {
            val marker = file(ctx, "pending")
            if (!marker.exists()) return null
            val kind = marker.readText().trim().take(16)
            marker.delete()
            val logName = if (kind == KIND_ANR) "anr_log.txt" else "crash_log.txt"
            val text = file(ctx, logName).takeIf { it.exists() }?.readText().orEmpty()
            if (text.isEmpty()) null else Pending(kind.ifEmpty { KIND_CRASH }, text)
        } catch (_: Exception) {
            null
        }
    }

    /** Last fatal report without consuming anything. */
    fun lastFatal(ctx: Context): String {
        return try {
            file(ctx, "crash_log.txt").takeIf { it.exists() }?.readText().orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

    /** True when [SAFE_COUNT] or more crashes landed inside [SAFE_WINDOW_MS]. */
    fun inSafeMode(ctx: Context): Boolean {
        return try {
            val now = System.currentTimeMillis()
            val recent = file(ctx, "crashes.idx")
                .takeIf { it.exists() }
                ?.readLines().orEmpty()
                .mapNotNull { it.trim().toLongOrNull() }
                .filter { now - it in 0..SAFE_WINDOW_MS }
            recent.size >= SAFE_COUNT
        } catch (_: Exception) {
            false
        }
    }

    private fun appendTimestamp(ctx: Context, now: Long) {
        try {
            val idx = file(ctx, "crashes.idx")
            val rows = idx.takeIf { it.exists() }?.readLines().orEmpty().takeLast(MAX_INDEX_ROWS - 1)
            idx.writeText((rows + now.toString()).joinToString("\n"))
        } catch (_: Exception) {
        }
    }

    private fun buildReport(kind: String, thread: Thread, error: Throwable): String {
        val sb = StringBuilder(4096)
        sb.append(kind).append(' ')
            .append(android.os.Build.MANUFACTURER).append(' ')
            .append(android.os.Build.MODEL).append(" API ")
            .append(android.os.Build.VERSION.SDK_INT).append('\n')
        sb.append("thread=").append(thread.name).append('\n')
        appendTrace(sb, error, 0)
        var cause = error.cause
        var depth = 0
        while (cause != null && depth < 4) {
            sb.append("Caused by: ")
            appendTrace(sb, cause, 0)
            cause = cause.cause
            depth++
        }
        return sb.toString()
    }

    private fun appendTrace(sb: StringBuilder, e: Throwable, skip: Int) {
        sb.append(e.javaClass.name).append(": ").append(e.message).append('\n')
        val frames = e.stackTrace.drop(skip).take(40)
        for (f in frames) sb.append("    at ").append(f.toString()).append('\n')
    }
}
