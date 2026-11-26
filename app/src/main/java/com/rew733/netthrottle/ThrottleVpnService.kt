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
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

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
    private val executor = Executors.newCachedThreadPool()
    private val downloadLimiter = TokenBucketLimiter(150 * 1024L)
    private val uploadLimiter = TokenBucketLimiter(150 * 1024L)
    private val tcpSessions = ConcurrentHashMap<String, TcpSession>()
    private val udpSockets = ConcurrentHashMap<String, DatagramSocket>()

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
                updateNotification(kbps)
                return START_STICKY
            }
            ACTION_START -> {
                val packages = intent.getStringArrayListExtra(EXTRA_PACKAGES) ?: arrayListOf()
                val kbps = intent.getIntExtra(EXTRA_SPEED_KBPS, 150)
                val bytesPerSec = kbps * 1024L
                downloadLimiter.updateRate(bytesPerSec)
                uploadLimiter.updateRate(bytesPerSec)
                startForegroundServiceWithNotification(kbps)
                startVpn(packages)
            }
        }
        return START_STICKY
    }

    private fun startForegroundServiceWithNotification(kbps: Int) {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "NetThrottle VPN",
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)

        val notification = buildNotification(kbps)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun updateNotification(kbps: Int) {
        if (!isRunning) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIF_ID, buildNotification(kbps))
    }

    private fun buildNotification(kbps: Int): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, ThrottleVpnService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("تحديد السرعة نشط")
            .setContentText("الحد الأقصى الحالي: $kbps KB/s")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_media_pause, "إيقاف", stopIntent)
            .setOngoing(true)
            .build()
    }

    private fun startVpn(targetPackages: List<String>) {
        stopVpnInternal()

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
            stopVpn()
            return
        }

        vpnInterface = builder.establish() ?: run {
            stopVpn()
            return
        }

        isRunning = true
        val fd = vpnInterface!!.fileDescriptor
        val inputStream = FileInputStream(fd)
        val outputStream = FileOutputStream(fd)

        executor.execute {
            val packet = ByteArray(32767)
            while (isRunning) {
                try {
                    val length = inputStream.read(packet)
                    if (length > 0) {
                        val copy = packet.copyOf(length)
                        handleOutgoingIpPacket(copy, outputStream)
                    }
                } catch (_: Exception) {
                    if (!isRunning) break
                }
            }
        }
    }

    private fun handleOutgoingIpPacket(raw: ByteArray, tunOut: FileOutputStream) {
        if (raw.size < 20) return
        val version = (raw[0].toInt() shr 4) and 0x0F
        if (version != 4) return

        val ihl = (raw[0].toInt() and 0x0F) * 4
        if (raw.size < ihl) return

        val protocol = raw[9].toInt() and 0xFF
        val srcIp = ByteArray(4) { raw[12 + it] }
        val dstIp = ByteArray(4) { raw[16 + it] }

        when (protocol) {
            6 -> handleTcpPacket(raw, ihl, srcIp, dstIp, tunOut)
            17 -> handleUdpPacket(raw, ihl, srcIp, dstIp, tunOut)
        }
    }

    private fun handleUdpPacket(
        raw: ByteArray,
        ihl: Int,
        srcIp: ByteArray,
        dstIp: ByteArray,
        tunOut: FileOutputStream
    ) {
        if (raw.size < ihl + 8) return
        val srcPort = ((raw[ihl].toInt() and 0xFF) shl 8) or (raw[ihl + 1].toInt() and 0xFF)
        val dstPort = ((raw[ihl + 2].toInt() and 0xFF) shl 8) or (raw[ihl + 3].toInt() and 0xFF)
        val udpLen = ((raw[ihl + 4].toInt() and 0xFF) shl 8) or (raw[ihl + 5].toInt() and 0xFF)
        val payloadOffset = ihl + 8
        val payloadLen = (udpLen - 8).coerceAtMost(raw.size - payloadOffset)
        if (payloadLen <= 0) return

        val payload = raw.copyOfRange(payloadOffset, payloadOffset + payloadLen)
        val key = "${InetAddress.getByAddress(srcIp).hostAddress}:$srcPort->${InetAddress.getByAddress(dstIp).hostAddress}:$dstPort"

        executor.execute {
            try {
                uploadLimiter.consume(payload.size)
                var socket = udpSockets[key]
                if (socket == null || socket.isClosed) {
                    socket = DatagramSocket()
                    protect(socket)
                    socket.soTimeout = 15000
                    udpSockets[key] = socket

                    val activeSocket = socket
                    executor.execute {
                        val recvBuf = ByteArray(32767)
                        val dp = DatagramPacket(recvBuf, recvBuf.size)
                        while (isRunning && !activeSocket.isClosed) {
                            try {
                                activeSocket.receive(dp)
                                val data = dp.data.copyOf(dp.length)
                                downloadLimiter.consume(data.size)
                                val respPacket = buildIpv4UdpPacket(dstIp, srcIp, dstPort, srcPort, data)
                                synchronized(tunOut) {
                                    tunOut.write(respPacket)
                                }
                            } catch (_: Exception) {
                                activeSocket.close()
                                udpSockets.remove(key)
                                break
                            }
                        }
                    }
                }

                val destAddr = InetAddress.getByAddress(dstIp)
                socket.send(DatagramPacket(payload, payload.size, destAddr, dstPort))
            } catch (_: Exception) {
                udpSockets.remove(key)?.close()
            }
        }
    }

    private fun handleTcpPacket(
        raw: ByteArray,
        ihl: Int,
        srcIp: ByteArray,
        dstIp: ByteArray,
        tunOut: FileOutputStream
    ) {
        if (raw.size < ihl + 20) return
        val srcPort = ((raw[ihl].toInt() and 0xFF) shl 8) or (raw[ihl + 1].toInt() and 0xFF)
        val dstPort = ((raw[ihl + 2].toInt() and 0xFF) shl 8) or (raw[ihl + 3].toInt() and 0xFF)
        val seqNum = ByteBuffer.wrap(raw, ihl + 4, 4).int.toLong() and 0xFFFFFFFFL
        val dataOffset = ((raw[ihl + 12].toInt() shr 4) and 0x0F) * 4
        val flags = raw[ihl + 13].toInt() and 0xFF

        val isSyn = (flags and 0x02) != 0
        val isAck = (flags and 0x10) != 0
        val isFin = (flags and 0x01) != 0
        val isRst = (flags and 0x04) != 0

        val payloadOffset = ihl + dataOffset
        val payloadLen = (raw.size - payloadOffset).coerceAtLeast(0)
        val payload = if (payloadLen > 0) raw.copyOfRange(payloadOffset, raw.size) else ByteArray(0)

        val key = "${InetAddress.getByAddress(srcIp).hostAddress}:$srcPort->${InetAddress.getByAddress(dstIp).hostAddress}:$dstPort"

        if (isRst) {
            tcpSessions.remove(key)?.close()
            return
        }

        if (isSyn && !isAck) {
            tcpSessions.remove(key)?.close()
            val session = TcpSession(key, srcIp, dstIp, srcPort, dstPort, seqNum + 1, tunOut)
            tcpSessions[key] = session
            executor.execute { session.connect() }
            return
        }

        val session = tcpSessions[key] ?: return

        if (payload.isNotEmpty()) {
            executor.execute {
                session.sendToRemote(payload, seqNum)
            }
        }

        if (isFin) {
            session.handleClientFin(seqNum + payload.size)
            tcpSessions.remove(key)
        }
    }

    private inner class TcpSession(
        val key: String,
        val clientIp: ByteArray,
        val serverIp: ByteArray,
        val clientPort: Int,
        val serverPort: Int,
        @Volatile var clientNextSeq: Long,
        val tunOut: FileOutputStream
    ) {
        private var socket: Socket? = null
        @Volatile
        private var serverSeq: Long = (System.nanoTime() and 0x7FFFFFFFL)
        @Volatile
        private var closed = false

        fun connect() {
            try {
                val s = Socket()
                protect(s)
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(InetAddress.getByAddress(serverIp), serverPort), 10000)
                socket = s

                // Send SYN-ACK to app
                val synAck = buildIpv4TcpPacket(
                    serverIp, clientIp, serverPort, clientPort,
                    serverSeq, clientNextSeq, 0x12, ByteArray(0)
                )
                serverSeq += 1
                writeToTun(synAck)

                // Read downstream data from remote server
                val inStream = s.getInputStream()
                val buf = ByteArray(1400)
                while (isRunning && !closed && !s.isClosed) {
                    val read = inStream.read(buf)
                    if (read == -1) {
                        val finPkt = buildIpv4TcpPacket(
                            serverIp, clientIp, serverPort, clientPort,
                            serverSeq, clientNextSeq, 0x11, ByteArray(0)
                        )
                        serverSeq += 1
                        writeToTun(finPkt)
                        break
                    }
                    if (read > 0) {
                        val chunk = buf.copyOf(read)
                        downloadLimiter.consume(chunk.size)
                        val dataPkt = buildIpv4TcpPacket(
                            serverIp, clientIp, serverPort, clientPort,
                            serverSeq, clientNextSeq, 0x18, chunk
                        )
                        serverSeq += chunk.size
                        writeToTun(dataPkt)
                    }
                }
            } catch (_: Exception) {
                val rstPkt = buildIpv4TcpPacket(
                    serverIp, clientIp, serverPort, clientPort,
                    serverSeq, clientNextSeq, 0x14, ByteArray(0)
                )
                writeToTun(rstPkt)
            } finally {
                close()
                tcpSessions.remove(key)
            }
        }

        @Synchronized
        fun sendToRemote(data: ByteArray, seq: Long) {
            if (closed) return
            if (seq < clientNextSeq) {
                // Duplicate retransmission, re-ACK
                val ackPkt = buildIpv4TcpPacket(
                    serverIp, clientIp, serverPort, clientPort,
                    serverSeq, clientNextSeq, 0x10, ByteArray(0)
                )
                writeToTun(ackPkt)
                return
            }
            try {
                uploadLimiter.consume(data.size)
                socket?.getOutputStream()?.write(data)
                socket?.getOutputStream()?.flush()
                clientNextSeq = seq + data.size

                val ackPkt = buildIpv4TcpPacket(
                    serverIp, clientIp, serverPort, clientPort,
                    serverSeq, clientNextSeq, 0x10, ByteArray(0)
                )
                writeToTun(ackPkt)
            } catch (_: Exception) {
                close()
            }
        }

        fun handleClientFin(finSeq: Long) {
            clientNextSeq = finSeq + 1
            val ackPkt = buildIpv4TcpPacket(
                serverIp, clientIp, serverPort, clientPort,
                serverSeq, clientNextSeq, 0x11, ByteArray(0)
            )
            writeToTun(ackPkt)
            close()
        }

        private fun writeToTun(pkt: ByteArray) {
            try {
                synchronized(tunOut) {
                    tunOut.write(pkt)
                }
            } catch (_: Exception) {
            }
        }

        fun close() {
            if (closed) return
            closed = true
            try {
                socket?.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun buildIpv4UdpPacket(
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray
    ): ByteArray {
        val udpLen = 8 + payload.size
        val totalLen = 20 + udpLen
        val buf = ByteBuffer.allocate(totalLen)

        buf.put(0x45.toByte())
        buf.put(0x00.toByte())
        buf.putShort(totalLen.toShort())
        buf.putShort(0)
        buf.putShort(0x4000.toShort())
        buf.put(64.toByte())
        buf.put(17.toByte())
        buf.putShort(0)
        buf.put(srcIp)
        buf.put(dstIp)

        val ipChecksum = computeChecksum(buf.array(), 0, 20)
        buf.putShort(10, ipChecksum)

        buf.putShort(srcPort.toShort())
        buf.putShort(dstPort.toShort())
        buf.putShort(udpLen.toShort())
        buf.putShort(0) // Optional UDP checksum in IPv4
        buf.put(payload)

        return buf.array()
    }

    private fun buildIpv4TcpPacket(
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        seqNum: Long,
        ackNum: Long,
        flags: Int,
        payload: ByteArray
    ): ByteArray {
        val tcpLen = 20 + payload.size
        val totalLen = 20 + tcpLen
        val buf = ByteBuffer.allocate(totalLen)

        // IPv4 Header
        buf.put(0x45.toByte())
        buf.put(0x00.toByte())
        buf.putShort(totalLen.toShort())
        buf.putShort(0)
        buf.putShort(0x4000.toShort())
        buf.put(64.toByte())
        buf.put(6.toByte())
        buf.putShort(0)
        buf.put(srcIp)
        buf.put(dstIp)

        val ipChecksum = computeChecksum(buf.array(), 0, 20)
        buf.putShort(10, ipChecksum)

        // TCP Header
        buf.putShort(srcPort.toShort())
        buf.putShort(dstPort.toShort())
        buf.putInt(seqNum.toInt())
        buf.putInt(ackNum.toInt())
        buf.put(0x50.toByte()) // Data offset 5 (20 bytes)
        buf.put(flags.toByte())
        buf.putShort(65535.toShort()) // Window size
        buf.putShort(0) // Checksum placeholder
        buf.putShort(0) // Urgent pointer
        buf.put(payload)

        val tcpChecksum = computeTcpChecksum(srcIp, dstIp, buf.array(), 20, tcpLen)
        buf.putShort(36, tcpChecksum)

        return buf.array()
    }

    private fun computeTcpChecksum(
        srcIp: ByteArray,
        dstIp: ByteArray,
        packet: ByteArray,
        tcpOffset: Int,
        tcpLen: Int
    ): Short {
        val pseudo = ByteBuffer.allocate(12 + tcpLen)
        pseudo.put(srcIp)
        pseudo.put(dstIp)
        pseudo.put(0.toByte())
        pseudo.put(6.toByte())
        pseudo.putShort(tcpLen.toShort())
        pseudo.put(packet, tcpOffset, tcpLen)
        return computeChecksum(pseudo.array(), 0, pseudo.capacity())
    }

    private fun computeChecksum(data: ByteArray, offset: Int, length: Int): Short {
        var sum = 0L
        var i = offset
        while (i < offset + length - 1) {
            val word = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }
        if (i < offset + length) {
            sum += (data[i].toInt() and 0xFF) shl 8
        }
        while ((sum shr 16) > 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return (sum.inv() and 0xFFFF).toShort()
    }

    private fun stopVpnInternal() {
        isRunning = false
        tcpSessions.values.forEach { it.close() }
        tcpSessions.clear()
        udpSockets.values.forEach { try { it.close() } catch (_: Exception) {} }
        udpSockets.clear()
        try {
            vpnInterface?.close()
        } catch (_: Exception) {
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
