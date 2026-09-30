package com.obsidian.apkeditor.mcp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
 * Foreground owner of the MCP runtime (Mod-Menu pattern: the Activity is
 * UI-only, the Service owns the job). It hosts the ServerSocket + overlay
 * bubble via [ServiceController]: startForeground keeps the process alive
 * after the app leaves the foreground (a plain background Service is killed
 * in ~1 min stock, instantly on Transsion/MTK; the overlay window alone does
 * NOT exempt it). START_STICKY re-anchors after an OEM kill. Never throws
 * out of lifecycle callbacks.
 */
class McpService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // Must promote within ~5s of startForegroundService, before any
        // async work. Placeholder text is replaced once the endpoint is up.
        runCatching { promoteToForeground("") }
        // Overlay work must happen on the main thread (WindowManager needs a
        // Looper); the controller posts to Main itself.
        runCatching { controller().onServiceCreated() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every entry re-asserts foreground: the system can recreate us
        // without onCreate ordering guarantees after an OEM kill.
        runCatching { promoteToForeground(controller().status().endpointUrl) }
        when (intent?.action) {
            ACTION_STOP -> {
                scope.launch {
                    runCatching { controller().stop(exit = true) }
                    runCatching {
                        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
                        else @Suppress("DEPRECATION") stopForeground(true)
                    }
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
                runCatching {
                    if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
                    else @Suppress("DEPRECATION") stopForeground(true)
                }
                stopSelf()
                return@launch
            }
            if (wanted && !running) runCatching { ctl.resumeIfWanted() }
            // Refresh the ongoing notification with the real endpoint once up.
            runCatching { promoteToForeground(ctl.status().endpointUrl) }
        }
        return START_STICKY
    }

    /**
     * Foreground promotion (Mod-Menu lesson: the Service owns the job, so it
     * must own the foreground state too). dataSync type matches the manifest;
     * falls back gracefully on old APIs / missing permission.
     */
    private fun promoteToForeground(endpoint: String) {
        val notif = McpNotifications.build(this, endpoint)
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(
                    NOTIF_ID, notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(NOTIF_ID, notif)
            }
        } catch (_: Throwable) {
            // Last resort: foreground without a type (old API) — still far
            // better than a background service that dies minimized.
            runCatching { startForeground(NOTIF_ID, notif) }
        }
    }

    private fun controller(): ServiceController =
        (application as ObsidianApp).container.service

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Foreground + stopWithTask=false: we survive a swipe-away when the
        // endpoint is wanted; the controller decides (wanted wins).
        scope.launch {
            runCatching { controller().onTaskRemoved() }
        }
    }

    override fun onDestroy() {
        runCatching { (application as ObsidianApp).container.service.onServiceDestroyed() }
        runCatching {
            if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
            else @Suppress("DEPRECATION") stopForeground(true)
        }
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
