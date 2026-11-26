package com.rew733.netthrottle

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
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
        const val ACTION_REFRESH = "com.rew733.netthrottle.REFRESH"
        const val EXTRA_PACKAGES = "extra_packages"
        const val EXTRA_BLOCKED_PACKAGES = "extra_blocked_packages"
        const val EXTRA_SPEED_KBPS = "extra_speed_kbps"
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
        var diagnosticStatus: String = "الحالة: متوقف"
            private set

        private const val CHANNEL_ID = "net_throttle_vpn"
        private const val NOTIF_ID = 101
        private const val AUTO_REFRESH_INTERVAL_MS = 5 * 60 * 1000L
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private val downloadLimiter = TokenBucketLimiter(65 * 1024L)
    private val uploadLimiter = TokenBucketLimiter(65 * 1024L)
    private val udpLimiter = TokenBucketLimiter(35 * 1024L)
    private var socksServer: LocalSocks5Server? = null
    private var currentKbps: Int = 100
    private var routingModeDesc: String = ""

    private var foregroundOnlyMode: Boolean = false
    private var userInstalledPackages: Set<String> = emptySet()
    private var lastActiveUserApp: String? = null
    private var cachedThrottled: List<String> = emptyList()
    private var cachedBlocked: List<String> = emptyList()

    private var lastDownBytes = 0L
    private var lastUpBytes = 0L

    private val handler = Handler(Looper.getMainLooper())

    private val statsRunnable = object : Runnable {
        override fun run() {
            if (isRunning) {
                if (foregroundOnlyMode) {
                    checkForegroundAppSwitch()
                }
                val server = socksServer
                if (server != null) {
                    val curDown = server.totalDownloadBytes.get()
                    val curUp = server.totalUploadBytes.get()
                    liveDownKbps = ((curDown - lastDownBytes) / 1024L).toInt().coerceAtLeast(0)
                    liveUpKbps = ((curUp - lastUpBytes) / 1024L).toInt().coerceAtLeast(0)
                    lastDownBytes = curDown
                    lastUpBytes = curUp
                    liveConnections = server.activeConnections.get()
                    val errs = server.errorCount.get()
                    diagnosticStatus = "نشط ($routingModeDesc) | الحد: $currentKbps KB/s | أخطاء: $errs (${server.lastError})"
                }
                handler.postDelayed(this, 1000L)
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

    private fun applySpeedLimits(kbps: Int) {
        val totalBytes = kbps * 1024L
        val tcpBytes = (totalBytes * 0.65).toLong().coerceAtLeast(1024L)
        val udpBytes = (totalBytes * 0.35).toLong().coerceAtLeast(1024L)
        downloadLimiter.updateRate(tcpBytes)
        uploadLimiter.updateRate(tcpBytes)
        udpLimiter.updateRate(udpBytes)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopVpn()
                return START_NOT_STICKY
            }
            ACTION_UPDATE_SPEED -> {
                currentKbps = intent.getIntExtra(EXTRA_SPEED_KBPS, 100).coerceAtLeast(1)
                applySpeedLimits(currentKbps)
                updateNotification("الحد الكلي: $currentKbps KB/s")
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
                cachedThrottled = intent.getStringArrayListExtra(EXTRA_PACKAGES) ?: arrayListOf()
                cachedBlocked = intent.getStringArrayListExtra(EXTRA_BLOCKED_PACKAGES) ?: arrayListOf()
                foregroundOnlyMode = intent.getBooleanExtra(EXTRA_FOREGROUND_ONLY_MODE, false)
                currentKbps = intent.getIntExtra(EXTRA_SPEED_KBPS, 100).coerceAtLeast(1)
                applySpeedLimits(currentKbps)
                userInstalledPackages = collectUserPackages()
                lastActiveUserApp = null
                startForegroundServiceWithNotification("جاري التشغيل...")
                startVpn(cachedThrottled, cachedBlocked, currentKbps)
            }
        }
        return START_STICKY
    }

    private fun collectUserPackages(): Set<String> {
        val pm = packageManager
        val list = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(0)
        }
        return list.filter { info ->
            info.packageName != packageName &&
                    (info.flags and ApplicationInfo.FLAG_SYSTEM) == 0 &&
                    pm.getLaunchIntentForPackage(info.packageName) != null
        }.map { it.packageName }.toSet()
    }

    private fun detectForegroundUserPackage(): String? {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isInteractive) return null

        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - 10_000L, now)
        val event = UsageEvents.Event()
        var latestPkg: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                latestPkg = event.packageName
            }
        }
        if (latestPkg == null) return lastActiveUserApp
        if (latestPkg == packageName) return null
        return if (userInstalledPackages.contains(latestPkg)) latestPkg else null
    }

    private fun checkForegroundAppSwitch() {
        val activePkg = detectForegroundUserPackage()
        if (activePkg != lastActiveUserApp) {
            lastActiveUserApp = activePkg
            socksServer?.refresh()
            startVpn(cachedThrottled, cachedBlocked, currentKbps)
        }
    }

    private fun performInternalRefresh(reason: String) {
        try {
            socksServer?.refresh()
            applySpeedLimits(currentKbps)
            updateNotification("الحد: $currentKbps KB/s ($reason)")
            diagnosticStatus = "$reason | الحد: $currentKbps KB/s | تم تصفير المقابس"
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
            val server = LocalSocks5Server(this, downloadLimiter, uploadLimiter, udpLimiter)
            server.start()
            socksServer = server
            lastDownBytes = 0L
            lastUpBytes = 0L

            val builder = Builder()
                .setSession("NetThrottle")
                .setMtu(1500)
                .addAddress("10.0.0.2", 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("8.8.8.8")
                .addDnsServer("1.1.1.1")

            var activeTunnelEnabled = true

            if (foregroundOnlyMode) {
                // وضع التطبيق النشط فقط: تطبيقات النظام مستثناة تلقائياً،
                // والمفتوح على الشاشة فقط يعمل بالسرعة المحددة بينما البقية محظورة
                val currentApp = lastActiveUserApp
                if (currentApp != null) {
                    try {
                        builder.addAllowedApplication(currentApp)
                        routingModeDesc = "النشط فقط: $currentApp"
                    } catch (_: Exception) {
                        activeTunnelEnabled = false
                        routingModeDesc = "النشط فقط: وضع السكون"
                    }
                } else {
                    // كل تطبيقات المستخدم تدخل النفق بدون تشغيل الخادم (حظر كامل للخلفية مع حماية النظام)
                    for (pkg in userInstalledPackages) {
                        try {
                            builder.addAllowedApplication(pkg)
                        } catch (_: Exception) {
                        }
                    }
                    activeTunnelEnabled = false
                    routingModeDesc = "النشط فقط: الخلفية محظورة"
                }
            } else if (throttledPackages.isEmpty() && blockedPackages.isEmpty()) {
                for (pkg in userInstalledPackages) {
                    try {
                        builder.addAllowedApplication(pkg)
                    } catch (_: Exception) {
                    }
                }
                routingModeDesc = "شامل لتطبيقات المستخدم"
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
                    for (pkg in userInstalledPackages) {
                        try {
                            builder.addAllowedApplication(pkg)
                        } catch (_: Exception) {
                        }
                    }
                    routingModeDesc = "شامل لتطبيقات المستخدم"
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

            if (activeTunnelEnabled) {
                val configFile = File(cacheDir, "tproxy.conf")
                configFile.writeText(
                    """
                    tunnel:
                      name: tun0
                      mtu: 1500
                      multi-queue: false
                      ipv4: 10.0.0.2
                    socks5:
                      port: ${server.port}
                      address: 127.0.0.1
                      udp: 'udp'
                    misc:
                      task-stack-size: 81920
                      tcp-buffer-size: 2048
                      connect-timeout: 5000
                      read-write-timeout: 60000
                      log-level: warn
                    """.trimIndent()
                )
                TProxyService.TProxyStartService(configFile.absolutePath, pfd.fd)
            } else {
                // إيقاف خادم التمرير ليتم إسقاط حزم تطبيقات الخلفية فوراً دون استهلاك أي بايت
                server.stop()
                socksServer = null
            }

            isRunning = true
            diagnosticStatus = "نشط ($routingModeDesc) | الحد: $kbps KB/s"
            updateNotification("الحد: $kbps KB/s ($routingModeDesc)")

            handler.removeCallbacks(statsRunnable)
            handler.postDelayed(statsRunnable, 1000L)

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
