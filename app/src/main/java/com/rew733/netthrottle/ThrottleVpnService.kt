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
        private const val AUTO_REFRESH_INTERVAL_MS = 15 * 60 * 1000L
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private val downloadLimiter = TokenBucketLimiter(65 * 1024L)
    private val uploadLimiter = TokenBucketLimiter(65 * 1024L)
    private val udpLimiter = TokenBucketLimiter(35 * 1024L)
    private var socksServer: LocalSocks5Server? = null
    private var currentKbps: Int = 100
    private var routingModeDesc: String = ""

    private var foregroundOnlyMode: Boolean = false
    private var userPackages: Set<String> = emptySet()
    private var lastDetectedActivePkg: String? = null
    private var currentTunnelTargetPkg: String? = "__NONE__"

    private var lastDownBytes = 0L
    private var lastUpBytes = 0L

    private val handler = Handler(Looper.getMainLooper())

    private val statsRunnable = object : Runnable {
        override fun run() {
            if (isRunning) {
                val server = socksServer
                if (server != null) {
                    if (foregroundOnlyMode) {
                        monitorAndRouteActiveApp(server)
                    }
                    val curDown = server.totalDownloadBytes.get()
                    val curUp = server.totalUploadBytes.get()
                    liveDownKbps = ((curDown - lastDownBytes) / 1024L).toInt().coerceAtLeast(0)
                    liveUpKbps = ((curUp - lastUpBytes) / 1024L).toInt().coerceAtLeast(0)
                    lastDownBytes = curDown
                    lastUpBytes = curUp
                    liveConnections = server.activeConnections.get()
                    val blocked = server.blockedCount.get()
                    val errs = server.errorCount.get()
                    diagnosticStatus = "نشط ($routingModeDesc) | الحد: $currentKbps KB/s | محظور: $blocked | أخطاء: $errs"
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
                val throttled = intent.getStringArrayListExtra(EXTRA_PACKAGES) ?: arrayListOf()
                val blocked = intent.getStringArrayListExtra(EXTRA_BLOCKED_PACKAGES) ?: arrayListOf()
                foregroundOnlyMode = intent.getBooleanExtra(EXTRA_FOREGROUND_ONLY_MODE, false)
                currentKbps = intent.getIntExtra(EXTRA_SPEED_KBPS, 100).coerceAtLeast(1)
                applySpeedLimits(currentKbps)
                userPackages = collectUserPackages()
                lastDetectedActivePkg = null
                currentTunnelTargetPkg = "__NONE__"
                startForegroundServiceWithNotification("جاري التشغيل...")
                startVpn(throttled, blocked, currentKbps)
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
        // فحص أحداث الانتقال للواجهة خلال آخر دقيقتين
        val events = usm.queryEvents(now - 120_000L, now)
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

        // إذا كان التطبيق المفتوح هو NetThrottle نفسه أو مشغل الشاشة، نوقف تمرير البيانات
        if (candidate == packageName) return null
        return if (userPackages.contains(candidate)) candidate else null
    }

    private fun monitorAndRouteActiveApp(server: LocalSocks5Server) {
        val activePkg = detectForegroundUserPackage()
        server.foregroundOnlyEnabled = true
        server.hasActiveForegroundApp = (activePkg != null)

        if (activePkg != currentTunnelTargetPkg) {
            currentTunnelTargetPkg = activePkg
            server.refresh()
            rebuildVpnInterfaceForTarget(activePkg)
        }

        routingModeDesc = if (activePkg != null) {
            "النشط: ${activePkg.substringAfterLast('.')}"
        } else {
            "النشط فقط: الخلفية محظورة"
        }
    }

    private fun rebuildVpnInterfaceForTarget(targetPkg: String?) {
        try {
            val builder = Builder()
                .setSession("NetThrottle")
                .setMtu(1500)
                .addAddress("10.0.0.2", 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("8.8.8.8")
                .addDnsServer("1.1.1.1")

            if (targetPkg != null) {
                // عزل التطبيق المفتوح فقط داخل النفق، بينما تُحظر بقية تطبيقات المستخدم
                // وتُستثنى تطبيقات النظام بالكامل
                for (pkg in userPackages) {
                    if (pkg != targetPkg) {
                        // تطبيقات الخلفية: لا نضيفها للنفق إذا أردنا حظرها عبر نفق ثانٍ،
                        // لكن أندرويد يسمح بنفق واحد فقط؛ لذلك ندخل كل تطبيقات المستخدم
                        // ونسمح للتطبيق المفتوح فقط بالمرور في الخادم
                    }
                }
                for (pkg in userPackages) {
                    try {
                        builder.addAllowedApplication(pkg)
                    } catch (_: Exception) {
                    }
                }
            } else {
                for (pkg in userPackages) {
                    try {
                        builder.addAllowedApplication(pkg)
                    } catch (_: Exception) {
                    }
                }
            }

            val newPfd = builder.establish() ?: return
            val oldPfd = vpnInterface
            vpnInterface = newPfd

            try {
                TProxyService.TProxyStopService()
            } catch (_: Throwable) {
            }
            try {
                oldPfd?.close()
            } catch (_: Throwable) {
            }

            val server = socksServer ?: return
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
            TProxyService.TProxyStartService(configFile.absolutePath, newPfd.fd)
        } catch (_: Throwable) {
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
            server.foregroundOnlyEnabled = foregroundOnlyMode
            server.hasActiveForegroundApp = false
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

            if (foregroundOnlyMode || (throttledPackages.isEmpty() && blockedPackages.isEmpty())) {
                for (pkg in userPackages) {
                    try {
                        builder.addAllowedApplication(pkg)
                    } catch (_: Exception) {
                    }
                }
                routingModeDesc = if (foregroundOnlyMode) "النشط فقط: جاري الرصد" else "شامل لتطبيقات المستخدم"
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
                    for (pkg in userPackages) {
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
            isRunning = true
            diagnosticStatus = "نشط ($routingModeDesc) | الحد: $kbps KB/s"
            updateNotification("الحد: $kbps KB/s")

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
