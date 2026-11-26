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
        const val EXTRA_SPEED_KBPS = "extra_speed_kbps"

        @Volatile
        var isRunning = false
            private set

        private const val CHANNEL_ID = "net_throttle_vpn"
        private const val NOTIF_ID = 101
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private val downloadLimiter = TokenBucketLimiter(150 * 1024L)
    private val uploadLimiter = TokenBucketLimiter(150 * 1024L)
    private var socksServer: LocalSocks5Server? = null
    private var tunnelThread: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopVpn()
                return START_NOT_STICKY
            }
            ACTION_UPDATE_SPEED -> {
                val kbps = intent.getIntExtra(EXTRA_SPEED_KBPS, 150)
                val bytesPerSec = kbps * 1024L
                downloadLimiter.updateRate(bytesPerSec)
                uploadLimiter.updateRate(bytesPerSec)
                updateNotification("الحد الأقصى الحالي: $kbps KB/s")
                return START_STICKY
            }
            ACTION_START -> {
                val packages = intent.getStringArrayListExtra(EXTRA_PACKAGES) ?: arrayListOf()
                val kbps = intent.getIntExtra(EXTRA_SPEED_KBPS, 150)
                val bytesPerSec = kbps * 1024L
                downloadLimiter.updateRate(bytesPerSec)
                uploadLimiter.updateRate(bytesPerSec)
                startForegroundServiceWithNotification("جاري التشغيل ($kbps KB/s)...")
                startVpn(packages, kbps)
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
            .setContentTitle("NetThrottle VPN")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_media_pause, "إيقاف", stopIntent)
            .setOngoing(true)
            .build()
    }

    private fun startVpn(targetPackages: List<String>, kbps: Int) {
        stopVpnInternal()

        if (TProxyService.loadError != null) {
            updateNotification("خطأ مكتبة: ${TProxyService.loadError}")
            return
        }

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

            var addedCount = 0
            for (pkg in targetPackages) {
                if (pkg == packageName) continue
                try {
                    builder.addAllowedApplication(pkg)
                    addedCount++
                } catch (_: Exception) {
                }
            }

            if (addedCount == 0) {
                updateNotification("لم يتم تحديد أي تطبيق")
                return
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
                misc:
                  task-stack-size: 81920
                tunnel:
                  mtu: 1500
                socks5:
                  port: ${server.port}
                  address: 127.0.0.1
                  udp: 'udp'
                """.trimIndent()
            )

            isRunning = true
            updateNotification("الحد الأقصى الحالي: $kbps KB/s")

            val tunFd = pfd.fd
            val configPath = configFile.absolutePath

            tunnelThread = Thread {
                try {
                    TProxyService.TProxyStartService(configPath, tunFd)
                } catch (t: Throwable) {
                    isRunning = false
                    updateNotification("خطأ تشغيل المحرك: ${t.javaClass.simpleName}: ${t.message}")
                }
            }.apply {
                name = "HevSocks5TunnelThread"
                start()
            }
        } catch (t: Throwable) {
            isRunning = false
            updateNotification("خطأ عام: ${t.javaClass.simpleName}: ${t.message}")
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
        tunnelThread = null
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
