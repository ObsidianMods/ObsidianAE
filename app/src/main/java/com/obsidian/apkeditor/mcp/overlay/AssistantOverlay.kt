package com.obsidian.apkeditor.mcp.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.obsidian.apkeditor.R
import com.obsidian.apkeditor.mcp.ServerStatus
import com.obsidian.apkeditor.system.Prefs
import com.obsidian.apkeditor.tools.ToolDefinition
import kotlin.math.abs

/**
 * System-overlay assistant hosted by the (background) service.
 *
 * - A draggable bubble attached straight to the window manager
 *   (TYPE_APPLICATION_OVERLAY) with edge snap.
 * - Tapping it opens [OverlayControlPanel]: an AlertDialog that is also an
 *   overlay window, with an MCP tab and a Capabilities tab.
 *
 * THREADING: every method here must run on the main thread. The controller
 * guarantees that (Dispatchers.Main.immediate); WindowManager.addView and
 * Dialog.show both require a Looper thread.
 */
class AssistantOverlay(app: Context) {

    interface Host {
        val prefs: Prefs
        fun status(): ServerStatus
        fun isRunning(): Boolean
        fun endpointUrl(): String
        fun hasPendingGrant(): Boolean
        fun tools(): List<ToolDefinition>
        fun onToggleService()
        fun onApplyConfig(port: Int, path: String)
        fun onGatingChanged()
        fun onHideBubble()
    }

    private val appContext: Context = app.applicationContext

    /** Themed context: AppCompat widgets + AlertDialog need an AppCompat theme. */
    private val ui: Context = ContextThemeWrapper(appContext, R.style.Theme_ObsidianAE_Overlay)

    private val wm: WindowManager =
        appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var bubble: View? = null
    private var panel: OverlayControlPanel? = null
    private var host: Host? = null

    private val density: Float = appContext.resources.displayMetrics.density
    private fun dp(v: Int): Int = (v * density + 0.5f).toInt()

    private val bubbleParams = WindowManager.LayoutParams(
        dp(60), dp(60),
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 0
        y = dp(320)
    }

    fun attach(host: Host) {
        if (!OverlayPermission.granted(appContext)) return
        this.host = host
        if (bubble != null) {
            refresh()
            return
        }
        val view = LayoutInflater.from(ui).inflate(R.layout.overlay_bubble, null)
        view.setOnTouchListener(DragListener())
        try {
            wm.addView(view, bubbleParams)
            bubble = view
        } catch (t: Throwable) {
            Log.w(TAG, "addView(bubble) failed", t)
            bubble = null
            return
        }
        refresh()
    }

    /** Removes the bubble and any open panel and forgets the host. */
    fun detach() {
        hideBubble()
        host = null
    }

    /** Hides bubble + panel; keeps the host so [attach] can bring it back. */
    fun hideBubble() {
        panel?.dismiss()
        panel = null
        bubble?.let { runCatching { wm.removeViewImmediate(it) } }
        bubble = null
    }

    fun isShowing(): Boolean = bubble != null

    /** Repaints the status dot and the open panel. */
    fun refresh() {
        val h = host ?: return
        val pendingGrant = runCatching { h.hasPendingGrant() }.getOrDefault(false)
        val status = runCatching { h.status() }.getOrDefault(ServerStatus.Stopped)
        bubble?.findViewById<View>(R.id.bubble_dot)?.setBackgroundResource(
            when {
                pendingGrant -> R.drawable.overlay_dot_warn
                status is ServerStatus.Running -> R.drawable.overlay_dot_on
                else -> R.drawable.overlay_dot_off
            })
        panel?.refresh()
    }

    private fun togglePanel() {
        val h = host ?: return
        val existing = panel
        if (existing != null && existing.isShowing()) {
            existing.dismiss()
            panel = null
            return
        }
        val p = OverlayControlPanel(ui, h) { panel = null }
        panel = p
        if (!p.show()) panel = null
    }

    private inner class DragListener : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var moved = false

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = bubbleParams.x
                    startY = bubbleParams.y
                    moved = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (!moved && abs(dx) + abs(dy) < dp(6)) return true
                    moved = true
                    bubbleParams.x = startX + dx
                    bubbleParams.y = startY + dy
                    runCatching { wm.updateViewLayout(v, bubbleParams) }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) snapToEdge(v) else togglePanel()
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (moved) snapToEdge(v)
                    return true
                }
            }
            return false
        }

        private fun snapToEdge(v: View) {
            val bounds = wm.currentWindowMetrics.bounds
            val w = v.width.takeIf { it > 0 } ?: dp(60)
            val h = v.height.takeIf { it > 0 } ?: dp(60)
            bubbleParams.x = if (bubbleParams.x + w / 2 < bounds.width() / 2) 0 else bounds.width() - w
            bubbleParams.y = bubbleParams.y.coerceIn(0, (bounds.height() - h).coerceAtLeast(0))
            runCatching { wm.updateViewLayout(v, bubbleParams) }
        }
    }

    private companion object {
        const val TAG = "ObsidianOverlay"
    }
}
