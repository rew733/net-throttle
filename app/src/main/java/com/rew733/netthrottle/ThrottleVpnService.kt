package com.rew733.netthrottle

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import hev.htproxy.TProxyService
import java.io.File

class ThrottleVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.rew733.netthrottle.START"
        const val ACTION_STOP = "com.rew733.netthrottle.STOP"
        const val ACTION_UPDATE_SPEED = "com.rew733.netthrottle.UPDATE_SPEED"
        const val ACTION_UPDATE_PORTS = "com.rew733.netthrottle.UPDATE_PORTS"
        const val ACTION_REFRESH = "com.rew733.netthrottle.REFRESH"

        const val EXTRA_PACKAGES = "extra_packages"
        const val EXTRA_BLOCKED_PACKAGES = "extra_blocked_packages"
        const val EXTRA_SPEED_KBPS = "extra_speed_kbps"
        const val EXTRA_OTHER_TCP_KBPS = "extra_other_tcp_kbps"
        const val EXTRA_BLOCK_QUIC = "extra_block_quic"
        const val EXTRA_ALLOW_CALLS_UDP = "extra_allow_calls_udp"
        const val EXTRA_CALLS_PPS = "extra_calls_pps"
        const val EXTRA_FOREGROUND_ONLY_MODE = "extra_foreground_only_mode"

        @Volatile
        var isRunning = false
            private set

        @Volatile
        var liveDownKbps: Int = 0
            private set

        @Volatile
        var liveUpKbps: Int = 0
            private set

        @Volatile
        var liveConnections: Int = 0
            private set

        @Volatile
        var liveWebTcpKbps: Int = 0
            private set

        @Volatile
        var liveOtherTcpKbps: Int = 0
            private set

        @Volatile
        var liveCallsUdpKbps: Int = 0
            private set

        @Volatile
        var liveQuicBlockedCount: Int = 0
            private set

        @Volatile
        var diagnosticStatus: String = "الحالة: متوقف"
            private set

        private const val CHANNEL_ID = "net_throttle_vpn"
        private const val NOTIF_ID = 101
        private const val AUTO_REFRESH_INTERVAL_MS = 15 * 60 * 1000L

        private val PROTECTED_SYSTEM_PREFIXES = listOf(
            "com.android.settings",
            "com.android.phone",
            "com.android.dialer",
            "com.android.contacts",
            "com.android.mms",
            "com.android.messaging",
            "com.android.camera",
            "com.android.systemui",
            "com.android.providers",
            "com.android.vending",
            "com.google.android.gms",
            "com.google.android.gsf",
            "com.google.android.dialer",
            "com.google.android.contacts",
            "com.google.android.apps.messaging",
            "com.google.android.gm",
            "com.google.android.calendar",
            "com.google.android.deskclock",
            "com.google.android.calculator",
            "com.google.android.apps.safetyhub",
            "com.google.android.apps.restore",
            "com.google.android.packageinstaller",
            "com.google.android.permissioncontroller",
            "com.motorola."
        )

        fun isProtectedSystemPackage(pkg: String): Boolean {
            if (pkg == "android" || pkg.startsWith("android.")) return true
            return PROTECTED_SYSTEM_PREFIXES.any { pkg.startsWith(it) }
        }
    }

    private var vpnInterface: ParcelFileDescriptor? = null

    private val webTcpDownLimiter = TokenBucketLimiter(60 * 1024L)
    private val webTcpUpLimiter = TokenBucketLimiter(60 * 1024L)
    private val otherTcpDownLimiter = TokenBucketLimiter(30 * 1024L)
    private val otherTcpUpLimiter = TokenBucketLimiter(30 * 1024L)

    private var socksServer: LocalSocks5Server? = null

    private var currentWebKbps: Int = 60
    private var currentOtherTcpKbps: Int = 30
    private var currentBlockQuic: Boolean = true
    private var currentAllowCallsUdp: Boolean = true
    private var currentCallsPps: Int = 30

    private var routingModeDesc: String = ""
    private var foregroundOnlyMode: Boolean = false
    private var managedAppNames: Map<String, String> = emptyMap()
    private var allInstalledPackages: List<String> = emptyList()
    private var lastDetectedActivePkg: String? = null
    private var currentTunnelTargetPkg: String? = "__NONE__"

    private var lastDownBytes = 0L
    private var lastUpBytes = 0L
    private var lastWebBytes = 0L
    private var lastOtherBytes = 0L
    private var lastCallsBytes = 0L

    private val handler = Handler(Looper.getMainLooper())

    private val statsRunnable = object : Runnable {
        override fun run() {
            if (isRunning) {
                val server = socksServer
                if (server != null) {
                    if (foregroundOnlyMode) {
                        monitorActiveAppWithoutRestart(server)
                    }
                    val curDown = server.totalDownloadBytes.get()
                    val curUp = server.totalUploadBytes.get()
                    val curWeb = server.webTcpBytes.get()
                    val curOther = server.otherTcpBytes.get()
                    val curCalls = server.callsUdpBytes.get()

                    liveDownKbps = (((curDown - lastDownBytes) * 2) / 1024L).toInt().coerceAtLeast(0)
                    liveUpKbps = (((curUp - lastUpBytes) * 2) / 1024L).toInt().coerceAtLeast(0)
                    liveWebTcpKbps = (((curWeb - lastWebBytes) * 2) / 1024L).toInt().coerceAtLeast(0)
                    liveOtherTcpKbps = (((curOther - lastOtherBytes) * 2) / 1024L).toInt().coerceAtLeast(0)
                    liveCallsUdpKbps = (((curCalls - lastCallsBytes) * 2) / 1024L).toInt().coerceAtLeast(0)

                    lastDownBytes = curDown
                    lastUpBytes = curUp
                    lastWebBytes = curWeb
                    lastOtherBytes = curOther
                    lastCallsBytes = curCalls

                    liveConnections = server.activeConnections.get()
                    liveQuicBlockedCount = server.quicBlockedCount.get()
                    val blocked = server.blockedCount.get()
                    val errs = server.errorCount.get()
                    diagnosticStatus = "نشط ($routingModeDesc) | Web: $currentWebKbps KB/s | محظور: $blocked | أخطاء: $errs"
                }
                handler.postDelayed(this, 500L)
            }
        }
    }

    private val autoRefreshRunnable = object : Runnable {
        override fun run() {
            if (isRunning) {
                performInternalRefresh("تحديث تلقائي")
                handler.postDelayed(this, AUTO_REFRESH_INTERVAL_MS)
            }
        }
    }

    private fun applyPortRulesToServer() {
        val webBytes = currentWebKbps.coerceAtLeast(1) * 1024L
        val otherBytes = currentOtherTcpKbps.coerceAtLeast(1) * 1024L
        webTcpDownLimiter.updateRate(webBytes)
        webTcpUpLimiter.updateRate(webBytes)
        otherTcpDownLimiter.updateRate(otherBytes)
        otherTcpUpLimiter.updateRate(otherBytes)

        socksServer?.let { server ->
            server.blockQuicUdp443 = currentBlockQuic
            server.allowCallsUdp = currentAllowCallsUdp
            server.maxCallsPacketsPerSec = currentCallsPps.coerceAtLeast(1)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopVpn()
                return START_NOT_STICKY
            }
            ACTION_UPDATE_SPEED -> {
                currentWebKbps = intent.getIntExtra(EXTRA_SPEED_KBPS, 60).coerceAtLeast(1)
                applyPortRulesToServer()
                updateNotification("$routingModeDesc | الحد: $currentWebKbps KB/s")
                return START_STICKY
            }
            ACTION_UPDATE_PORTS -> {
                currentWebKbps = intent.getIntExtra(EXTRA_SPEED_KBPS, currentWebKbps).coerceAtLeast(1)
                currentOtherTcpKbps = intent.getIntExtra(EXTRA_OTHER_TCP_KBPS, currentOtherTcpKbps).coerceAtLeast(1)
                currentBlockQuic = intent.getBooleanExtra(EXTRA_BLOCK_QUIC, currentBlockQuic)
                currentAllowCallsUdp = intent.getBooleanExtra(EXTRA_ALLOW_CALLS_UDP, currentAllowCallsUdp)
                currentCallsPps = intent.getIntExtra(EXTRA_CALLS_PPS, currentCallsPps).coerceAtLeast(1)
                applyPortRulesToServer()
                updateNotification("$routingModeDesc | Web: $currentWebKbps KB/s")
                return START_STICKY
            }
            ACTION_REFRESH -> {
                if (isRunning) {
                    performInternalRefresh("تم التحديث يدوياً")
                    handler.removeCallbacks(autoRefreshRunnable)
                    handler.postDelayed(autoRefreshRunnable, AUTO_REFRESH_INTERVAL_MS)
                }
                return START_STICKY
            }
            ACTION_START -> {
                val throttled = intent.getStringArrayListExtra(EXTRA_PACKAGES) ?: arrayListOf()
                val blocked = intent.getStringArrayListExtra(EXTRA_BLOCKED_PACKAGES) ?: arrayListOf()
                foregroundOnlyMode = intent.getBooleanExtra(EXTRA_FOREGROUND_ONLY_MODE, false)
                currentWebKbps = intent.getIntExtra(EXTRA_SPEED_KBPS, 60).coerceAtLeast(1)
                currentOtherTcpKbps = intent.getIntExtra(EXTRA_OTHER_TCP_KBPS, 30).coerceAtLeast(1)
                currentBlockQuic = intent.getBooleanExtra(EXTRA_BLOCK_QUIC, true)
                currentAllowCallsUdp = intent.getBooleanExtra(EXTRA_ALLOW_CALLS_UDP, true)
                currentCallsPps = intent.getIntExtra(EXTRA_CALLS_PPS, 30).coerceAtLeast(1)

                applyPortRulesToServer()
                scanPackages()
                lastDetectedActivePkg = null
                currentTunnelTargetPkg = "__NONE__"
                startForegroundServiceWithNotification("جاري التشغيل...")
                startVpn(throttled, blocked, currentWebKbps)
            }
        }
        return START_STICKY
    }

    private fun scanPackages() {
        val pm = packageManager
        val list = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(0)
        }
        val map = mutableMapOf<String, String>()
        val all = mutableListOf<String>()
        for (info in list) {
            val pkg = info.packageName
            all.add(pkg)
            if (pkg == packageName) continue
            if (isProtectedSystemPackage(pkg)) continue
            if (pm.getLaunchIntentForPackage(pkg) != null) {
                map[pkg] = pm.getApplicationLabel(info).toString()
            }
        }
        managedAppNames = map
        allInstalledPackages = all
    }

    private fun detectForegroundManagedPackage(): String? {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isInteractive) return null

        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - 180_000L, now)
        val event = UsageEvents.Event()
        var latestEventPkg: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                latestEventPkg = event.packageName
            }
        }

        val candidate = latestEventPkg ?: lastDetectedActivePkg ?: return null
        lastDetectedActivePkg = candidate

        if (candidate == packageName) return null
        return if (managedAppNames.containsKey(candidate)) candidate else null
    }

    private fun monitorActiveAppWithoutRestart(server: LocalSocks5Server) {
        val activePkg = detectForegroundManagedPackage()
        server.foregroundOnlyEnabled = true
        server.hasActiveForegroundApp = (activePkg != null)

        routingModeDesc = if (activePkg != null) {
            val label = managedAppNames[activePkg] ?: activePkg.substringAfterLast('.')
            "النشط: $label"
        } else {
            "النشط فقط: الخلفية محظورة"
        }

        if (activePkg != currentTunnelTargetPkg) {
            currentTunnelTargetPkg = activePkg
            server.refresh()
            updateNotification("$routingModeDesc | الحد: $currentWebKbps KB/s")
        }
    }

    private fun performInternalRefresh(reason: String) {
        try {
            socksServer?.refresh()
            applyPortRulesToServer()
            updateNotification("$routingModeDesc | الحد: $currentWebKbps KB/s ($reason)")
            diagnosticStatus = "$reason | الحد: $currentWebKbps KB/s | تم تصفير المقابس"
        } catch (t: Throwable) {
            diagnosticStatus = "خطأ تحديث: ${t.message}"
        }
    }

    private fun startForegroundServiceWithNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "NetThrottle VPN",
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)

        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIF_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, ThrottleVpnService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("NetThrottle نشط")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_media_pause, "إيقاف", stopIntent)
            .setOngoing(true)
            .build()
    }

    private fun startVpn(
        throttledPackages: List<String>,
        blockedPackages: List<String>,
        kbps: Int
    ) {
        stopVpnInternal()

        try {
            val server = LocalSocks5Server(
                this,
                webTcpDownLimiter,
                webTcpUpLimiter,
                otherTcpDownLimiter,
                otherTcpUpLimiter
            )
            server.foregroundOnlyEnabled = foregroundOnlyMode
            server.hasActiveForegroundApp = false
            server.blockQuicUdp443 = currentBlockQuic
            server.allowCallsUdp = currentAllowCallsUdp
            server.maxCallsPacketsPerSec = currentCallsPps
            server.start()
            socksServer = server

            lastDownBytes = 0L
            lastUpBytes = 0L
            lastWebBytes = 0L
            lastOtherBytes = 0L
            lastCallsBytes = 0L

            val builder = Builder()
                .setSession("NetThrottle")
                .setMtu(1500)
                .addAddress("10.0.0.2", 32)
                .addRoute("0.0.0.0", 0)
                .addAddress("fd00:1:fd00:1:fd00:1:fd00:1", 128)
                .addRoute("::", 0)
                .addDnsServer("8.8.8.8")
                .addDnsServer("1.1.1.1")

            if (foregroundOnlyMode || (throttledPackages.isEmpty() && blockedPackages.isEmpty())) {
                // استخدام addDisallowedApplication يجبر النواة على تمرير 100% من عمليات تيك توك وفيسبوك الفرعية داخل النفق
                // مع حماية واستثناء NetThrottle وتطبيقات النظام الحساسة فقط
                try {
                    builder.addDisallowedApplication(packageName)
                } catch (_: Exception) {
                }
                for (pkg in allInstalledPackages) {
                    if (isProtectedSystemPackage(pkg)) {
                        try {
                            builder.addDisallowedApplication(pkg)
                        } catch (_: Exception) {
                        }
                    }
                }
                routingModeDesc = if (foregroundOnlyMode) "النشط فقط: إحكام شامل" else "شامل للتطبيقات"
            } else {
                var addedCount = 0
                for (pkg in throttledPackages) {
                    if (pkg == packageName) continue
                    try {
                        builder.addAllowedApplication(pkg)
                        addedCount++
                    } catch (_: Exception) {
                    }
                }
                if (addedCount == 0) {
                    try {
                        builder.addDisallowedApplication(packageName)
                    } catch (_: Exception) {
                    }
                    for (pkg in allInstalledPackages) {
                        if (isProtectedSystemPackage(pkg)) {
                            try {
                                builder.addDisallowedApplication(pkg)
                            } catch (_: Exception) {
                            }
                        }
                    }
                    routingModeDesc = "شامل للتطبيقات"
                } else {
                    routingModeDesc = "محدد ($addedCount تطبيق)"
                }
            }

            val pfd = builder.establish()
            if (pfd == null) {
                diagnosticStatus = "خطأ: فشل إنشاء واجهة VPN"
                updateNotification("فشل إنشاء واجهة VPN")
                return
            }
            vpnInterface = pfd

            val configFile = File(cacheDir, "tproxy.conf")
            configFile.writeText(
                """
                tunnel:
                  name: tun0
                  mtu: 1500
                  multi-queue: false
                  ipv4: 10.0.0.2
                  ipv6: 'fd00:1:fd00:1:fd00:1:fd00:1'
                socks5:
                  port: ${server.port}
                  address: 127.0.0.1
                  udp: 'udp'
                misc:
                  task-stack-size: 81920
                  tcp-buffer-size: 512
                  connect-timeout: 5000
                  read-write-timeout: 60000
                  log-level: warn
                """.trimIndent()
            )

            TProxyService.TProxyStartService(configFile.absolutePath, pfd.fd)
            isRunning = true
            diagnosticStatus = "نشط ($routingModeDesc) | الحد: $kbps KB/s"
            updateNotification("$routingModeDesc | الحد: $kbps KB/s")

            handler.removeCallbacks(statsRunnable)
            handler.postDelayed(statsRunnable, 500L)

            handler.removeCallbacks(autoRefreshRunnable)
            handler.postDelayed(autoRefreshRunnable, AUTO_REFRESH_INTERVAL_MS)
        } catch (t: Throwable) {
            isRunning = false
            diagnosticStatus = "استثناء: ${t.javaClass.simpleName}: ${t.message}"
            updateNotification("خطأ: ${t.javaClass.simpleName}")
        }
    }

    private fun stopVpnInternal() {
        handler.removeCallbacks(statsRunnable)
        handler.removeCallbacks(autoRefreshRunnable)
        if (isRunning) {
            try {
                TProxyService.TProxyStopService()
            } catch (_: Throwable) {
            }
        }
        isRunning = false
        liveDownKbps = 0
        liveUpKbps = 0
        liveConnections = 0
        liveWebTcpKbps = 0
        liveOtherTcpKbps = 0
        liveCallsUdpKbps = 0
        liveQuicBlockedCount = 0
        diagnosticStatus = "الحالة: متوقف"
        socksServer?.stop()
        socksServer = null
        try {
            vpnInterface?.close()
        } catch (_: Throwable) {
        }
        vpnInterface = null
    }

    private fun stopVpn() {
        stopVpnInternal()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopVpnInternal()
        super.onDestroy()
    }
}
