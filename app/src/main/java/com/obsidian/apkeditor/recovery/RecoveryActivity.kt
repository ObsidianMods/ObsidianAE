package com.obsidian.apkeditor.recovery

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.databinding.DataBindingUtil
import com.obsidian.apkeditor.R
import com.obsidian.apkeditor.databinding.ActivityRecoveryBinding
import com.obsidian.apkeditor.ui.main.MainActivity

/**
 * Isolated recovery surface.
 *
 * Safety contract (do not weaken):
 * - XML layout + Data Binding only, AppCompat widgets only.
 * - References [CrashStore] and nothing else — never the container, tools,
 *   workspaces, or services. Safe to launch when everything else is broken.
 * - Every I/O call is guarded; this screen must never throw.
 */
class RecoveryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRecoveryBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DataBindingUtil.setContentView(this, R.layout.activity_recovery)

        val safeMode = intent.getBooleanExtra(EXTRA_SAFE_MODE, false) ||
            runCatching { CrashStore.inSafeMode(this) }.getOrDefault(false)

        val pending = runCatching { CrashStore.consumePending(this) }.getOrNull()
        val report = pending?.text
            ?: runCatching { CrashStore.lastFatal(this) }.getOrDefault("")

        binding.recoveryStatus.text = when {
            safeMode -> getString(R.string.recovery_safe_mode)
            pending?.kind == CrashStore.KIND_ANR -> getString(R.string.recovery_anr)
            report.isNotEmpty() -> getString(R.string.recovery_crash)
            else -> getString(R.string.recovery_empty)
        }
        binding.recoveryLog.text = report.ifEmpty { getString(R.string.recovery_no_details) }

        binding.btnCopy.setOnClickListener {
            copyReport(report)
        }
        binding.btnRestart.setOnClickListener {
            val launch = Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            runCatching { startActivity(launch) }
            finish()
        }
        binding.btnClose.setOnClickListener {
            finishAndRemoveTask()
        }
    }

    private fun copyReport(report: String) {
        if (report.isEmpty()) return
        runCatching {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("obsidian-crash", report.take(24_000)))
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val EXTRA_SAFE_MODE = "extra_safe_mode"
    }
}
