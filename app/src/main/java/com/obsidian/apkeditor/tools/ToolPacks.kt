package com.obsidian.apkeditor.tools

import com.obsidian.apkeditor.mcp.ServiceController
import com.obsidian.apkeditor.ops.OperationTracker
import com.obsidian.apkeditor.system.Prefs
import com.obsidian.apkeditor.tools.packs.ApkTools
import com.obsidian.apkeditor.tools.packs.EditTools
import com.obsidian.apkeditor.tools.packs.FileTools
import com.obsidian.apkeditor.tools.packs.MetaTools
import com.obsidian.apkeditor.work.WorkspaceRepository

/**
 * One place where packs are assembled. Adding a tool = new pack class +
 * one line here. Each pack receives only the collaborators it needs.
 */
object ToolPacks {

    fun registerAll(
        registry: ToolRegistry,
        workspaces: WorkspaceRepository,
        operations: OperationTracker,
        prefs: Prefs,
        service: ServiceController,
    ) {
        // File tools need no context.
        FileTools().registerAll(registry)
        // APK + edit packs need the workspace store.
        ApkTools(service.appContext, workspaces).registerAll(registry)
        EditTools(workspaces, operations).registerAll(registry)
        MetaTools(registry, operations).registerAll(registry)
        // Gating synced from prefs at every warm (service start re-syncs too).
        registry.syncDisabled(prefs.disabledTools(), prefs.disabledCapabilities())
    }
}
