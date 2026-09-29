package com.obsidian.apkeditor.recovery

import android.content.Context
import java.io.File

/**
 * File-backed crash state. Application class and recovery screen only —
 * never referenced from MainActivity or any other UI.
 *
 * Files (under `<filesDir>/crash/`):
 * - `crash_log.txt` — last fatal crash report (overwritten per crash).
 * - `anr_log.txt`   — last ANR report (separate file, never overwrites crashes).
 * - `crashes.idx`   — crash timestamps (millis, one per line, capped at 20).
 */
object CrashStore {

    const val KIND_CRASH = "crash"
    const val KIND_ANR = "anr"

    /** Consecutive crashes inside this window flag safe mode (recovery UI only). */
    private const val SAFE_WINDOW_MS = 60_000L
    private const val SAFE_COUNT = 3
    private const val MAX_LOG_CHARS = 32_768
    private const val MAX_INDEX_ROWS = 20

    private fun dir(ctx: Context): File = File(ctx.filesDir, "crash")

    private fun file(ctx: Context, name: String): File = File(dir(ctx), name)

    /** Writes the fatal log and records a timestamp. Never throws. */
    fun writeCrash(ctx: Context, report: String) {
        try {
            dir(ctx).mkdirs()
            file(ctx, "crash_log.txt").writeText(report.take(MAX_LOG_CHARS))
            appendTimestamp(ctx, System.currentTimeMillis())
        } catch (_: Exception) {
        }
    }

    /** Writes the ANR log. Never touches the crash log. Never throws. */
    fun writeAnr(ctx: Context, report: String) {
        try {
            dir(ctx).mkdirs()
            file(ctx, "anr_log.txt").writeText(report.take(MAX_LOG_CHARS))
        } catch (_: Exception) {
        }
    }

    /** Last fatal report, or empty. Never throws. */
    fun readLastLog(ctx: Context): String {
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
}
