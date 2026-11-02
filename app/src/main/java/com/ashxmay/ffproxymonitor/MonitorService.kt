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
import android.os.RemoteException
import android.telephony.TelephonyManager
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
    private var lastRx: Long = 0
    private var lastTx: Long = 0
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
        "com.garena.game.freefire",
        "com.tencent.tmgp.pubgmhd"
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
            val possible = mutableListOf<String>()
            for (app in apps) {
                val name = app.packageName.lowercase()
                val label = try {
                    pm.getApplicationLabel(app).toString().lowercase()
                } catch (_: Exception) { "" }

                if (name.contains("freefire") || name.contains("free.fire") || name.contains("ffi") ||
                    name.contains("garena") || label.contains("free fire") || label.contains("freefire")) {
                    possible.add("${app.packageName} | $label | uid=${app.uid}")
                }

                if (name.contains("freefire") || name.contains("free.fire") ||
                    (name.contains("garena") && (label.contains("free fire") || label.contains("freefire") || label.contains("ffi"))) ||
                    label.contains("free fire") || label.contains("freefire")) {
                    freeFireUid = app.uid
                    freeFirePkg = app.packageName
                    appendLog("Auto-detected: $freeFirePkg UID=$freeFireUid label=$label")
                    return
                }
            }
            if (possible.isNotEmpty()) {
                appendLog("Possible related packages found:")
                possible.forEach { appendLog("  → $it") }
            } else {
                appendLog("No freefire/garena related package found on device")
            }
        } catch (e: Exception) {
            appendLog("Scan error: ${e.message}")
        }

        appendLog("ERROR: Free Fire package not found on device")
        freeFireUid = -1
        freeFirePkg = "none"
    }

    private fun setupLogFile() {
        val dir = getExternalFilesDir(null) ?: filesDir
        logFile = File(dir, "ff_proxy_monitor_log.txt")
        if (!logFile.exists()) {
            logFile.createNewFile()
        }
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
                    isVpnActive = true
                    appendLog("")
                    appendLog("========== PROXY / VPN CONNECTED ==========")
                    appendLog("Timestamp: ${now()}")
                    appendLog("Status: PROXY ON - ab se AFTER data")
                    logNetworkInterfaces()
                    snapshotTraffic("PROXY_ON_SNAPSHOT")
                    updateNotification("VPN/Proxy ACTIVE - logging AFTER data")
                }
            }

            override fun onLost(network: Network) {
                if (isVpnActive) {
                    isVpnActive = false
                    appendLog("")
                    appendLog("========== PROXY / VPN DISCONNECTED ==========")
                    appendLog("Timestamp: ${now()}")
                    appendLog("Status: PROXY OFF - ab se BEFORE data")
                    snapshotTraffic("PROXY_OFF_SNAPSHOT")
                    updateNotification("VPN/Proxy OFF - logging BEFORE data")
                }
            }
        }
        connectivityManager.registerNetworkCallback(request, networkCallback!!)
    }

    /** Get RX/TX for Free Fire UID. Tries TrafficStats first, then NetworkStatsManager. */
    private fun getFfTraffic(): Pair<Long, Long> {
        if (freeFireUid == -1) return -1L to -1L

        // 1. Try classic TrafficStats
        val rxTs = TrafficStats.getUidRxBytes(freeFireUid)
        val txTs = TrafficStats.getUidTxBytes(freeFireUid)
        if (rxTs != TrafficStats.UNSUPPORTED.toLong() && txTs != TrafficStats.UNSUPPORTED.toLong()) {
            return rxTs to txTs
        }

        // 2. Fallback: NetworkStatsManager (needs Usage Access)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && networkStatsManager != null) {
            try {
                val end = System.currentTimeMillis()
                val start = end - 24 * 60 * 60 * 1000L // last 24h cumulative

                var rx = 0L
                var tx = 0L

                // Mobile
                try {
                    val subId = try {
                        val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) tm.subscriberId else ""
                    } catch (_: Exception) { "" }

                    val bucketMobile = networkStatsManager!!.querySummaryForDevice(
                        ConnectivityManager.TYPE_MOBILE, subId, start, end
                    )
                    // Better: query per UID
                    val statsMobile = networkStatsManager!!.queryDetailsForUid(
                        ConnectivityManager.TYPE_MOBILE, subId, start, end, freeFireUid
                    )
                    val bucket = NetworkStats.Bucket()
                    while (statsMobile.hasNextBucket()) {
                        statsMobile.getNextBucket(bucket)
                        rx += bucket.rxBytes
                        tx += bucket.txBytes
                    }
                    statsMobile.close()
                } catch (_: Exception) {}

                // Wi-Fi
                try {
                    val statsWifi = networkStatsManager!!.queryDetailsForUid(
                        ConnectivityManager.TYPE_WIFI, "", start, end, freeFireUid
                    )
                    val bucket = NetworkStats.Bucket()
                    while (statsWifi.hasNextBucket()) {
                        statsWifi.getNextBucket(bucket)
                        rx += bucket.rxBytes
                        tx += bucket.txBytes
                    }
                    statsWifi.close()
                } catch (_: Exception) {}

                if (rx > 0 || tx > 0) {
                    return rx to tx
                }
            } catch (e: RemoteException) {
                appendLog("NetworkStats error: ${e.message}")
            } catch (e: SecurityException) {
                appendLog("NetworkStats SecurityException - Usage Access chahiye")
            } catch (e: Exception) {
                appendLog("NetworkStats fail: ${e.message}")
            }
        }

        return -2L to -2L // special: unsupported even after fallback
    }

    private fun logFullState() {
        val phase = if (isVpnActive) "AFTER_PROXY" else "BEFORE_PROXY"
        val sb = StringBuilder()
        sb.append("[${now()}] [$phase] ")

        if (freeFireUid != -1) {
            val (rx, tx) = getFfTraffic()
            when {
                rx == -1L -> sb.append("FF_UID=NOT_FOUND ")
                rx == -2L -> sb.append("FF_TRAFFIC=NEED_USAGE_ACCESS ")
                else -> {
                    val deltaRx = if (lastRx == 0L) 0 else (rx - lastRx).coerceAtLeast(0)
                    val deltaTx = if (lastTx == 0L) 0 else (tx - lastTx).coerceAtLeast(0)
                    lastRx = rx
                    lastTx = tx
                    sb.append("FF_RX=$rx FF_TX=$tx dRX=$deltaRx dTX=$deltaTx ")
                }
            }
        } else {
            sb.append("FF_UID=NOT_FOUND ")
        }

        val totalRx = TrafficStats.getTotalRxBytes()
        val totalTx = TrafficStats.getTotalTxBytes()
        sb.append("DEV_RX=$totalRx DEV_TX=$totalTx ")

        val ifaces = getVpnInterfaces()
        sb.append("IFACES=$ifaces")

        appendLog(sb.toString())
    }

    private fun logNetworkInterfaces() {
        try {
            val list = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (nif in list) {
                if (nif.isUp) {
                    appendLog("  IFACE: ${nif.name}  up=${nif.isUp}  loopback=${nif.isLoopback}")
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

    private fun snapshotTraffic(tag: String) {
        if (freeFireUid == -1) {
            appendLog("[$tag] FF not found")
            return
        }
        val (rx, tx) = getFfTraffic()
        appendLog("[$tag] FF_RX=$rx FF_TX=$tx")
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
            val channel = NotificationChannel(
                channelId,
                "FF Proxy Monitor",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
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
        val notification = buildNotification(text)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(notificationId, notification)
    }
}
