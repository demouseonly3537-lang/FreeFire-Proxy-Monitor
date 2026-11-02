package com.ashxmay.ffproxymonitor

import android.app.AppOpsManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var logView: TextView
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var usageBtn: Button
    private lateinit var copyBtn: Button
    private val handler = Handler(Looper.getMainLooper())

    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshLog()
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        logView = findViewById(R.id.logView)
        startBtn = findViewById(R.id.startBtn)
        stopBtn = findViewById(R.id.stopBtn)
        usageBtn = findViewById(R.id.usageBtn)
        copyBtn = findViewById(R.id.copyBtn)

        // Notification permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                1001
            )
        }

        startBtn.setOnClickListener {
            if (!hasUsageAccess()) {
                Toast.makeText(this, "Pehle USAGE ACCESS ON karo!", Toast.LENGTH_LONG).show()
                openUsageAccessSettings()
                return@setOnClickListener
            }
            val intent = Intent(this, MonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(this, intent)
            } else {
                startService(intent)
            }
            statusText.text = "Monitor RUNNING - data collect ho raha hai"
            handler.post(refreshRunnable)
        }

        stopBtn.setOnClickListener {
            stopService(Intent(this, MonitorService::class.java))
            statusText.text = "Monitor STOPPED"
            handler.removeCallbacks(refreshRunnable)
            refreshLog()
        }

        usageBtn.setOnClickListener {
            openUsageAccessSettings()
        }

        copyBtn.setOnClickListener {
            copyFullLog()
        }

        updateUsageButton()
        refreshLog()
    }

    override fun onResume() {
        super.onResume()
        updateUsageButton()
        refreshLog()
    }

    private fun hasUsageAccess(): Boolean {
        return try {
            val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    packageName
                )
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            false
        }
    }

    private fun openUsageAccessSettings() {
        try {
            val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            startActivity(intent)
            Toast.makeText(this, "FF Proxy Monitor ko ON kar do list me", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            } catch (_: Exception) {}
        }
    }

    private fun updateUsageButton() {
        if (hasUsageAccess()) {
            usageBtn.text = "USAGE ACCESS ✓ ON HAI"
            statusText.text = if (statusText.text.toString().contains("RUNNING"))
                statusText.text else "Usage Access OK - START dabao"
        } else {
            usageBtn.text = "USAGE ACCESS ON KARO (ZAROORI)"
            statusText.text = "Pehle Usage Access permission do!"
        }
    }

    private fun copyFullLog() {
        try {
            val dir = getExternalFilesDir(null) ?: filesDir
            val logFile = File(dir, "ff_proxy_monitor_log.txt")
            if (logFile.exists()) {
                val text = logFile.readText()
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("FF Log", text))
                Toast.makeText(this, "Poora log copy ho gaya!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Log file nahi mili", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Copy fail: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshLog() {
        try {
            val dir = getExternalFilesDir(null) ?: filesDir
            val logFile = File(dir, "ff_proxy_monitor_log.txt")
            if (logFile.exists()) {
                val lines = logFile.readLines()
                val last = if (lines.size > 40) lines.takeLast(40) else lines
                logView.text = last.joinToString("\n")
            } else {
                logView.text = "Log file abhi nahi bani.\n1. Usage Access ON karo\n2. START dabao"
            }
        } catch (e: Exception) {
            logView.text = "Error reading log: ${e.message}"
        }
    }
}
