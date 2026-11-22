package com.bedrocklanproxy.core

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.util.Collections

/** Servidor Bedrock de mentira: responde pings y hace eco del resto de paquetes. */
class FakeServer(private val motd: String = MOTD) : AutoCloseable {
    private val socket = DatagramSocket(0, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 200 }
    val port: Int = socket.localPort
    val peers: MutableSet<SocketAddress> = Collections.synchronizedSet(HashSet())
    @Volatile private var stop = false
    private val thread = Thread(::run).apply { isDaemon = true; start() }

    private fun run() {
        val buf = ByteArray(65535)
        while (!stop) {
            val packet = DatagramPacket(buf, buf.size)
            try {
                socket.receive(packet)
            } catch (_: SocketTimeoutException) {
                continue
            } catch (_: Exception) {
                return
            }
            val ping = RakNet.parsePing(buf, packet.length)
            val reply = if (ping != null) {
                RakNet.buildPong(ping.first, 42, motd)
            } else {
                peers.add(packet.socketAddress)
                "ECHO:".toByteArray() + buf.copyOf(packet.length)
            }
            socket.send(DatagramPacket(reply, reply.size, packet.socketAddress))
        }
    }

    override fun close() {
        stop = true
        thread.join(2000)
        socket.close()
    }

    companion object {
        const val MOTD = "MCPE;Servidor de Prueba;712;1.21.40;5;100;1234567890;Lobby;Survival;1;19132;19133;"
    }
}
