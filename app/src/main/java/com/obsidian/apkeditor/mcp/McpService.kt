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
import com.obsidian.apkeditor.system.GrantRequests
import com.obsidian.apkeditor.ui.main.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Background anchor for the MCP runtime (NOT a foreground service). It hosts
 * nothing itself: overlay (bubble + control panel), server and notification
 * are coordinated by [ServiceController]. While the bubble window is attached
 * to the system the process stays visible to the OS; START_STICKY re-anchors
 * after an OEM kill. Never throws out of lifecycle callbacks.
 */
class McpService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // Overlay work must happen on the main thread (WindowManager needs a
        // Looper); the controller posts to Main itself.
        runCatching { controller().onServiceCreated() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                scope.launch {
                    runCatching { controller().stop(exit = true) }
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
        // stop instead of lingering with no socket. STICKY otherwise: after
        // an OEM kill the system recreates us and we re-anchor the socket.
        scope.launch {
            val ctl = runCatching { controller() }.getOrNull() ?: run {
                stopSelf()
                return@launch
            }
            val running = ctl.status() is ServerStatus.Running
            val wanted = runCatching { ctl.isWanted() }.getOrDefault(false)
            val bubble = runCatching { ctl.overlayWanted() }.getOrDefault(false)
            if (!running && !wanted && !bubble) {
                stopSelf()
                return@launch
            }
            if (wanted && !running) runCatching { ctl.resumeIfWanted() }
        }
        return START_STICKY
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
        const val GRANT_NOTIF_ID = 42
        const val ACTION_STOP = "com.obsidian.apkeditor.STOP"
        const val ACTION_SHOW_BUBBLE = "com.obsidian.apkeditor.SHOW_BUBBLE"
        const val ACTION_OPEN = "com.obsidian.apkeditor.OPEN_APP"
        const val ACTION_GRANT = "com.obsidian.apkeditor.GRANT_FOLDER"
        const val CHANNEL_ID = "mcp"
        const val CHANNEL_GRANT = "grants"

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

    /**
     * Folder-grant ping: high-priority, auto-dismissing. Tap opens the app
     * straight into the storage bottom sheet for [path]. Posted at most once
     * per distinct path (the service dedupes via [GrantRequests]).
     */
    fun grantRequest(ctx: Context, path: String) {
        runCatching {
            ensureGrantChannel(ctx)
            val open = PendingIntent.getActivity(
                ctx, 100,
                Intent(ctx, MainActivity::class.java)
                    .setAction(McpService.ACTION_GRANT)
                    .putExtra(GrantRequests.EXTRA_GRANT_PATH, path)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(
                McpService.GRANT_NOTIF_ID,
                NotificationCompat.Builder(ctx, McpService.CHANNEL_GRANT)
                    .setSmallIcon(R.drawable.ic_mcp)
                    .setContentTitle("Folder access needed")
                    .setContentText("The agent needs: $path — tap to grant")
                    .setStyle(NotificationCompat.BigTextStyle().bigText(
                        "The agent tried to read $path, which isn't granted. " +
                            "Tap to open Storage access and pick the folder."))
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .build(),
            )
        }
    }

    fun cancelGrant(ctx: Context) {
        runCatching {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(McpService.GRANT_NOTIF_ID)
        }
    }

    private fun ensureGrantChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        runCatching {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(McpService.CHANNEL_GRANT) == null) {
                nm.createNotificationChannel(NotificationChannel(
                    McpService.CHANNEL_GRANT, "Folder access requests",
                    NotificationManager.IMPORTANCE_HIGH,
                ))
            }
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
