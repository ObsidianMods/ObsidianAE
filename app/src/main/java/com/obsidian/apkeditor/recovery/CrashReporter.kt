package com.obsidian.apkeditor.recovery

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.Process
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess

/**
 * Crash + ANR reporting, mirroring the original crash-handler boundaries:
 * installed with two lines in [ObsidianApp.onCreate], lives entirely in the
 * application class. No activity ever references this object.
 *
 * Kept improvements over the original (all app-side): chains to the previous
 * handler, ANRs log to a separate file, one in-process relaunch breaker.
 */
object CrashReporter {

    private val installed = AtomicBoolean(false)
    /** In-process relaunch breaker: a crashing recovery screen must not bounce. */
    private val relaunches = AtomicInteger(0)

    fun install(ctx: Context) {
        if (!installed.compareAndSet(false, true)) return
        val appCtx = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            handleCrash(appCtx, thread, error, previous)
        }
        AnrWatch(appCtx).start()
    }

    private fun handleCrash(
        ctx: Context,
        thread: Thread,
        error: Throwable,
        previous: Thread.UncaughtExceptionHandler?,
    ) {
        try {
            logAndLaunch(ctx, buildLog(thread, error, "CRASH"), fatal = true)
        } catch (_: Exception) {
            // Handling itself failed (e.g. truly out of memory): fall back to
            // the system behavior instead of hanging or swallowing the crash.
            try {
                previous?.uncaughtException(thread, error)
            } catch (_: Exception) {
            }
            Process.killProcess(Process.myPid())
            exitProcess(10)
        }
    }

    /** Readable log line. Used for real crashes and ANR reports. */
    fun buildLog(thread: Thread, error: Throwable, type: String): String {
        val sw = java.io.StringWriter()
        error.printStackTrace(java.io.PrintWriter(sw))
        val sb = StringBuilder(4096)
        sb.append("=== ").append(type).append(" ===\n")
        sb.append("Time: ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            .format(Date())).append('\n')
        sb.append("Thread: ").append(thread.name).append('\n')
        sb.append("Device: ").append(android.os.Build.MANUFACTURER).append(' ')
            .append(android.os.Build.MODEL).append('\n')
        sb.append("Android: ").append(android.os.Build.VERSION.RELEASE)
            .append(" (SDK ").append(android.os.Build.VERSION.SDK_INT).append(")\n\n")
        sb.append(sw)
        return sb.toString()
    }

    /**
     * Writes the log and opens [RecoveryActivity].
     * @param fatal if true, kills the process afterward; if false (ANR),
     * leaves the app running.
     */
    fun logAndLaunch(ctx: Context, log: String, fatal: Boolean) {
        val app = ctx.applicationContext
        if (fatal) {
            CrashStore.writeCrash(app, log)
        } else {
            CrashStore.writeAnr(app, log)
        }
        // Breaker: only the first crash in this process may relaunch the UI.
        if (fatal && relaunches.getAndIncrement() != 0) {
            Process.killProcess(Process.myPid())
            exitProcess(10)
        }
        try {
            val intent = Intent(app, RecoveryActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra(RecoveryActivity.EXTRA_LOG, log)
            app.startActivity(intent)
            if (fatal) {
                try {
                    Thread.sleep(350)
                } catch (_: InterruptedException) {
                }
            }
        } catch (_: Exception) {
            // Background-start blocked or worse: don't hang here.
        }
        if (fatal) {
            Process.killProcess(Process.myPid())
            exitProcess(10)
        }
    }

    /**
     * Main-thread responsiveness probe. Pings every 5s; a missed window is
     * reported as an ANR (log + recovery screen, process left alive).
     * Interruptible thread — stops with the process, never outlives it.
     */
    private class AnrWatch(ctx: Context) : Thread("obsidian-anr-watch") {

        private val appCtx = ctx.applicationContext
        private val mainHandler = Handler(Looper.getMainLooper())

        @Volatile
        private var tick = 0

        @Volatile
        private var responded = false

        init {
            isDaemon = true
        }

        override fun run() {
            // Startup grace: cold start must never false-positive.
            try {
                sleep(12_000)
            } catch (_: InterruptedException) {
                return
            }
            while (!isInterrupted) {
                val current = ++tick
                responded = false
                mainHandler.post {
                    if (current == tick) responded = true
                }
                try {
                    sleep(5_000)
                } catch (_: InterruptedException) {
                    return
                }
                if (!responded) {
                    val mainThread = Looper.getMainLooper().thread
                    val detail = buildLog(mainThread, AnrError(mainThread.stackTrace), "ANR") +
                        memLine(appCtx)
                    try {
                        logAndLaunch(appCtx, detail, fatal = false)
                    } catch (_: Exception) {
                    }
                    // Don't spam while still frozen; wait it out.
                    try {
                        sleep(5_000)
                    } catch (_: InterruptedException) {
                        return
                    }
                }
            }
        }

        private fun memLine(ctx: Context): String {
            return try {
                val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val mem = ActivityManager.MemoryInfo()
                am.getMemoryInfo(mem)
                "\nlowMem=${mem.lowMemory}\n"
            } catch (_: Exception) {
                ""
            }
        }

        private class AnrError(stackTrace: Array<StackTraceElement>) : Error("Application Not Responding") {
            init {
                setStackTrace(stackTrace)
            }
        }
    }
}
