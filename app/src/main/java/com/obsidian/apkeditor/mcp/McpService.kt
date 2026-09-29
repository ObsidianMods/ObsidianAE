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
import com.obsidian.apkeditor.R
import com.obsidian.apkeditor.app.ObsidianApp
import com.obsidian.apkeditor.ui.main.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground anchor for the MCP runtime. Hosts nothing itself — it keeps the
 * process alive and delegates overlay/notification state to
 * [ServiceController], which coordinates server + overlay + notification as
 * one lifecycle. Never throws out of lifecycle callbacks.
 */
class McpService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        runCatching {
            startForeground(NOTIF_ID, McpNotifications.build(this, "Starting…"))
        }
        scope.launch {
            runCatching { controller().onServiceCreated() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                scope.launch {
                    runCatching { controller().stop() }
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            ACTION_SHOW_BUBBLE -> {
                scope.launch {
                    runCatching { controller().setOverlayVisible(true) }
                }
                return START_STICKY
            }
            ACTION_OPEN -> {
                runCatching {
                    val launch = Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(launch)
                }
                return START_STICKY
            }
        }
        // Re-anchor: if the controller has no server and service isn't wanted,
        // stop instead of lingering with no socket.
        scope.launch {
            val ctl = runCatching { controller() }.getOrNull() ?: run {
                stopSelf()
                return@launch
            }
            val running = ctl.status() is ServerStatus.Running
            val wanted = runCatching { ctl.isWanted() }.getOrDefault(false)
            if (!running && !wanted) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun controller(): ServiceController =
        (application as ObsidianApp).container.service

    override fun onTaskRemoved(rootIntent: Intent?) {
        scope.launch {
            runCatching { controller().onTaskRemoved() }
        }
    }

    override fun onDestroy() {
        runCatching { (application as ObsidianApp).container.service.onServiceDestroyed() }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val NOTIF_ID = 41
        const val ACTION_STOP = "com.obsidian.apkeditor.STOP"
        const val ACTION_SHOW_BUBBLE = "com.obsidian.apkeditor.SHOW_BUBBLE"
        const val ACTION_OPEN = "com.obsidian.apkeditor.OPEN_APP"
        const val CHANNEL_ID = "mcp"

        fun start(context: Context) {
            ServiceController.startService(context)
        }
    }
}

/** Notification helpers. Channel created once; actions drive the service. */
object McpNotifications {

    fun build(ctx: Context, endpoint: String): Notification {
        ensureChannel(ctx)
        val open = PendingIntent.getService(
            ctx, 0,
            Intent(ctx, McpService::class.java).setAction(McpService.ACTION_OPEN),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            ctx, 1,
            Intent(ctx, McpService::class.java).setAction(McpService.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val bubble = PendingIntent.getService(
            ctx, 2,
            Intent(ctx, McpService::class.java).setAction(McpService.ACTION_SHOW_BUBBLE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(ctx, McpService.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mcp)
            .setContentTitle("Obsidian agent access")
            .setContentText(endpoint.ifEmpty { "Starting…" })
            .setContentIntent(open)
            .addAction(0, "Open", open)
            .addAction(0, "Bubble", bubble)
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
