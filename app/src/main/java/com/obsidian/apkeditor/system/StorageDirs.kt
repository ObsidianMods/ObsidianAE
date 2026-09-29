package com.obsidian.apkeditor.system

import android.content.Context
import java.io.File

/**
 * Storage locations. Single source of truth for directories (the reference
 * hardcoded `/storage/emulated/0/...` in several places; here it lives once).
 */
object StorageDirs {

    /** Public MCP drop folder (APKs in, service scope root). */
    fun mcpRoot(): File = File("/storage/emulated/0/ObsidianAE/mcp")

    /** Private per-APK workspaces. */
    fun workspaces(ctx: Context): File = File(ctx.getExternalFilesDir(null), "workspaces")

    /** Best-effort creation. Returns false instead of throwing. */
    fun ensure(ctx: Context): Boolean {
        return try {
            var ok = true
            ok = workspaces(ctx).mkdirs() || workspaces(ctx).isDirectory || ok
            ok = mcpRoot().mkdirs() || mcpRoot().isDirectory || ok
            ok
        } catch (_: Exception) {
            false
        }
    }
}
