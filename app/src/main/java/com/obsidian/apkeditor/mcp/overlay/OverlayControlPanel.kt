package com.obsidian.apkeditor.mcp.overlay

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import com.obsidian.apkeditor.mcp.ServerStatus
import com.obsidian.apkeditor.tools.Capability
import com.obsidian.apkeditor.ui.main.MainActivity

/**
 * Control panel shown by the service as a system-overlay AlertDialog.
 *
 *  - MCP tab: status, endpoint (tap to copy), start/stop, port + path,
 *    lifecycle switches, open app / hide bubble.
 *  - Capabilities tab: one switch per capability group (expandable) and one
 *    per tool. Toggles persist to prefs and re-sync the live registry at once.
 *
 * Plain Views only (no Compose): a Service has no lifecycle/saved-state owner
 * for a ComposeView, and this keeps the overlay dependency-free.
 * Main thread only.
 */
class OverlayControlPanel(
    private val ctx: Context,
    private val host: AssistantOverlay.Host,
    private val onDismissed: () -> Unit,
) {

    private var dialog: AlertDialog? = null
    private var tab = 0
    private val expanded = mutableSetOf<Capability>()

    private var body: FrameLayout? = null
    private var tabMcp: TextView? = null
    private var tabCaps: TextView? = null
    private var indMcp: View? = null
    private var indCaps: View? = null

    // Live widgets of the MCP page (null while the Capabilities tab is shown).
    private var dotView: View? = null
    private var statusView: TextView? = null
    private var endpointView: TextView? = null
    private var toggleBtn: TextView? = null
    private var errorView: TextView? = null

    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Int): Int = (v * density + 0.5f).toInt()

    fun isShowing(): Boolean = dialog?.isShowing == true

    fun dismiss() {
        val d = dialog ?: return
        dialog = null
        runCatching { d.dismiss() }
    }

    /** Builds and shows the dialog. Returns false if the window could not attach. */
    fun show(): Boolean {
        val metrics = ctx.resources.displayMetrics
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(10))
        }

        // Header: title + close.
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(label("Obsidian AE", 16f, TEXT, bold = true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(label("✕", 18f, MUTED).apply {
            setPadding(dp(10), dp(2), dp(4), dp(2))
            setOnClickListener { dismiss(); onDismissed() }
        })
        root.addView(header)

        // Tabs.
        val tabs = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(6))
        }
        val (mcpCell, mcpText, mcpInd) = tabCell("MCP") { select(0) }
        val (capCell, capText, capInd) = tabCell("Capabilities") { select(1) }
        tabMcp = mcpText; indMcp = mcpInd
        tabCaps = capText; indCaps = capInd
        tabs.addView(mcpCell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        tabs.addView(capCell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(tabs)

        // Scrollable body, capped so the dialog never exceeds ~55% of the screen.
        val scroll = ScrollView(ctx).apply { isFillViewport = false }
        val bodyFrame = FrameLayout(ctx)
        scroll.addView(bodyFrame, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        body = bodyFrame
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, (metrics.heightPixels * 0.5f).toInt()))

        select(tab)

        val bg = GradientDrawable().apply {
            setColor(BG)
            cornerRadius = dp(22).toFloat()
            setStroke(dp(1), ACCENT_DIM)
        }
        val d = AlertDialog.Builder(ctx).setView(root).create()
        d.setCanceledOnTouchOutside(true)
        d.setOnDismissListener {
            if (dialog === d) dialog = null
            dotView = null; statusView = null; endpointView = null
            toggleBtn = null; errorView = null
            onDismissed()
        }
        d.window?.let { w ->
            // The whole point: this dialog is a system overlay, hosted by the
            // service (no Activity token exists).
            w.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            w.setBackgroundDrawable(bg)
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        return try {
            d.show()
            d.window?.setLayout(
                minOf((metrics.widthPixels * 0.92f).toInt(), dp(440)),
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            dialog = d
            true
        } catch (t: Throwable) {
            Log.w(TAG, "control panel show failed", t)
            false
        }
    }

    /** Repaints status widgets only; never rebuilds pages (keeps typed text). */
    fun refresh() {
        if (dialog == null) return
        val status = runCatching { host.status() }.getOrDefault(ServerStatus.Stopped)
        val pending = runCatching { host.hasPendingGrant() }.getOrDefault(false)
        dotView?.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(when {
                pending -> WARN
                status is ServerStatus.Running -> OK
                status is ServerStatus.Error -> ERR
                else -> GREY
            })
        }
        statusView?.text = buildString {
            append(when (status) {
                is ServerStatus.Running -> "Running"
                ServerStatus.Starting -> "Starting…"
                is ServerStatus.Error -> "Error"
                ServerStatus.Stopped -> "Stopped"
            })
            if (pending) append(" · storage grant needed")
        }
        endpointView?.text = host.endpointUrl().ifEmpty { "—" }
        toggleBtn?.text = when (status) {
            is ServerStatus.Running -> "Stop"
            ServerStatus.Starting -> "Starting…"
            else -> "Start"
        }
        errorView?.apply {
            if (status is ServerStatus.Error) {
                text = status.message
                visibility = View.VISIBLE
            } else if (tag != "local") {
                visibility = View.GONE
            }
        }
    }

    // ---- tabs -------------------------------------------------------------

    private fun select(index: Int) {
        tab = index
        val b = body ?: return
        b.removeAllViews()
        dotView = null; statusView = null; endpointView = null
        toggleBtn = null; errorView = null
        tabMcp?.setTextColor(if (index == 0) TEXT else MUTED)
        tabCaps?.setTextColor(if (index == 1) TEXT else MUTED)
        indMcp?.setBackgroundColor(if (index == 0) ACCENT else Color.TRANSPARENT)
        indCaps?.setBackgroundColor(if (index == 1) ACCENT else Color.TRANSPARENT)
        b.addView(if (index == 0) mcpPage() else capabilitiesPage())
        if (index == 0) refresh()
    }

    private fun tabCell(title: String, onClick: () -> Unit): Triple<View, TextView, View> {
        val text = label(title, 14f, MUTED, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
        }
        val ind = View(ctx)
        val cell = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setOnClickListener { onClick() }
        }
        cell.addView(text, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        cell.addView(ind, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)))
        return Triple(cell, text, ind)
    }

    // ---- MCP page ---------------------------------------------------------

    private fun mcpPage(): View {
        val page = column()
        val prefs = host.prefs

        // Status card.
        val card = column().apply {
            background = rounded(CARD, 14)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        val statusRow = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        val dot = View(ctx)
        dotView = dot
        statusRow.addView(dot, LinearLayout.LayoutParams(dp(10), dp(10)).apply {
            rightMargin = dp(8)
        })
        statusView = label("", 14f, TEXT, bold = true)
        statusRow.addView(statusView)
        card.addView(statusRow)
        endpointView = label("", 12f, MUTED).apply {
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(6), 0, 0)
            setOnClickListener {
                val url = host.endpointUrl()
                if (url.isNotEmpty()) {
                    runCatching {
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("MCP endpoint", url))
                        Toast.makeText(ctx, "Endpoint copied", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        card.addView(endpointView)
        errorView = label("", 12f, ERR).apply {
            visibility = View.GONE
            setPadding(0, dp(6), 0, 0)
        }
        card.addView(errorView)
        page.addView(card, lpMatch())

        toggleBtn = button("Start", filled = true) { host.onToggleService() }
        page.addView(toggleBtn, lpMatch(top = 10))

        // Port + path.
        page.addView(sectionLabel("Endpoint"), lpMatch(top = 14))
        val portField = field("Port (1024–65535)", prefs.servicePort.toString(), numeric = true)
        val pathField = field("Path", prefs.endpointPath, numeric = false)
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(portField, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            .apply { rightMargin = dp(8) })
        row.addView(pathField, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f))
        page.addView(row, lpMatch(top = 6))
        val localError = label("", 12f, ERR).apply {
            visibility = View.GONE
            tag = "local"
        }
        page.addView(localError, lpMatch(top = 4))
        page.addView(button("Apply", filled = false) {
            val port = portField.text.toString().trim().toIntOrNull()
            val path = pathField.text.toString().trim().trim('/')
            when {
                port == null || port !in 1024..65535 -> {
                    localError.text = "Port must be 1024–65535."
                    localError.visibility = View.VISIBLE
                }
                !PATH_RE.matches(path) -> {
                    localError.text = "Path: letters, digits, _ or -, up to 64 characters."
                    localError.visibility = View.VISIBLE
                }
                else -> {
                    localError.visibility = View.GONE
                    host.onApplyConfig(port, path)
                    Toast.makeText(ctx, "Saved — restarting if running", Toast.LENGTH_SHORT).show()
                }
            }
        }, lpMatch(top = 6))

        // Lifecycle.
        page.addView(sectionLabel("Behaviour"), lpMatch(top = 14))
        page.addView(switchRow(
            "Stop when app is swiped away",
            "Ends the endpoint when the task is removed.",
            prefs.stopOnTaskRemoved,
        ) { prefs.stopOnTaskRemoved = it }, lpMatch(top = 4))

        val actions = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(button("Open app", filled = false) {
            runCatching {
                ctx.startActivity(Intent(ctx, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            dismiss(); onDismissed()
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            .apply { rightMargin = dp(8) })
        actions.addView(button("Hide bubble", filled = false) {
            dismiss(); onDismissed()
            host.onHideBubble()
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        page.addView(actions, lpMatch(top = 12))
        return page
    }

    // ---- Capabilities page ------------------------------------------------

    private fun capabilitiesPage(): View {
        val page = column()
        val prefs = host.prefs
        val all = host.tools()
        val disabledCaps = prefs.disabledCapabilities()
        val disabledTools = prefs.disabledTools()

        if (all.isEmpty()) {
            page.addView(label(
                "No tools are registered yet. Open the app once so the tool set loads.",
                13f, MUTED), lpMatch(top = 8))
            return page
        }

        val available = all.count { it.capability.id !in disabledCaps && it.name !in disabledTools }
        page.addView(label("$available of ${all.size} tools available to the agent", 12f, MUTED),
            lpMatch(top = 4))

        for (cap in Capability.entries) {
            val tools = all.filter { it.capability == cap }.sortedBy { it.name }
            if (tools.isEmpty()) continue
            val capOn = cap.id !in disabledCaps
            val open = cap in expanded

            val card = column().apply {
                background = rounded(CARD, 14)
                setPadding(dp(12), dp(8), dp(12), dp(8))
            }
            val head = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val titles = column().apply {
                setOnClickListener {
                    if (!expanded.add(cap)) expanded.remove(cap)
                    select(1)
                }
            }
            titles.addView(label(cap.title, 14f, TEXT, bold = true))
            titles.addView(label(
                "${tools.size} ${if (tools.size == 1) "tool" else "tools"}  ${if (open) "▾" else "▸"}",
                12f, MUTED))
            head.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            head.addView(switch(capOn) { on ->
                prefs.setCapabilityEnabled(cap.id, on)
                host.onGatingChanged()
                select(1)
            })
            card.addView(head)

            if (open) {
                for (t in tools) {
                    val on = t.name !in disabledTools
                    val r = LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(8), dp(4), 0, dp(4))
                        alpha = if (capOn) 1f else 0.45f
                    }
                    val txt = column()
                    txt.addView(label(t.title, 13f, TEXT))
                    txt.addView(label(t.name, 11f, MUTED).apply { typeface = Typeface.MONOSPACE })
                    r.addView(txt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    r.addView(switch(on) { checked ->
                        prefs.setToolEnabled(t.name, checked)
                        host.onGatingChanged()
                        select(1)
                    })
                    card.addView(r)
                }
            }
            page.addView(card, lpMatch(top = 8))
        }
        page.addView(label(
            "Changes apply to the running endpoint immediately. " +
                "A disabled capability blocks all of its tools.",
            11f, MUTED), lpMatch(top = 10))
        return page
    }

    // ---- view helpers -----------------------------------------------------

    private fun column() = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

    private fun lpMatch(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(top) }

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun label(text: String, sp: Float, color: Int, bold: Boolean = false) =
        TextView(ctx).apply {
            this.text = text
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun sectionLabel(text: String) = label(text.uppercase(), 11f, ACCENT, bold = true)

    private fun button(text: String, filled: Boolean, onClick: () -> Unit) =
        TextView(ctx).apply {
            this.text = text
            gravity = Gravity.CENTER
            setTextColor(if (filled) BG_SOLID else ACCENT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(14), dp(11), dp(14), dp(11))
            background = if (filled) rounded(ACCENT, 12) else GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), ACCENT_DIM)
            }
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun field(hint: String, value: String, numeric: Boolean): EditText =
        EditText(ctx).apply {
            setText(value)
            this.hint = hint
            setHintTextColor(GREY)
            setTextColor(TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setSingleLine()
            inputType = if (numeric) InputType.TYPE_CLASS_NUMBER
            else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(CARD, 12)
        }

    private fun switch(checked: Boolean, onChange: (Boolean) -> Unit) =
        SwitchCompat(ctx).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, on -> onChange(on) }
        }

    private fun switchRow(
        title: String,
        subtitle: String,
        checked: Boolean,
        onChange: (Boolean) -> Unit,
    ): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val txt = column()
        txt.addView(label(title, 14f, TEXT))
        txt.addView(label(subtitle, 11f, MUTED))
        row.addView(txt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(switch(checked, onChange))
        return row
    }

    private companion object {
        const val TAG = "ObsidianOverlay"
        val PATH_RE = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")

        val BG = Color.parseColor("#F2171428")
        val BG_SOLID = Color.parseColor("#FF171428")
        val CARD = Color.parseColor("#FF241E3D")
        val TEXT = Color.parseColor("#FFF1ECFA")
        val MUTED = Color.parseColor("#FFC9BFD9")
        val ACCENT = Color.parseColor("#FFB79CFF")
        val ACCENT_DIM = Color.parseColor("#66B79CFF")
        val OK = Color.parseColor("#FF5EEAD4")
        val WARN = Color.parseColor("#FFFBBF24")
        val ERR = Color.parseColor("#FFF87171")
        val GREY = Color.parseColor("#FF8A8494")
    }
}
