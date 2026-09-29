package com.obsidian.apkeditor.app

import android.app.Application
import android.content.Context
import com.obsidian.apkeditor.recovery.CrashReporter
import com.obsidian.apkeditor.system.StorageDirs
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Application entry point.
 *
 * Crash-path rule: [attachBaseContext] installs only the crash reporter.
 * Everything else (dirs, container) happens off the main thread or lazily.
 */
class ObsidianApp : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        CrashReporter.install(this)
    }

    override fun onCreate() {
        super.onCreate()
        // Best-effort, never blocks launch. Single shared executor lives
        // as long as the process, matching the app lifecycle.
        AppExecutors.io.execute {
            runCatching { StorageDirs.ensure(this) }
        }
    }

    /** App-scoped dependency graph. Created on first use, thread-safe. */
    val container: AppContainer by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AppContainer(this)
    }
}

/** Process-wide executors. Shut down never (process lifetime = executor lifetime). */
internal object AppExecutors {
    val io: Executor = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "obsidian-io").apply { isDaemon = true }
    }
}
