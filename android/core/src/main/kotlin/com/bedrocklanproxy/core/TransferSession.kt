package com.bedrocklanproxy.core

import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Modo transferencia: el teléfono se comporta como un servidor Bedrock mínimo.
 *
 * Acepta la conexión RakNet de la consola, negocia la compresión, recibe el
 * login y responde con un paquete *Transfer* que manda a la consola al
 * servidor real. A partir de ahí la consola se conecta directamente al
 * servidor y el teléfono deja de intervenir (se puede cerrar la app).
 */
internal class TransferSession(
    val client: InetSocketAddress,
    private val serverGuid: Long,
    private val targetHost: String,
    private val targetPort: Int,
    private val send: (ByteArray) -> Unit,
    private val log: (ProxyEvent, String) -> Unit,
) {
    enum class Outcome { PENDING, TRANSFERRED, IGNORED, CLOSED }

    @Volatile var lastSeen: Long = System.nanoTime()
        private set
    var outcome = Outcome.PENDING
        private set
    var transferSentAt = 0L
        private set
    var clientProtocol = 0
        private set

    private var mtu = 1400
    private var compressionReady = false
    private var sendSeq = 0
    private var reliableIndex = 0
    private var orderIndex = 0
    private val pendingAcks = ArrayList<Int>()
    private val unacked = LinkedHashMap<Int, Pair<ByteArray, Long>>()
    private val seenReliable = LinkedHashSet<Int>()
    private val splits = HashMap<Int, Array<ByteArray?>>()

    // ------------------------------------------------------------ entrada

    fun handle(data: ByteArray, length: Int) {
        if (length < 1) return
        lastSeen = System.nanoTime()
        val id = data[0].toInt() and 0xFF
        try {
            when {
                id == ID_OPEN_CONNECTION_REQUEST_1 -> openConnection1(length)
                id == ID_OPEN_CONNECTION_REQUEST_2 -> openConnection2(ByteReader(data, 0, length))
                id and 0x80 != 0 && id and 0x40 != 0 -> handleAck(ByteReader(data, 1, length))
                id and 0x80 != 0 && id and 0x20 != 0 -> handleNack(ByteReader(data, 1, length))
                id and 0x80 != 0 -> handleDatagram(ByteReader(data, 1, length))
            }
        } catch (e: Exception) {
            log(ProxyEvent.ERROR, "Paquete inesperado de la consola (${"%02x".format(id)}): $e")
        }
        flushAcks()
    }

    /** Llamar periódicamente: confirma lo recibido y reenvía lo que la consola no confirmó. */
    fun tick(now: Long) {
        flushAcks()
        val resend = unacked.filter { now - it.value.second > RESEND_AFTER_NS }.keys.toList()
        for (seq in resend) resendDatagram(seq, now)
        // Si tras enviar la transferencia la consola sigue conectada, es que no la aceptó.
        // Si se fue sin despedirse (dejó de enviarnos paquetes), la transferencia funcionó.
        if (outcome == Outcome.PENDING && transferSentAt != 0L && now - transferSentAt > IGNORED_AFTER_NS) {
            if (lastSeen - transferSentAt > STILL_TALKING_NS) {
                outcome = Outcome.IGNORED
                sendFrame(byteArrayOf(ID_DISCONNECT_NOTIFICATION.toByte()))
            } else {
                outcome = Outcome.TRANSFERRED
            }
        }
    }

    // ------------------------------------------------------------ RakNet: conexión

    private fun openConnection1(length: Int) {
        // La consola rellena el paquete hasta el MTU que quiere probar (+28 de cabeceras IP/UDP).
        mtu = minOf(length + 28, MAX_MTU)
        val w = ByteWriter()
        w.byte(ID_OPEN_CONNECTION_REPLY_1)
        w.bytes(RakNet.MAGIC)
        w.longBE(serverGuid)
        w.byte(0) // sin seguridad
        w.shortBE(mtu)
        send(w.toByteArray())
    }

    private fun openConnection2(r: ByteReader) {
        r.skip(1 + 16)
        r.address() // dirección del servidor según la consola
        mtu = minOf(r.shortBE(), MAX_MTU).coerceAtLeast(576)
        val w = ByteWriter()
        w.byte(ID_OPEN_CONNECTION_REPLY_2)
        w.bytes(RakNet.MAGIC)
        w.longBE(serverGuid)
        w.address(client)
        w.shortBE(mtu)
        w.byte(0) // sin cifrado RakNet
        send(w.toByteArray())
    }

    // ------------------------------------------------------------ RakNet: datagramas

    private fun handleDatagram(r: ByteReader) {
        val seq = r.triadLE()
        pendingAcks += seq
        while (r.remaining > 0) {
            val flags = r.ubyte()
            val reliability = flags ushr 5
            val split = flags and 0x10 != 0
            val len = (r.ushortBE() + 7) / 8
            var relIndex = -1
            if (reliability in RELIABLE) relIndex = r.triadLE()
            if (reliability == 1 || reliability == 4) r.triadLE() // índice de secuencia
            if (reliability in ORDERED_OR_SEQUENCED) { r.triadLE(); r.ubyte() } // índice de orden y canal
            var splitCount = 0
            var splitId = 0
            var splitIndex = 0
            if (split) {
                splitCount = r.intBE(); splitId = r.ushortBE(); splitIndex = r.intBE()
            }
            val payload = r.bytes(len)
            if (relIndex >= 0) {
                if (!seenReliable.add(relIndex)) continue // duplicado
                if (seenReliable.size > 4096) seenReliable.remove(seenReliable.first())
            }
            if (split) {
                if (splitCount !in 1..4096 || splitIndex !in 0 until splitCount) continue
                val parts = splits.getOrPut(splitId) { arrayOfNulls(splitCount) }
                if (splitIndex < parts.size) parts[splitIndex] = payload
                if (parts.all { it != null }) {
                    splits.remove(splitId)
                    val out = ByteArrayOutputStream()
                    parts.forEach { out.write(it!!) }
                    onMessage(out.toByteArray())
                }
            } else {
                onMessage(payload)
            }
        }
    }

    private fun onMessage(msg: ByteArray) {
        if (msg.isEmpty()) return
        when (msg[0].toInt() and 0xFF) {
            ID_CONNECTION_REQUEST -> {
                val r = ByteReader(msg, 1, msg.size)
                r.longBE() // guid de la consola
                val time = r.longBE()
                val w = ByteWriter()
                w.byte(ID_CONNECTION_REQUEST_ACCEPTED)
                w.address(client)
                w.shortBE(0)
                repeat(20) { w.address(InetSocketAddress("0.0.0.0", 0)) }
                w.longBE(time)
                w.longBE(System.currentTimeMillis())
                sendFrame(w.toByteArray())
            }
            ID_CONNECTED_PING -> {
                val time = ByteReader(msg, 1, msg.size).longBE()
                val w = ByteWriter()
                w.byte(ID_CONNECTED_PONG)
                w.longBE(time)
                w.longBE(System.currentTimeMillis())
                sendFrame(w.toByteArray())
            }
            ID_DISCONNECT_NOTIFICATION -> {
                if (outcome == Outcome.PENDING) {
                    outcome = if (transferSentAt != 0L) Outcome.TRANSFERRED else Outcome.CLOSED
                }
            }
            ID_GAME_PACKET -> handleGame(msg)
            else -> Unit // NewIncomingConnection y otros: no requieren respuesta
        }
    }

    private fun handleAck(r: ByteReader) {
        forEachRecord(r) { unacked.remove(it) }
    }

    private fun handleNack(r: ByteReader) {
        val now = System.nanoTime()
        forEachRecord(r) { if (unacked.containsKey(it)) resendDatagram(it, now) }
    }

    private inline fun forEachRecord(r: ByteReader, action: (Int) -> Unit) {
        val count = r.ushortBE()
        repeat(count) {
            val single = r.ubyte() == 1
            val start = r.triadLE()
            val end = if (single) start else r.triadLE()
            if (end - start in 0..4096) for (s in start..end) action(s)
        }
    }

    private fun flushAcks() {
        if (pendingAcks.isEmpty()) return
        val w = ByteWriter()
        w.byte(ID_ACK)
        w.shortBE(pendingAcks.size)
        for (seq in pendingAcks) { w.byte(1); w.triadLE(seq) }
        pendingAcks.clear()
        send(w.toByteArray())
    }

    /** Envía un mensaje fiable y ordenado en su propio datagrama (nuestros mensajes son pequeños). */
    private fun sendFrame(payload: ByteArray) {
        val w = ByteWriter()
        w.byte(0x84)
        w.triadLE(0) // número de secuencia: se rellena al enviar
        w.byte(RELIABLE_ORDERED shl 5)
        w.shortBE(payload.size * 8)
        w.triadLE(reliableIndex++)
        w.triadLE(orderIndex++)
        w.byte(0)
        w.bytes(payload)
        transmit(w.toByteArray(), System.nanoTime())
    }

    private fun transmit(datagram: ByteArray, now: Long) {
        val seq = sendSeq++ and 0xFFFFFF
        datagram[1] = seq.toByte()
        datagram[2] = (seq shr 8).toByte()
        datagram[3] = (seq shr 16).toByte()
        unacked[seq] = datagram to now
        while (unacked.size > 512) unacked.remove(unacked.keys.first())
        send(datagram)
    }

    private fun resendDatagram(seq: Int, now: Long) {
        val (datagram, _) = unacked.remove(seq) ?: return
        transmit(datagram, now)
    }

    // ------------------------------------------------------------ Bedrock

    private fun handleGame(msg: ByteArray) {
        val body = msg.copyOfRange(1, msg.size)
        val batch = when {
            !compressionReady -> body
            clientProtocol >= PROTOCOL_COMPRESSOR_IN_HEADER -> when (body[0].toInt() and 0xFF) {
                0x00 -> inflate(body, 1)
                0xFF -> body.copyOfRange(1, body.size)
                else -> throw IllegalStateException("compresión no soportada: ${body[0]}")
            }
            else -> inflate(body, 0)
        }
        val r = ByteReader(batch, 0, batch.size)
        while (r.remaining > 0) {
            val len = r.varUInt()
            val packet = ByteReader(r.bytes(len))
            when (packet.varUInt() and 0x3FF) {
                PACKET_REQUEST_NETWORK_SETTINGS -> {
                    clientProtocol = packet.intBE()
                    val ns = ByteWriter()
                    ns.varUInt(PACKET_NETWORK_SETTINGS)
                    ns.shortLE(COMPRESSION_THRESHOLD)
                    ns.shortLE(0) // deflate
                    ns.byte(0) // sin limitación de jugadores
                    ns.byte(0)
                    ns.floatLE(0f)
                    sendGame(ns.toByteArray())
                    compressionReady = true
                }
                PACKET_LOGIN -> {
                    if (clientProtocol == 0) clientProtocol = packet.intBE()
                    sendTransfer()
                }
            }
        }
    }

    private fun sendTransfer() {
        val status = ByteWriter()
        status.varUInt(PACKET_PLAY_STATUS)
        status.intBE(0) // login_success
        sendGame(status.toByteArray(), transferPacket(clientProtocol, targetHost, targetPort))
        transferSentAt = System.nanoTime()
        log(ProxyEvent.INFO, "Enviando la consola ${client.address.hostAddress} a $targetHost:$targetPort…")
    }

    private fun sendGame(vararg packets: ByteArray) {
        val batch = ByteWriter()
        for (p in packets) { batch.varUInt(p.size); batch.bytes(p) }
        val raw = batch.toByteArray()
        val w = ByteWriter()
        w.byte(ID_GAME_PACKET)
        when {
            !compressionReady -> w.bytes(raw)
            clientProtocol >= PROTOCOL_COMPRESSOR_IN_HEADER -> { w.byte(0xFF); w.bytes(raw) } // sin comprimir
            else -> w.bytes(deflate(raw))
        }
        sendFrame(w.toByteArray())
    }

    companion object {
        const val ID_CONNECTED_PING = 0x00
        const val ID_CONNECTED_PONG = 0x03
        const val ID_OPEN_CONNECTION_REQUEST_1 = 0x05
        const val ID_OPEN_CONNECTION_REPLY_1 = 0x06
        const val ID_OPEN_CONNECTION_REQUEST_2 = 0x07
        const val ID_OPEN_CONNECTION_REPLY_2 = 0x08
        const val ID_CONNECTION_REQUEST = 0x09
        const val ID_CONNECTION_REQUEST_ACCEPTED = 0x10
        const val ID_DISCONNECT_NOTIFICATION = 0x15
        const val ID_GAME_PACKET = 0xFE
        const val ID_ACK = 0xC0

        const val PACKET_LOGIN = 0x01
        const val PACKET_PLAY_STATUS = 0x02
        const val PACKET_TRANSFER = 0x55
        const val PACKET_NETWORK_SETTINGS = 0x8F
        const val PACKET_REQUEST_NETWORK_SETTINGS = 0xC1

        /** Desde 1.20.60 cada lote comprimido lleva un byte con el algoritmo. */
        const val PROTOCOL_COMPRESSOR_IN_HEADER = 649
        /** 1.21.30 añadió "reload_world" al paquete Transfer. */
        const val PROTOCOL_TRANSFER_RELOAD_WORLD = 729
        /** 1.26.40 añadió un campo opcional de "gatherings" al paquete Transfer. */
        const val PROTOCOL_TRANSFER_GATHERINGS = 2168

        private const val COMPRESSION_THRESHOLD = 512
        private const val MAX_MTU = 1492
        private const val RELIABLE_ORDERED = 3
        private val RELIABLE = setOf(2, 3, 4, 6, 7)
        private val ORDERED_OR_SEQUENCED = setOf(1, 3, 4, 7)
        private const val RESEND_AFTER_NS = 1_000_000_000L
        private const val IGNORED_AFTER_NS = 8_000_000_000L
        private const val STILL_TALKING_NS = 3_000_000_000L

        /** Paquete Transfer de Bedrock según la versión de protocolo de la consola. */
        fun transferPacket(protocol: Int, host: String, port: Int): ByteArray {
            val w = ByteWriter()
            w.varUInt(PACKET_TRANSFER)
            w.string(host)
            w.shortLE(port)
            if (protocol >= PROTOCOL_TRANSFER_RELOAD_WORLD) w.byte(0) // reload_world = false
            if (protocol >= PROTOCOL_TRANSFER_GATHERINGS) w.byte(0) // sin gatherings_configuration
            return w.toByteArray()
        }

        private fun inflate(data: ByteArray, offset: Int): ByteArray {
            val inflater = Inflater(true)
            try {
                inflater.setInput(data, offset, data.size - offset)
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                while (!inflater.finished()) {
                    val n = inflater.inflate(buf)
                    if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                    out.write(buf, 0, n)
                    if (out.size() > 8 * 1024 * 1024) throw IllegalStateException("lote demasiado grande")
                }
                return out.toByteArray()
            } finally {
                inflater.end()
            }
        }

        private fun deflate(data: ByteArray): ByteArray {
            val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
            try {
                deflater.setInput(data)
                deflater.finish()
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf))
                return out.toByteArray()
            } finally {
                deflater.end()
            }
        }
    }
}

// ---------------------------------------------------------------- utilidades binarias

internal class ByteWriter {
    private val out = ByteArrayOutputStream()
    fun byte(v: Int) = out.write(v and 0xFF)
    fun bytes(b: ByteArray) = out.write(b)
    fun shortBE(v: Int) { byte(v shr 8); byte(v) }
    fun shortLE(v: Int) { byte(v); byte(v shr 8) }
    fun intBE(v: Int) { byte(v shr 24); byte(v shr 16); byte(v shr 8); byte(v) }
    fun longBE(v: Long) { for (i in 7 downTo 0) byte((v shr (i * 8)).toInt()) }
    fun triadLE(v: Int) { byte(v); byte(v shr 8); byte(v shr 16) }
    fun floatLE(f: Float) { val b = java.lang.Float.floatToIntBits(f); byte(b); byte(b shr 8); byte(b shr 16); byte(b shr 24) }
    fun varUInt(value: Int) {
        var v = value
        while (v and 0x7F.inv() != 0) { byte((v and 0x7F) or 0x80); v = v ushr 7 }
        byte(v)
    }
    fun string(s: String) { val b = s.toByteArray(Charsets.UTF_8); varUInt(b.size); bytes(b) }

    /** Dirección en formato RakNet. */
    fun address(a: InetSocketAddress) {
        val ip = a.address
        if (ip is Inet4Address) {
            byte(4)
            for (b in ip.address) byte(b.toInt().inv())
            shortBE(a.port)
        } else {
            byte(6)
            shortLE(23)
            shortBE(a.port)
            intBE(0)
            bytes(ip?.address ?: ByteArray(16))
            intBE(0)
        }
    }

    fun toByteArray(): ByteArray = out.toByteArray()
}

internal class ByteReader(private val b: ByteArray, private var pos: Int = 0, private val end: Int = b.size) {
    val remaining get() = end - pos
    private fun need(n: Int) { if (pos + n > end) throw IndexOutOfBoundsException("faltan datos") }
    fun skip(n: Int) { need(n); pos += n }
    fun ubyte(): Int { need(1); return b[pos++].toInt() and 0xFF }
    fun ushortBE(): Int = (ubyte() shl 8) or ubyte()
    fun shortBE(): Int = ushortBE()
    fun intBE(): Int = (ubyte() shl 24) or (ubyte() shl 16) or (ubyte() shl 8) or ubyte()
    fun longBE(): Long { var v = 0L; repeat(8) { v = (v shl 8) or ubyte().toLong() }; return v }
    fun triadLE(): Int = ubyte() or (ubyte() shl 8) or (ubyte() shl 16)
    fun bytes(n: Int): ByteArray { need(n); return b.copyOfRange(pos, pos + n).also { pos += n } }
    fun varUInt(): Int {
        var result = 0
        var shift = 0
        while (shift < 35) {
            val v = ubyte()
            result = result or ((v and 0x7F) shl shift)
            if (v and 0x80 == 0) return result
            shift += 7
        }
        throw IllegalStateException("varint demasiado largo")
    }
    fun address() {
        when (ubyte()) {
            4 -> skip(4 + 2)
            6 -> skip(2 + 2 + 4 + 16 + 4)
            else -> throw IllegalStateException("dirección desconocida")
        }
    }
}
