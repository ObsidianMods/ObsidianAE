package com.obsidian.apkeditor.tools

import android.net.Uri
import com.obsidian.apkeditor.mcp.ServiceController
import com.obsidian.apkeditor.ops.OperationTracker
import com.obsidian.apkeditor.system.FileScope
import com.obsidian.apkeditor.system.Prefs
import com.obsidian.apkeditor.system.StorageDirs
import com.obsidian.apkeditor.system.TreePaths
import com.obsidian.apkeditor.tools.packs.ApkTools
import com.obsidian.apkeditor.tools.packs.DexTools
import com.obsidian.apkeditor.tools.packs.EditTools
import com.obsidian.apkeditor.tools.packs.FileTools
import com.obsidian.apkeditor.tools.packs.MetaTools
import com.obsidian.apkeditor.tools.packs.ResTools
import com.obsidian.apkeditor.tools.packs.ShellTools
import com.obsidian.apkeditor.tools.packs.SmaliTools
import com.obsidian.apkeditor.work.WorkspaceRepository

/**
 * One place where packs are assembled. Adding a tool = new pack class +
 * one line here. Each pack receives only the collaborators it needs.
 */
object ToolPacks {

    fun registerAll(
        registry: ToolRegistry,
        appContext: android.content.Context,
        workspaces: WorkspaceRepository,
        operations: OperationTracker,
        prefs: Prefs,
        service: ServiceController,
        sessions: SessionStore,
    ) {
        // File tools see the default MCP root + every SAF-granted folder
        // (resolved live from prefs so grants apply without re-warm).
        // Shell tools share the same scope and grant callback.
        val fileScope = FileScope(StorageDirs.mcpRoot()) {
            prefs.folderGrants().mapNotNull { uriStr ->
                runCatching {
                    val uri = Uri.parse(uriStr)
                    val dir = TreePaths.toFile(uri) ?: return@mapNotNull null
                    FileScope.Root(TreePaths.alias(uri), dir)
                }.getOrNull()
            }.distinctBy { it.alias.lowercase() }
        }
        val onGrant: (String) -> Unit = { path ->
            runCatching { service.requestFolderGrant(path) }
        }
        FileTools(fileScope, onGrant).registerAll(registry)
        ShellTools(fileScope, onGrant).registerAll(registry)
        // APK + dex + edit packs need the workspace store.
        ApkTools(service.appContext, workspaces).registerAll(registry)
        DexTools(workspaces).registerAll(registry)
        SmaliTools(appContext, workspaces, sessions).registerAll(registry)
        ResTools(workspaces).registerAll(registry)
        EditTools(workspaces, operations, sessions).registerAll(registry)
        MetaTools(registry, operations).registerAll(registry)
        // Gating synced from prefs at every warm (service start re-syncs too).
        registry.syncDisabled(prefs.disabledTools(), prefs.disabledCapabilities())
    }
}
