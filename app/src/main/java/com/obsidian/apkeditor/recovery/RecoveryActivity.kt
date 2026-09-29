package com.obsidian.apkeditor.recovery

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Process
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.databinding.DataBindingUtil
import com.obsidian.apkeditor.R
import com.obsidian.apkeditor.databinding.ActivityRecoveryBinding
import kotlin.system.exitProcess

/**
 * Isolated recovery surface (mirrors the original CrashActivity contract).
 * Reads the log from the intent extra, else the last stored log.
 * References [CrashStore] and nothing else — safe when everything else
 * is broken. Never referenced from any other activity.
 */
class RecoveryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRecoveryBinding
    private var logText: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DataBindingUtil.setContentView(this, R.layout.activity_recovery)

        logText = intent.getStringExtra(EXTRA_LOG)
            ?: runCatching { CrashStore.readLastLog(this) }.getOrDefault("")
        if (logText.isEmpty()) logText = getString(R.string.recovery_no_details)

        val safeMode = runCatching { CrashStore.inSafeMode(this) }.getOrDefault(false)
        binding.recoveryStatus.text = if (safeMode) {
            getString(R.string.recovery_safe_mode)
        } else if (logText.startsWith("=== ANR ===")) {
            getString(R.string.recovery_anr)
        } else {
            getString(R.string.recovery_crash)
        }
        binding.recoveryLog.text = logText

        binding.btnCopy.setOnClickListener {
            copyReport()
        }
        binding.btnRestart.setOnClickListener {
            restartApp()
        }
        binding.btnClose.setOnClickListener {
            Process.killProcess(Process.myPid())
            exitProcess(0)
        }
    }

    private fun copyReport() {
        if (logText.isEmpty()) return
        runCatching {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("obsidian-crash", logText.take(24_000)))
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
        }
    }

    private fun restartApp() {
        runCatching {
            val launch = packageManager.getLaunchIntentForPackage(packageName)
            if (launch != null) {
                launch.addFlags(
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
                startActivity(launch)
            }
        }
        Process.killProcess(Process.myPid())
        exitProcess(0)
    }

    companion object {
        const val EXTRA_LOG = "extra_log"
    }
}
