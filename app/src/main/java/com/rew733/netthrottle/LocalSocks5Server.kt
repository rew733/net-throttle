package com.rew733.netthrottle

import android.net.VpnService
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class LocalSocks5Server(
    private val vpnService: VpnService,
    private val downloadLimiter: TokenBucketLimiter,
    private val uploadLimiter: TokenBucketLimiter,
    private val udpLimiter: TokenBucketLimiter
) {
    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()
    private val activeSockets = ConcurrentHashMap.newKeySet<Socket>()

    @Volatile
    var foregroundOnlyEnabled: Boolean = false

    @Volatile
    var hasActiveForegroundApp: Boolean = false

    val totalDownloadBytes = AtomicLong(0L)
    val totalUploadBytes = AtomicLong(0L)
    val activeConnections = AtomicInteger(0)
    val blockedCount = AtomicInteger(0)
    val errorCount = AtomicInteger(0)

    @Volatile
    var lastError: String = "لا توجد أخطاء"

    @Volatile
    private var running = false

    val port: Int = 10808

    fun start() {
        if (running) return
        running = true
        serverSocket = ServerSocket().apply {
            reuseAddress = true
            receiveBufferSize = 2048
            bind(InetSocketAddress("127.0.0.1", port))
        }

        executor.execute {
            while (running) {
                try {
                    val client = serverSocket?.accept() ?: break
                    client.tcpNoDelay = true
                    client.receiveBufferSize = 2048
                    client.sendBufferSize = 2048
                    client.soTimeout = 25000
                    activeSockets.add(client)
                    executor.execute { handleClient(client) }
                } catch (e: Exception) {
                    if (running) {
                        errorCount.incrementAndGet()
                        lastError = "Accept: ${e.javaClass.simpleName}"
                    } else {
                        break
                    }
                }
            }
        }
    }

    fun refresh() {
        val iterator = activeSockets.iterator()
        while (iterator.hasNext()) {
            val sock = iterator.next()
            try {
                sock.close()
            } catch (_: Exception) {
            }
            iterator.remove()
        }
        activeConnections.set(0)
    }

    fun stop() {
        running = false
        refresh()
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
    }

    private fun handleClient(client: Socket) {
        activeConnections.incrementAndGet()
        try {
            val input = DataInputStream(client.getInputStream())
            val output = DataOutputStream(client.getOutputStream())

            val version = input.readUnsignedByte()
            if (version != 0x05) {
                client.close()
                return
            }
            val nMethods = input.readUnsignedByte()
            val methods = ByteArray(nMethods)
            input.readFully(methods)

            output.write(byteArrayOf(0x05, 0x00))
            output.flush()

            input.readUnsignedByte() // VER
            val cmd = input.readUnsignedByte()
            input.readUnsignedByte() // RSV
            val atyp = input.readUnsignedByte()

            val destHost: String = when (atyp) {
                0x01 -> {
                    val addr = ByteArray(4)
                    input.readFully(addr)
                    InetAddress.getByAddress(addr).hostAddress ?: ""
                }
                0x03 -> {
                    val len = input.readUnsignedByte()
                    val domain = ByteArray(len)
                    input.readFully(domain)
                    String(domain, Charsets.UTF_8)
                }
                0x04 -> {
                    val addr = ByteArray(16)
                    input.readFully(addr)
                    InetAddress.getByAddress(addr).hostAddress ?: ""
                }
                else -> {
                    client.close()
                    return
                }
            }
            val destPort = input.readUnsignedShort()

            if (foregroundOnlyEnabled && !hasActiveForegroundApp) {
                blockedCount.incrementAndGet()
                output.write(byteArrayOf(0x05, 0x02, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                output.flush()
                client.close()
                return
            }

            when (cmd) {
                0x01 -> handleTcpConnect(client, input, output, destHost, destPort)
                0x03 -> handleUdpAssociate(client, input, output)
                else -> {
                    output.write(byteArrayOf(0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                    output.flush()
                    client.close()
                }
            }
        } catch (e: Exception) {
            if (running && e.message?.contains("closed", ignoreCase = true) != true) {
                errorCount.incrementAndGet()
                lastError = "Client: ${e.javaClass.simpleName}"
            }
            try {
                client.close()
            } catch (_: Exception) {
            }
        } finally {
            activeSockets.remove(client)
            val current = activeConnections.decrementAndGet()
            if (current < 0) activeConnections.set(0)
        }
    }

    private fun handleTcpConnect(
        client: Socket,
        clientIn: InputStream,
        clientOut: OutputStream,
        host: String,
        port: Int
    ) {
        if (!running || client.isClosed) return

        val remote = Socket()
        activeSockets.add(remote)
        try {
            downloadLimiter.consume(1500)

            vpnService.protect(remote)
            remote.tcpNoDelay = true
            // نافذة استقبال صغيرة جداً (2048 بايت) تجبر خادم تيك توك/فيسبوك على خفض سرعة الإرسال فيزيائياً
            remote.receiveBufferSize = 2048
            remote.sendBufferSize = 2048
            remote.soTimeout = 20000
            remote.connect(InetSocketAddress(host, port), 10000)

            clientOut.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            clientOut.flush()

            val remoteIn = remote.getInputStream()
            val remoteOut = remote.getOutputStream()

            val upFuture = executor.submit {
                val buf = ByteArray(1024)
                try {
                    while (running && !client.isClosed && !remote.isClosed) {
                        val read = clientIn.read(buf)
                        if (read == -1) break
                        if (read > 0) {
                            uploadLimiter.consume(read)
                            totalUploadBytes.addAndGet(read.toLong())
                            remoteOut.write(buf, 0, read)
                            remoteOut.flush()
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    try {
                        remote.shutdownOutput()
                    } catch (_: Exception) {
                    }
                }
            }

            val buf = ByteArray(1024)
            try {
                while (running && !client.isClosed && !remote.isClosed) {
                    val read = remoteIn.read(buf)
                    if (read == -1) break
                    if (read > 0) {
                        downloadLimiter.consume(read)
                        totalDownloadBytes.addAndGet(read.toLong())
                        clientOut.write(buf, 0, read)
                        clientOut.flush()
                    }
                }
            } catch (_: Exception) {
            } finally {
                upFuture.cancel(true)
            }
        } catch (e: Exception) {
            if (running && e.message?.contains("closed", ignoreCase = true) != true &&
                e.message?.contains("timed out", ignoreCase = true) != true
            ) {
                errorCount.incrementAndGet()
                lastError = "TCP($host:$port): ${e.javaClass.simpleName}"
            }
            try {
                clientOut.write(byteArrayOf(0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                clientOut.flush()
            } catch (_: Exception) {
            }
        } finally {
            activeSockets.remove(remote)
            try {
                remote.close()
            } catch (_: Exception) {
            }
            activeSockets.remove(client)
            try {
                client.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun handleUdpAssociate(
        client: Socket,
        clientIn: InputStream,
        clientOut: OutputStream
    ) {
        val udpRelay = DatagramSocket(0, InetAddress.getByName("127.0.0.1")).apply {
            receiveBufferSize = 2048
            sendBufferSize = 2048
        }
        val relayPort = udpRelay.localPort

        val reply = ByteBuffer.allocate(10).apply {
            put(0x05.toByte())
            put(0x00.toByte())
            put(0x00.toByte())
            put(0x01.toByte())
            put(byteArrayOf(127, 0, 0, 1))
            putShort(relayPort.toShort())
        }.array()

        clientOut.write(reply)
        clientOut.flush()

        executor.execute {
            val buf = ByteArray(1500)
            val packet = DatagramPacket(buf, buf.size)
            var clientAppAddr: InetSocketAddress? = null

            try {
                while (running && !client.isClosed && !udpRelay.isClosed) {
                    udpRelay.receive(packet)
                    val len = packet.length
                    if (len < 10) continue

                    if (foregroundOnlyEnabled && !hasActiveForegroundApp) {
                        blockedCount.incrementAndGet()
                        continue
                    }

                    if (packet.address.isLoopbackAddress && (clientAppAddr == null || packet.port == clientAppAddr.port)) {
                        clientAppAddr = InetSocketAddress(packet.address, packet.port)
                        val atyp = buf[3].toInt() and 0xFF
                        var offset = 4
                        val destAddr: InetAddress = when (atyp) {
                            0x01 -> {
                                val ip = buf.copyOfRange(offset, offset + 4)
                                offset += 4
                                InetAddress.getByAddress(ip)
                            }
                            0x03 -> {
                                val dLen = buf[offset].toInt() and 0xFF
                                offset += 1
                                val host = String(buf, offset, dLen, Charsets.UTF_8)
                                offset += dLen
                                InetAddress.getByName(host)
                            }
                            0x04 -> {
                                val ip = buf.copyOfRange(offset, offset + 16)
                                offset += 16
                                InetAddress.getByAddress(ip)
                            }
                            else -> continue
                        }
                        val destPort = ((buf[offset].toInt() and 0xFF) shl 8) or (buf[offset + 1].toInt() and 0xFF)
                        offset += 2

                        // حظر بروتوكول QUIC (UDP 443) لإجبار تيك توك وفيسبوك ويوتيوب على استخدام TCP المنضبط
                        if (destPort != 53) {
                            blockedCount.incrementAndGet()
                            continue
                        }

                        val payload = buf.copyOfRange(offset, len)
                        val outSock = DatagramSocket().apply {
                            soTimeout = 3000
                        }
                        vpnService.protect(outSock)
                        executor.execute {
                            try {
                                outSock.send(DatagramPacket(payload, payload.size, destAddr, destPort))
                                val rBuf = ByteArray(1500)
                                val rPkt = DatagramPacket(rBuf, rBuf.size)
                                outSock.receive(rPkt)
                                val rData = rPkt.data.copyOf(rPkt.length)

                                val srcIpBytes = destAddr.address
                                val header = ByteBuffer.allocate(6 + srcIpBytes.size + rData.size).apply {
                                    putShort(0)
                                    put(0.toByte())
                                    put(if (srcIpBytes.size == 4) 0x01.toByte() else 0x04.toByte())
                                    put(srcIpBytes)
                                    putShort(destPort.toShort())
                                    put(rData)
                                }.array()

                                clientAppAddr?.let { target ->
                                    udpRelay.send(DatagramPacket(header, header.size, target))
                                }
                            } catch (_: Exception) {
                            } finally {
                                outSock.close()
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                udpRelay.close()
            }
        }

        try {
            while (running && clientIn.read() != -1) {
            }
        } catch (_: Exception) {
        } finally {
            udpRelay.close()
            activeSockets.remove(client)
            try {
                client.close()
            } catch (_: Exception) {
            }
        }
    }
}
