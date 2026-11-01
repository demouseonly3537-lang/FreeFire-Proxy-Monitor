package com.ashxmay.ffproxymonitor

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var logView: TextView
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button
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

        startBtn.setOnClickListener {
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

        refreshLog()
    }

    override fun onResume() {
        super.onResume()
        refreshLog()
    }

    private fun refreshLog() {
        try {
            val dir = getExternalFilesDir(null) ?: filesDir
            val logFile = File(dir, "ff_proxy_monitor_log.txt")
            if (logFile.exists()) {
                val lines = logFile.readLines()
                val last = if (lines.size > 30) lines.takeLast(30) else lines
                logView.text = last.joinToString("\n")
            } else {
                logView.text = "Log file abhi nahi bani. START dabao."
            }
        } catch (e: Exception) {
            logView.text = "Error reading log: ${e.message}"
        }
    }
}
