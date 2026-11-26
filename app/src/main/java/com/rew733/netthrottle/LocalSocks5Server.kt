package com.rew733.netthrottle

import android.content.Context
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.system.OsConstants
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
import java.util.concurrent.Executors

class LocalSocks5Server(
    private val vpnService: VpnService,
    private val downloadLimiter: TokenBucketLimiter,
    private val uploadLimiter: TokenBucketLimiter
) {
    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()
    private val connectivityManager =
        vpnService.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    @Volatile
    private var running = false

    @Volatile
    var blockedUids: Set<Int> = emptySet()

    val port: Int = 10808

    fun start() {
        if (running) return
        running = true
        serverSocket = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress("127.0.0.1", port))
        }

        executor.execute {
            while (running) {
                try {
                    val client = serverSocket?.accept() ?: break
                    client.tcpNoDelay = true
                    executor.execute { handleClient(client) }
                } catch (_: Exception) {
                    if (!running) break
                }
            }
        }
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
    }

    private fun isConnectionBlocked(
        protocol: Int,
        srcPort: Int,
        dstHost: String,
        dstPort: Int
    ): Boolean {
        val blocked = blockedUids
        if (blocked.isEmpty()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val localAddr = InetSocketAddress(InetAddress.getByName("10.0.0.2"), srcPort)
                val remoteAddr = InetSocketAddress(InetAddress.getByName(dstHost), dstPort)
                val uid = connectivityManager.getConnectionOwnerUid(protocol, localAddr, remoteAddr)
                if (uid > 0 && blocked.contains(uid)) {
                    return true
                }
            } catch (_: Exception) {
            }
        }
        return false
    }

    private fun handleClient(client: Socket) {
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

            when (cmd) {
                0x01 -> handleTcpConnect(client, input, output, destHost, destPort)
                0x03 -> handleUdpAssociate(client, input, output)
                else -> {
                    output.write(byteArrayOf(0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                    output.flush()
                    client.close()
                }
            }
        } catch (_: Exception) {
            try {
                client.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun handleTcpConnect(
        client: Socket,
        clientIn: InputStream,
        clientOut: OutputStream,
        host: String,
        port: Int
    ) {
        // التحقق مما إذا كان التطبيق محظوراً
        if (isConnectionBlocked(OsConstants.IPPROTO_TCP, client.port, host, port)) {
            try {
                clientOut.write(byteArrayOf(0x05, 0x02, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                clientOut.flush()
                client.close()
            } catch (_: Exception) {
            }
            return
        }

        val remote = Socket()
        try {
            vpnService.protect(remote)
            remote.tcpNoDelay = true
            remote.receiveBufferSize = 2048
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

            val buf = ByteArray(512)
            try {
                while (running && !client.isClosed && !remote.isClosed) {
                    val read = remoteIn.read(buf)
                    if (read == -1) break
                    if (read > 0) {
                        downloadLimiter.consume(read)
                        clientOut.write(buf, 0, read)
                        clientOut.flush()
                    }
                }
            } catch (_: Exception) {
            } finally {
                upFuture.cancel(true)
            }
        } catch (_: Exception) {
            try {
                clientOut.write(byteArrayOf(0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                clientOut.flush()
            } catch (_: Exception) {
            }
        } finally {
            try {
                remote.close()
            } catch (_: Exception) {
            }
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
        val udpRelay = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
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
            val buf = ByteArray(4096)
            val packet = DatagramPacket(buf, buf.size)
            var clientAppAddr: InetSocketAddress? = null

            try {
                while (running && !client.isClosed && !udpRelay.isClosed) {
                    udpRelay.receive(packet)
                    val len = packet.length
                    if (len < 10) continue

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
                            else -> continue
                        }
                        val destPort = ((buf[offset].toInt() and 0xFF) shl 8) or (buf[offset + 1].toInt() and 0xFF)
                        offset += 2

                        // حظر QUIC (UDP 443) لتحويل الفيديو إلى TCP المنضبط
                        if (destPort == 443 || destPort == 80) continue

                        // حظر استعلامات UDP/DNS للتطبيقات المحظورة
                        val destHostStr = destAddr.hostAddress ?: ""
                        if (destHostStr.isNotEmpty() && isConnectionBlocked(
                                OsConstants.IPPROTO_UDP,
                                packet.port,
                                destHostStr,
                                destPort
                            )
                        ) {
                            continue
                        }

                        val payload = buf.copyOfRange(offset, len)
                        if (destPort != 53) {
                            uploadLimiter.consume(payload.size)
                        }

                        val outSock = DatagramSocket()
                        vpnService.protect(outSock)
                        outSock.soTimeout = 5000
                        executor.execute {
                            try {
                                outSock.send(DatagramPacket(payload, payload.size, destAddr, destPort))
                                val rBuf = ByteArray(4096)
                                val rPkt = DatagramPacket(rBuf, rBuf.size)
                                outSock.receive(rPkt)
                                val rData = rPkt.data.copyOf(rPkt.length)
                                if (destPort != 53) {
                                    downloadLimiter.consume(rData.size)
                                }

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
            try {
                client.close()
            } catch (_: Exception) {
            }
        }
    }
}
