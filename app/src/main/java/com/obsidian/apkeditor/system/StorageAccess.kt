package com.obsidian.apkeditor.system

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import java.io.File

/**
 * "All files access" gate for the default MCP tree.
 *
 * The default MCP dir lives on shared storage ([StorageDirs.mcpRoot]), so
 * on Android 11+ plain mkdir/File I/O there silently fails without
 * MANAGE_EXTERNAL_STORAGE. There is no runtime dialog for it — the only
 * grant path is the Settings screen, so the app sends the user there
 * ([StorageGate]) and creates the tree whenever the grant is present.
 *
 * Note: SAF-picked folders keep working without this grant; it is only
 * needed for the default shared-storage path.
 */
object StorageAccess {
    fun hasFullAccess(@Suppress("UNUSED_PARAMETER") ctx: Context): Boolean =
        Environment.isExternalStorageManager()

    fun requestIntent(ctx: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${ctx.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun fallbackIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Create `<mcp>/` + `<mcp>/output/`. Returns true only if both exist now. */
    fun ensureDefaultDirs(): Boolean = runCatching {
        val root: File = StorageDirs.mcpRoot()
        File(root, "output").mkdirs() // mkdirs creates missing parents too
        root.isDirectory && File(root, "output").isDirectory
    }.getOrDefault(false)
}
