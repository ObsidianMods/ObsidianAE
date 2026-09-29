package com.obsidian.apkeditor.mcp.overlay

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * "Display over other apps" gate for the floating assistant.
 * SYSTEM_ALERT_WINDOW is not a runtime permission: there is no system
 * dialog. The only grant path is the Settings screen, so the start flow
 * sends the user there and resumes starting when they come back.
 */
object OverlayPermission {
    fun granted(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(ctx)

    fun requestIntent(ctx: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${ctx.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
