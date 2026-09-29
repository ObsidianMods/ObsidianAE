package com.obsidian.apkeditor.mcp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.obsidian.apkeditor.ui.main.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground anchor for the MCP server (the reference used a background
 * service that Android 8+ and OEM task killers stop at will). Holds no
 * sockets itself; [ServiceController] owns the server. Stops when not wanted.
 */
class McpService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, McpNotifications.build(this, ""))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                scope.launch {
                    runCatching {
                        (application as ObsidianApp).container.service.stop()
                    }
                    stopSelf()
                }
                return START_NOT_STICKY
            }
        }
        // Re-anchor: if the controller has no server and service isn't wanted,
        // stop instead of lingering (reference gap: sticky restart with no socket).
        scope.launch {
            val ctl = (application as ObsidianApp).container.service
            val running = ctl.status() is ServerStatus.Running
            val wanted = runCatching { ctlPrefsWanted() }.getOrDefault(false)
            if (!running && !wanted) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun ctlPrefsWanted(): Boolean =
        (application as ObsidianApp).container.prefs.serviceWanted

    override fun onTaskRemoved(rootIntent: Intent?) {
        scope.launch {
            runCatching { (application as ObsidianApp).container.service.onTaskRemoved() }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val NOTIF_ID = 41
        const val ACTION_STOP = "com.obsidian.apkeditor.STOP"
        const val CHANNEL_ID = "mcp"

        fun start(context: Context) {
            ServiceController.startService(context)
        }
    }
}

/** Notification helpers. Channel created once; text-only updates after. */
object McpNotifications {

    fun build(ctx: Context, endpoint: String): Notification {
        ensureChannel(ctx)
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            ctx, 1, Intent(ctx, McpService::class.java).setAction(McpService.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(ctx, McpService.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Obsidian agent access")
            .setContentText(endpoint.ifEmpty { "Starting…" })
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .build()
    }

    fun show(ctx: Context, endpoint: String) {
        runCatching {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(McpService.NOTIF_ID, build(ctx, endpoint))
        }
    }

    fun cancel(ctx: Context) {
        runCatching {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(McpService.NOTIF_ID)
        }
    }

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        runCatching {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(McpService.CHANNEL_ID) == null) {
                nm.createNotificationChannel(NotificationChannel(
                    McpService.CHANNEL_ID, "Agent access",
                    NotificationManager.IMPORTANCE_LOW,
                ))
            }
        }
    }
}
