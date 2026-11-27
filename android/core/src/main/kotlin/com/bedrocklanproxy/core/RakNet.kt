package com.bedrocklanproxy.core

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.security.SecureRandom

/**
 * Lo mínimo de RakNet que necesitamos: los paquetes "desconectados" con los
 * que la consola descubre partidas LAN. El resto del tráfico se reenvía sin tocar.
 */
object RakNet {
    const val DEFAULT_PORT = 19132

    const val ID_UNCONNECTED_PING: Byte = 0x01
    const val ID_UNCONNECTED_PING_OPEN_CONNECTIONS: Byte = 0x02
    const val ID_UNCONNECTED_PONG: Byte = 0x1C

    val MAGIC: ByteArray = byteArrayOf(
        0x00, 0xFF.toByte(), 0xFF.toByte(), 0x00, 0xFE.toByte(), 0xFE.toByte(), 0xFE.toByte(), 0xFE.toByte(),
        0xFD.toByte(), 0xFD.toByte(), 0xFD.toByte(), 0xFD.toByte(), 0x12, 0x34, 0x56, 0x78,
    )

    private val random = SecureRandom()

    fun randomGuid(): Long = random.nextLong()

    fun nowMs(): Long = System.nanoTime() / 1_000_000

    // ------------------------------------------------------------ Ping / Pong

    fun buildPing(clientGuid: Long, timestamp: Long = nowMs()): ByteArray =
        ByteBuffer.allocate(33)
            .put(ID_UNCONNECTED_PING)
            .putLong(timestamp)
            .put(MAGIC)
            .putLong(clientGuid)
            .array()

    fun isPing(data: ByteArray, length: Int = data.size): Boolean =
        length >= 25 &&
            (data[0] == ID_UNCONNECTED_PING || data[0] == ID_UNCONNECTED_PING_OPEN_CONNECTIONS) &&
            magicAt(data, 9)

    /** Devuelve (timestamp, guidCliente) o null si no es un ping. */
    fun parsePing(data: ByteArray, length: Int = data.size): Pair<Long, Long>? {
        if (!isPing(data, length)) return null
        val buf = ByteBuffer.wrap(data, 0, length)
        val timestamp = buf.getLong(1)
        val guid = if (length >= 33) buf.getLong(25) else 0L
        return timestamp to guid
    }

    fun buildPong(timestamp: Long, serverGuid: Long, motd: String): ByteArray {
        var raw = motd.toByteArray(Charsets.UTF_8)
        if (raw.size > 0xFFFF) raw = raw.copyOf(0xFFFF)
        return ByteBuffer.allocate(35 + raw.size)
            .put(ID_UNCONNECTED_PONG)
            .putLong(timestamp)
            .putLong(serverGuid)
            .put(MAGIC)
            .putShort(raw.size.toShort())
            .put(raw)
            .array()
    }

    data class Pong(val timestamp: Long, val guid: Long, val motd: String)

    fun parsePong(data: ByteArray, length: Int = data.size): Pong? {
        if (length < 35 || data[0] != ID_UNCONNECTED_PONG || !magicAt(data, 17)) return null
        val buf = ByteBuffer.wrap(data, 0, length)
        val timestamp = buf.getLong(1)
        val guid = buf.getLong(9)
        val strLen = buf.getShort(33).toInt() and 0xFFFF
        val end = minOf(length, 35 + strLen)
        return Pong(timestamp, guid, String(data, 35, end - 35, Charsets.UTF_8))
    }

    private fun magicAt(data: ByteArray, offset: Int): Boolean {
        for (i in MAGIC.indices) if (data[offset + i] != MAGIC[i]) return false
        return true
    }

    // ------------------------------------------------------------ Direcciones

    /** Convierte "host", "host:puerto" o "[ipv6]:puerto" en (host, puerto). */
    fun parseAddress(text: String, defaultPort: Int = DEFAULT_PORT): Pair<String, Int> {
        val t = text.trim()
        require(t.isNotEmpty()) { "La dirección está vacía" }
        val host: String
        val port: Int
        if (t.startsWith("[")) {
            val end = t.indexOf(']')
            require(end != -1) { "Dirección IPv6 mal formada: $t" }
            host = t.substring(1, end)
            val rest = t.substring(end + 1)
            port = if (rest.startsWith(":")) parsePort(rest.substring(1)) else defaultPort
        } else if (t.count { it == ':' } == 1) {
            val (h, p) = t.split(":")
            host = h
            port = if (p.isEmpty()) defaultPort else parsePort(p)
        } else {
            host = t
            port = defaultPort
        }
        require(host.isNotEmpty()) { "La dirección está vacía" }
        require(port in 1..65535) { "Puerto inválido: $port" }
        return host to port
    }

    private fun parsePort(text: String): Int =
        text.toIntOrNull() ?: throw IllegalArgumentException("Puerto inválido: $text")

    /** Resuelve el host prefiriendo IPv4. Hace DNS: no llamar desde el hilo principal de Android. */
    fun resolve(host: String, port: Int): InetSocketAddress {
        val all = InetAddress.getAllByName(host)
        val chosen = all.firstOrNull { it is Inet4Address } ?: all.first()
        return InetSocketAddress(chosen, port)
    }

    data class QueryResult(val info: ServerInfo, val latencyMs: Long)

    /**
     * Intenta iniciar una conexión RakNet (Open Connection Request 1), como hace un jugador al
     * entrar. Un servidor RakNet responde aunque tenga los pings desactivados. Devuelve el
     * tiempo de respuesta en ms, o null si no contesta.
     */
    fun probeConnection(host: String, port: Int = DEFAULT_PORT, timeoutMs: Int = 3000): Long? {
        val addr = resolve(host, port)
        DatagramSocket().use { socket ->
            socket.soTimeout = maxOf(timeoutMs / 3, 200)
            val buf = ByteArray(2048)
            for (mtu in intArrayOf(1400, 1200, 576)) {
                val pkt = ByteArray(mtu - 28)
                pkt[0] = 0x05
                System.arraycopy(MAGIC, 0, pkt, 1, MAGIC.size)
                pkt[17] = 11 // versión del protocolo RakNet
                val start = System.nanoTime()
                socket.send(DatagramPacket(pkt, pkt.size, addr))
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    return (System.nanoTime() - start) / 1_000_000
                } catch (_: SocketTimeoutException) {
                    // probar con un paquete más pequeño
                }
            }
        }
        return null
    }

    /** Hace ping a un servidor Bedrock (bloqueante). Lanza excepción si no responde. */
    fun query(host: String, port: Int = DEFAULT_PORT, timeoutMs: Int = 3000, attempts: Int = 3): QueryResult {
        val addr = resolve(host, port)
        val guid = randomGuid()
        DatagramSocket().use { socket ->
            socket.soTimeout = maxOf(timeoutMs / maxOf(attempts, 1), 100)
            val buf = ByteArray(65535)
            repeat(maxOf(attempts, 1)) {
                val ping = buildPing(guid)
                val start = System.nanoTime()
                socket.send(DatagramPacket(ping, ping.size, addr))
                try {
                    while (true) {
                        val packet = DatagramPacket(buf, buf.size)
                        socket.receive(packet)
                        val pong = parsePong(buf, packet.length) ?: continue
                        val latency = (System.nanoTime() - start) / 1_000_000
                        return QueryResult(ServerInfo.fromMotd(pong.motd, pong.guid), latency)
                    }
                } catch (_: SocketTimeoutException) {
                    // siguiente intento
                }
            }
        }
        throw java.io.IOException("El servidor $host:$port no respondió")
    }
}

/** MOTD de Bedrock: MCPE;Línea1;Protocolo;Versión;Jugadores;Máx;ServerID;Línea2;Modo;ModoNum;PuertoV4;PuertoV6; */
data class ServerInfo(val fields: List<String>, val guid: Long = 0) {
    private fun get(i: Int) = fields.getOrElse(i) { "" }

    val name get() = get(F_MOTD)
    val protocol get() = get(F_PROTOCOL)
    val version get() = get(F_VERSION)
    val players get() = get(F_PLAYERS)
    val maxPlayers get() = get(F_MAX_PLAYERS)
    val levelName get() = get(F_LEVEL_NAME)
    val gamemode get() = get(F_GAMEMODE)

    fun toMotd(): String = fields.joinToString(";") + ";"

    /**
     * MOTD reescrito para anunciarlo como partida LAN: los puertos pasan a ser el
     * puerto local del proxy y el ID del servidor el GUID del proxy.
     */
    fun forLan(localPort: Int, serverGuid: Long, customName: String? = null): String {
        val f = fields.toMutableList()
        while (f.size < DEFAULT_FIELDS.size) f.add(DEFAULT_FIELDS[f.size])
        f[F_SERVER_ID] = java.lang.Long.toUnsignedString(serverGuid)
        f[F_PORT_V4] = localPort.toString()
        f[F_PORT_V6] = localPort.toString()
        if (!customName.isNullOrBlank()) f[F_MOTD] = sanitizeName(customName)
        return f.joinToString(";") + ";"
    }

    companion object {
        const val F_MOTD = 1
        const val F_PROTOCOL = 2
        const val F_VERSION = 3
        const val F_PLAYERS = 4
        const val F_MAX_PLAYERS = 5
        const val F_SERVER_ID = 6
        const val F_LEVEL_NAME = 7
        const val F_GAMEMODE = 8
        const val F_PORT_V4 = 10
        const val F_PORT_V6 = 11

        val DEFAULT_FIELDS = listOf(
            "MCPE", "Servidor", "0", "0.0.0", "0", "0", "0",
            "Bedrock level", "Survival", "1", "19132", "19133",
        )

        fun fromMotd(motd: String, guid: Long = 0): ServerInfo {
            val parts = motd.split(";").toMutableList()
            if (parts.isNotEmpty() && parts.last().isEmpty()) parts.removeAt(parts.size - 1)
            return ServerInfo(parts, guid)
        }

        fun sanitizeName(name: String) = name.replace(';', ',').replace('\n', ' ').trim()
    }
}
