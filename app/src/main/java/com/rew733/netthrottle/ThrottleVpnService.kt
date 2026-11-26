package com.rew733.netthrottle

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import hev.htproxy.TProxyService
import java.io.File

class ThrottleVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.rew733.netthrottle.START"
        const val ACTION_STOP = "com.rew733.netthrottle.STOP"
        const val ACTION_UPDATE_SPEED = "com.rew733.netthrottle.UPDATE_SPEED"
        const val EXTRA_PACKAGES = "extra_packages"
        const val EXTRA_BLOCKED_PACKAGES = "extra_blocked_packages"
        const val EXTRA_SPEED_KBPS = "extra_speed_kbps"

        @Volatile
        var isRunning = false
            private set

        private const val CHANNEL_ID = "net_throttle_vpn"
        private const val NOTIF_ID = 101
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private val downloadLimiter = TokenBucketLimiter(100 * 1024L)
    private val uploadLimiter = TokenBucketLimiter(100 * 1024L)
    private var socksServer: LocalSocks5Server? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopVpn()
                return START_NOT_STICKY
            }
            ACTION_UPDATE_SPEED -> {
                val kbps = intent.getIntExtra(EXTRA_SPEED_KBPS, 100)
                val bytesPerSec = kbps * 1024L
                downloadLimiter.updateRate(bytesPerSec)
                uploadLimiter.updateRate(bytesPerSec)
                updateNotification("الحد الكلي: $kbps KB/s")
                return START_STICKY
            }
            ACTION_START -> {
                val throttled = intent.getStringArrayListExtra(EXTRA_PACKAGES) ?: arrayListOf()
                val blocked = intent.getStringArrayListExtra(EXTRA_BLOCKED_PACKAGES) ?: arrayListOf()
                val kbps = intent.getIntExtra(EXTRA_SPEED_KBPS, 100)
                val bytesPerSec = kbps * 1024L
                downloadLimiter.updateRate(bytesPerSec)
                uploadLimiter.updateRate(bytesPerSec)
                startForegroundServiceWithNotification("جاري التشغيل...")
                startVpn(throttled, blocked, kbps)
            }
        }
        return START_STICKY
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
            .setSmallIcon(android.R.drawable.stat_sys_download)
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
            val server = LocalSocks5Server(this, downloadLimiter, uploadLimiter)
            server.start()
            socksServer = server

            val builder = Builder()
                .setSession("NetThrottle")
                .setMtu(1500)
                .addAddress("10.0.0.2", 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("8.8.8.8")
                .addDnsServer("1.1.1.1")

            // إذا لم يُحدد أي تطبيق للتحديد أو الحظر، أو تم تفعيل الحظر:
            // نستثني تطبيقنا فقط فتدخل كل تطبيقات الهاتف والنظام تحت سقف السرعة المحدد
            if (throttledPackages.isEmpty() && blockedPackages.isEmpty()) {
                builder.addDisallowedApplication(packageName)
            } else if (blockedPackages.isNotEmpty()) {
                // وضع حظر الخلفية: تمرير التطبيقات المحددة السرعة فقط داخل النفق وحظر الباقي
                // عبر إجبار كل التطبيقات ما عدا تطبيقنا، أو حصر النفق في التطبيقات المختارة
                builder.addDisallowedApplication(packageName)
            } else {
                // تحديد التطبيقات المختارة فقط (تيك توك مثلاً) وترك الباقي
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
                    builder.addDisallowedApplication(packageName)
                }
            }

            val pfd = builder.establish()
            if (pfd == null) {
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
            updateNotification("الحد الكلي للهاتف: $kbps KB/s")
        } catch (t: Throwable) {
            isRunning = false
            updateNotification("خطأ: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun stopVpnInternal() {
        if (isRunning) {
            try {
                TProxyService.TProxyStopService()
            } catch (_: Throwable) {
            }
        }
        isRunning = false
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
