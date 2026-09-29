package com.obsidian.apkeditor.mcp.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import com.obsidian.apkeditor.R
import com.obsidian.apkeditor.ui.main.MainActivity
import kotlin.math.abs

/**
 * Floating assistant overlay built on traditional Views (no Compose):
 * a draggable MCP-icon bubble with edge snap, tap toggles a menu panel
 * (service toggle, open app, hide). All views are released on [detach] —
 * nothing outlives the service.
 */
class AssistantOverlay(app: Context) {

    interface Host {
        fun isRunning(): Boolean
        fun endpointUrl(): String
        fun hasPendingGrant(): Boolean
        fun onToggleService()
    }

    private val appContext: Context = app.applicationContext
    private val wm: WindowManager =
        appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var bubble: View? = null
    private var panel: View? = null
    private var host: Host? = null

    private val density: Float = appContext.resources.displayMetrics.density

    private fun dp(v: Int): Int = (v * density + 0.5f).toInt()

    private val bubbleParams = baseParams(dp(60), dp(60)).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 0
        y = dp(320)
    }

    fun attach(host: Host) {
        if (!OverlayPermission.granted(appContext)) return
        if (bubble != null) {
            this.host = host
            refresh()
            return
        }
        this.host = host
        val view = LayoutInflater.from(appContext).inflate(R.layout.overlay_bubble, null)
        view.setOnTouchListener(DragListener())
        view.findViewById<View>(R.id.bubble_icon).setOnClickListener { togglePanel() }
        bubble = view
        runCatching { wm.addView(view, bubbleParams) }
        refresh()
    }

    fun detach() {
        host = null
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null
    }

    fun isShowing(): Boolean = bubble != null

    /** Updates dot + panel contents to match the server state. */
    fun refresh() {
        val h = host ?: return
        val running = runCatching { h.isRunning() }.getOrDefault(false)
        val pendingGrant = runCatching { h.hasPendingGrant() }.getOrDefault(false)
        bubble?.findViewById<View>(R.id.bubble_dot)?.setBackgroundResource(
            when {
                pendingGrant -> R.drawable.overlay_dot_warn
                running -> R.drawable.overlay_dot_on
                else -> R.drawable.overlay_dot_off
            })
        panel?.let {
            it.findViewById<TextView>(R.id.panel_status).text =
                when {
                    pendingGrant -> "Storage grant needed"
                    running -> "Running"
                    else -> "Stopped"
                }
            it.findViewById<TextView>(R.id.panel_endpoint).text =
                h.endpointUrl().ifEmpty { "—" }
            it.findViewById<Button>(R.id.panel_toggle).text =
                if (running) appContext.getString(R.string.stop_service)
                else appContext.getString(R.string.start_service)
        }
    }

    private fun togglePanel() {
        val existing = panel
        if (existing != null) {
            runCatching { wm.removeView(existing) }
            panel = null
            return
        }
        val h = host ?: return
        val view = LayoutInflater.from(appContext).inflate(R.layout.overlay_panel, null)
        view.findViewById<Button>(R.id.panel_toggle).setOnClickListener { h.onToggleService() }
        view.findViewById<Button>(R.id.panel_open).setOnClickListener {
            val launch = Intent(appContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { appContext.startActivity(launch) }
        }
        view.findViewById<Button>(R.id.panel_hide).setOnClickListener { hideBubble() }
        val params = baseParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (bubbleParams.x + 130).coerceAtLeast(0)
            y = bubbleParams.y
        }
        panel = view
        runCatching { wm.addView(view, params) }
        refresh()
    }

    /** Hides the bubble (and panel). Re-shown from notification or settings. */
    fun hideBubble() {
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null
    }

    private fun baseParams(w: Int, h: Int): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= 26) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        return WindowManager.LayoutParams(w, h, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT)
    }

    private inner class DragListener : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var moved = false

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.action) {
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
                    if (!moved && abs(dx) + abs(dy) < 12) return true
                    moved = true
                    bubbleParams.x = startX + dx
                    bubbleParams.y = startY + dy
                    runCatching { wm.updateViewLayout(v, bubbleParams) }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        snapToEdge(v)
                        return true
                    }
                    return false // let click through to icon/panel toggle
                }
            }
            return false
        }

        private fun snapToEdge(v: View) {
            val display = wm.defaultDisplay
            val size = android.graphics.Point()
            @Suppress("DEPRECATION")
            display.getSize(size)
            bubbleParams.x = if (bubbleParams.x + v.width / 2 < size.x / 2) 0 else size.x - v.width
            runCatching { wm.updateViewLayout(v, bubbleParams) }
        }
    }
}
