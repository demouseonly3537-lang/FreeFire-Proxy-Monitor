package com.ashxmay.ffproxymonitor

import android.app.*
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.TrafficStats
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileWriter
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.*

class MonitorService : Service() {

    private val channelId = "ff_monitor_channel"
    private val notificationId = 1001
    private lateinit var connectivityManager: ConnectivityManager
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isVpnActive = false
    private var freeFireUid = -1
    private var freeFirePkg = "none"
    private var lastDevRx: Long = 0
    private var lastDevTx: Long = 0
    private var lastFfRx: Long = 0
    private var lastFfTx: Long = 0
    private var phaseStartRx: Long = 0
    private var phaseStartTx: Long = 0
    private var phaseStartTime: Long = 0
    private lateinit var logFile: File
    private var networkStatsManager: NetworkStatsManager? = null

    private val candidates = listOf(
        "com.garena.game.ffi",
        "com.dts.freefireth",
        "com.dts.freefiremax",
        "com.garena.game.kgth",
        "com.garena.game.kgvn",
        "com.garena.game.kgtw",
        "com.garena.game.kgid",
        "com.garena.game.kgph",
        "com.garena.game.kgmy",
        "com.garena.game.kgbr",
        "com.garena.game.freefire"
    )

    private val logRunnable = object : Runnable {
        override fun run() {
            logFullState()
            handler.postDelayed(this, 3000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            networkStatsManager = getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager
        }
        createNotificationChannel()
        setupLogFile()
        resolveFreeFireUid()
        registerVpnCallback()
        checkCurrentVpnState()
        // init phase counters
        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()
        lastDevRx = rx
        lastDevTx = tx
        phaseStartRx = rx
        phaseStartTx = tx
        phaseStartTime = System.currentTimeMillis()
        handler.post(logRunnable)
        appendLog("Service created. pkg=$freeFirePkg UID=$freeFireUid PID=${Process.myPid()}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification("Monitoring Free Fire network...")
        startForeground(notificationId, notification)
        appendLog("onStartCommand - service running")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        networkCallback?.let { connectivityManager.unregisterNetworkCallback(it) }
        handler.removeCallbacks(logRunnable)
        appendLog("=== MONITOR STOPPED ===")
    }

    private fun resolveFreeFireUid() {
        val pm = packageManager
        for (pkg in candidates) {
            try {
                val info = pm.getApplicationInfo(pkg, 0)
                freeFireUid = info.uid
                freeFirePkg = pkg
                appendLog("Found Free Fire: $pkg UID=$freeFireUid")
                return
            } catch (_: Exception) {}
        }
        try {
            val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            for (app in apps) {
                val name = app.packageName.lowercase()
                val label = try { pm.getApplicationLabel(app).toString().lowercase() } catch (_: Exception) { "" }
                if (name.contains("freefire") || name.contains("free.fire") || name.contains("ffi") ||
                    (name.contains("garena") && (label.contains("free fire") || label.contains("freefire"))) ||
                    label.contains("free fire")) {
                    freeFireUid = app.uid
                    freeFirePkg = app.packageName
                    appendLog("Auto-detected: $freeFirePkg UID=$freeFireUid")
                    return
                }
            }
        } catch (e: Exception) {
            appendLog("Scan error: ${e.message}")
        }
        appendLog("ERROR: Free Fire package not found")
        freeFireUid = -1
        freeFirePkg = "none"
    }

    private fun setupLogFile() {
        val dir = getExternalFilesDir(null) ?: filesDir
        logFile = File(dir, "ff_proxy_monitor_log.txt")
        if (!logFile.exists()) logFile.createNewFile()
        appendLog("=== FF PROXY MONITOR STARTED ===")
        appendLog("Log path: ${logFile.absolutePath}")
        appendLog("Time: ${now()}")
    }

    private fun checkCurrentVpnState() {
        val active = connectivityManager.activeNetwork
        val caps = active?.let { connectivityManager.getNetworkCapabilities(it) }
        isVpnActive = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        appendLog("Initial VPN state: ${if (isVpnActive) "ON" else "OFF"}")
    }

    private fun registerVpnCallback() {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (!isVpnActive) {
                    // close previous phase summary
                    writePhaseSummary("BEFORE_PROXY")
                    isVpnActive = true
                    // reset phase counters
                    val rx = TrafficStats.getTotalRxBytes()
                    val tx = TrafficStats.getTotalTxBytes()
                    phaseStartRx = rx
                    phaseStartTx = tx
                    phaseStartTime = System.currentTimeMillis()
                    appendLog("")
                    appendLog("========== PROXY / VPN CONNECTED ==========")
                    appendLog("Timestamp: ${now()}")
                    appendLog("Status: PROXY ON - ab se AFTER data")
                    logNetworkInterfaces()
                    updateNotification("VPN/Proxy ACTIVE - AFTER data")
                }
            }

            override fun onLost(network: Network) {
                if (isVpnActive) {
                    writePhaseSummary("AFTER_PROXY")
                    isVpnActive = false
                    val rx = TrafficStats.getTotalRxBytes()
                    val tx = TrafficStats.getTotalTxBytes()
                    phaseStartRx = rx
                    phaseStartTx = tx
                    phaseStartTime = System.currentTimeMillis()
                    appendLog("")
                    appendLog("========== PROXY / VPN DISCONNECTED ==========")
                    appendLog("Timestamp: ${now()}")
                    appendLog("Status: PROXY OFF - ab se BEFORE data")
                    updateNotification("VPN/Proxy OFF - BEFORE data")
                }
            }
        }
        connectivityManager.registerNetworkCallback(request, networkCallback!!)
    }

    private fun writePhaseSummary(phase: String) {
        val nowRx = TrafficStats.getTotalRxBytes()
        val nowTx = TrafficStats.getTotalTxBytes()
        val dRx = (nowRx - phaseStartRx).coerceAtLeast(0)
        val dTx = (nowTx - phaseStartTx).coerceAtLeast(0)
        val sec = ((System.currentTimeMillis() - phaseStartTime) / 1000).coerceAtLeast(1)
        val kbpsDown = (dRx * 8 / 1000) / sec
        val kbpsUp = (dTx * 8 / 1000) / sec
        appendLog("")
        appendLog("----- $phase PHASE SUMMARY -----")
        appendLog("Duration: ${sec}s")
        appendLog("Downloaded: ${formatBytes(dRx)}  (${kbpsDown} kbps avg)")
        appendLog("Uploaded:   ${formatBytes(dTx)}  (${kbpsUp} kbps avg)")
        appendLog("-------------------------------")
        appendLog("")
    }

    private fun formatBytes(b: Long): String {
        return when {
            b >= 1_000_000 -> String.format("%.2f MB", b / 1_000_000.0)
            b >= 1_000 -> String.format("%.1f KB", b / 1_000.0)
            else -> "$b B"
        }
    }

    private fun getFfTraffic(): Pair<Long, Long>? {
        if (freeFireUid == -1) return null

        // TrafficStats first
        val rxTs = TrafficStats.getUidRxBytes(freeFireUid)
        val txTs = TrafficStats.getUidTxBytes(freeFireUid)
        if (rxTs != TrafficStats.UNSUPPORTED.toLong() && txTs != TrafficStats.UNSUPPORTED.toLong() && rxTs >= 0) {
            return rxTs to txTs
        }

        // NetworkStatsManager fallback
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && networkStatsManager != null) {
            try {
                val end = System.currentTimeMillis()
                val start = end - 12 * 60 * 60 * 1000L
                var rx = 0L
                var tx = 0L

                // try wifi
                try {
                    val s = networkStatsManager!!.queryDetailsForUid(
                        ConnectivityManager.TYPE_WIFI, "", start, end, freeFireUid
                    )
                    val b = NetworkStats.Bucket()
                    while (s.hasNextBucket()) {
                        s.getNextBucket(b)
                        rx += b.rxBytes
                        tx += b.txBytes
                    }
                    s.close()
                } catch (_: Exception) {}

                // try mobile with empty subscriber
                try {
                    val s = networkStatsManager!!.queryDetailsForUid(
                        ConnectivityManager.TYPE_MOBILE, "", start, end, freeFireUid
                    )
                    val b = NetworkStats.Bucket()
                    while (s.hasNextBucket()) {
                        s.getNextBucket(b)
                        rx += b.rxBytes
                        tx += b.txBytes
                    }
                    s.close()
                } catch (_: Exception) {}

                if (rx > 0 || tx > 0) return rx to tx
            } catch (_: Exception) {}
        }
        return null
    }

    private fun logFullState() {
        val phase = if (isVpnActive) "AFTER_PROXY" else "BEFORE_PROXY"
        val sb = StringBuilder()
        sb.append("[${now()}] [$phase] ")

        // Free Fire traffic if available
        val ff = getFfTraffic()
        if (ff != null) {
            val (rx, tx) = ff
            val dRx = if (lastFfRx == 0L) 0 else (rx - lastFfRx).coerceAtLeast(0)
            val dTx = if (lastFfTx == 0L) 0 else (tx - lastFfTx).coerceAtLeast(0)
            lastFfRx = rx
            lastFfTx = tx
            sb.append("FF_RX=$rx FF_TX=$tx dRX=$dRx dTX=$dTx ")
        } else {
            sb.append("FF_pkg=$freeFirePkg ")
        }

        // Device total + delta (always works)
        val totalRx = TrafficStats.getTotalRxBytes()
        val totalTx = TrafficStats.getTotalTxBytes()
        val dRx = if (lastDevRx == 0L) 0 else (totalRx - lastDevRx).coerceAtLeast(0)
        val dTx = if (lastDevTx == 0L) 0 else (totalTx - lastDevTx).coerceAtLeast(0)
        lastDevRx = totalRx
        lastDevTx = totalTx

        // speed estimate (last 3 sec)
        val kbpsDown = (dRx * 8 / 1000) / 3
        val kbpsUp = (dTx * 8 / 1000) / 3

        sb.append("DEV_dRX=${formatBytes(dRx)} DEV_dTX=${formatBytes(dTx)} ")
        sb.append("${kbpsDown}↓/${kbpsUp}↑ kbps ")

        val ifaces = getVpnInterfaces()
        sb.append("IFACES=$ifaces")

        appendLog(sb.toString())
    }

    private fun logNetworkInterfaces() {
        try {
            val list = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (nif in list) {
                if (nif.isUp && !nif.isLoopback) {
                    appendLog("  IFACE: ${nif.name}")
                }
            }
        } catch (e: Exception) {
            appendLog("IFACE error: ${e.message}")
        }
    }

    private fun getVpnInterfaces(): String {
        return try {
            val list = Collections.list(NetworkInterface.getNetworkInterfaces())
            list.filter { it.isUp && (it.name.startsWith("tun") || it.name.startsWith("ppp") || it.name.startsWith("wg") || it.name.contains("vpn")) }
                .joinToString(",") { it.name }
                .ifEmpty { "none" }
        } catch (e: Exception) {
            "error"
        }
    }

    private fun appendLog(line: String) {
        try {
            if (::logFile.isInitialized) {
                FileWriter(logFile, true).use { it.appendLine(line) }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun now(): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "FF Proxy Monitor", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("FF Proxy Monitor")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(notificationId, buildNotification(text))
    }
}
