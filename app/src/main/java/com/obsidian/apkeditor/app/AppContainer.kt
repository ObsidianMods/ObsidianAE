package com.obsidian.apkeditor.app

import android.content.Context
import com.obsidian.apkeditor.mcp.ServiceController
import com.obsidian.apkeditor.ops.OperationTracker
import com.obsidian.apkeditor.system.Prefs
import com.obsidian.apkeditor.tools.SessionStore
import com.obsidian.apkeditor.tools.ToolPacks
import com.obsidian.apkeditor.tools.ToolRegistry
import com.obsidian.apkeditor.work.WorkspaceRepository
import com.obsidian.apkeditor.work.ZipWorkspaces

/**
 * Explicit dependency graph — the anti-[god-object].
 *
 * Differences from the reference implementation:
 * - No `object` singleton with `lateinit` backends; a plain class owned by [ObsidianApp].
 * - Only the application context is retained (never an Activity/View).
 * - Construction does no I/O and launches no threads; [warm] is explicit and cancellable
 *   by the caller (MainActivity lifecycle scope), never fire-and-forget here.
 */
class AppContainer(app: Context) {

    val appContext: Context = app.applicationContext
    val prefs: Prefs = Prefs(appContext)
    val operations: OperationTracker = OperationTracker()
    val workspaces: WorkspaceRepository = ZipWorkspaces(appContext)
    val tools: ToolRegistry = ToolRegistry()
    val sessions: SessionStore = SessionStore()
    val service: ServiceController = ServiceController(appContext, prefs)

    /** Registers all tool packs. Pure registration, no I/O. */
    fun warm() {
        ToolPacks.registerAll(tools, appContext, workspaces, operations, prefs, service, sessions)
        service.attachRegistry { tools }
    }
}
