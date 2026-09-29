package com.obsidian.apkeditor.recovery

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.Process
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess

/**
 * Crash + ANR reporting. Installed once from [ObsidianApp.attachBaseContext].
 *
 * Differences from the reference implementation:
 * - Chains to the previous handler instead of only killing (lets the system
 *   report normally when recovery display cannot start).
 * - ANRs are logged to a SEPARATE file and never auto-launch an activity
 *   (no task-wipe while the process is alive, no evidence overwrite).
 * - Watchdog is a daemon thread with a shutdown flag; the reference thread
 *   could never stop.
 */
object CrashReporter {

    private val installed = AtomicBoolean(false)
    private val watch = AnrWatch()

    fun install(ctx: Context) {
        if (!installed.compareAndSet(false, true)) return
        val appCtx = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            handleCrash(appCtx, thread, error, previous)
        }
        watch.start(appCtx)
    }

    private fun handleCrash(
        ctx: Context,
        thread: Thread,
        error: Throwable,
        previous: Thread.UncaughtExceptionHandler?,
    ) {
        val safeMode = try {
            CrashStore.writeCrash(ctx, thread, error)
        } catch (_: Exception) {
            false
        }
        try {
            val intent = Intent(ctx, RecoveryActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra(RecoveryActivity.EXTRA_SAFE_MODE, safeMode)
            ctx.startActivity(intent)
            // Brief pause so ActivityManager can act before the process dies.
            try {
                Thread.sleep(350)
            } catch (_: InterruptedException) {
            }
        } catch (_: Exception) {
            // Background-start blocked or worse: fall through to previous handler.
        }
        try {
            previous?.uncaughtException(thread, error)
        } catch (_: Exception) {
        }
        Process.killProcess(Process.myPid())
        exitProcess(10)
    }

    /**
     * Main-thread responsiveness probe. Pings the main looper every 5s after a
     * 12s startup grace; a missed 8s window is recorded as an ANR (log only).
     */
    private class AnrWatch {
        private val tick = AtomicInteger(0)
        private val running = AtomicBoolean(false)

        fun start(ctx: Context) {
            if (!running.compareAndSet(false, true)) return
            val appCtx = ctx.applicationContext
            val main = Handler(Looper.getMainLooper())
            val worker = Thread({
                try {
                    Thread.sleep(12_000)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                while (running.get()) {
                    val seen = tick.get()
                    main.post { tick.incrementAndGet() }
                    try {
                        Thread.sleep(8_000)
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                    if (running.get() && tick.get() == seen) {
                        val detail = buildDetail(appCtx)
                        CrashStore.writeAnr(appCtx, detail)
                        // Wait out the stall instead of hammering the log.
                        try {
                            Thread.sleep(15_000)
                        } catch (_: InterruptedException) {
                            return@Thread
                        }
                    } else {
                        try {
                            Thread.sleep(5_000)
                        } catch (_: InterruptedException) {
                            return@Thread
                        }
                    }
                }
            }, "obsidian-anr-watch").apply { isDaemon = true }
            worker.start()
        }

        private fun buildDetail(ctx: Context): String {
            val sb = StringBuilder(1024)
            sb.append("main-blocked\n")
            try {
                val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val mem = ActivityManager.MemoryInfo()
                am.getMemoryInfo(mem)
                sb.append("lowMem=").append(mem.lowMemory).append('\n')
            } catch (_: Exception) {
            }
            val main = Looper.getMainLooper().thread
            for (f in main.stackTrace.take(30)) sb.append("    at ").append(f.toString()).append('\n')
            return sb.toString()
        }
    }
}
