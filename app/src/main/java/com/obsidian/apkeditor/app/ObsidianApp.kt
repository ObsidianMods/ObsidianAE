package com.obsidian.apkeditor.app

import android.app.Application
import com.obsidian.apkeditor.recovery.CrashReporter
import com.obsidian.apkeditor.system.StorageDirs
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Application entry point. Crash reporting is installed here and only here
 * (two lines, mirroring the original crash-handler setup) — no activity
 * references crash code, and nothing crash-related runs during any
 * activity's startup path.
 */
class ObsidianApp : Application() {

    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
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
