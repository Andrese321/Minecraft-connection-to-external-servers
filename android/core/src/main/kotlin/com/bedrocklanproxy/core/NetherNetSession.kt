package com.bedrocklanproxy.core

import java.io.ByteArrayOutputStream

/** WebRTC mínimo que necesita NetherNet. En Android se usa libwebrtc; en las pruebas, webrtc-java. */
interface RtcBackend {
    fun createPeer(listener: RtcPeer.Listener): RtcPeer
}

interface RtcPeer {
    interface Listener {
        /** Candidato ICE local, en la forma `candidate:...`. */
        fun onLocalCandidate(candidate: String)
        /** El canal "ReliableDataChannel" está abierto. */
        fun onReliableOpen()
        fun onReliableMessage(data: ByteArray)
        fun onClosed(reason: String)
    }

    /** Aplica la oferta de la consola y devuelve la respuesta SDP (o un error) por el callback. */
    fun answer(offerSdp: String, callback: (answerSdp: String?, error: String?) -> Unit)
    fun addRemoteCandidate(candidate: String, mid: String)
    fun send(data: ByteArray)
    fun close()
}

/**
 * Una consola que entra por NetherNet. Habla la señalización (CONNECTREQUEST/CONNECTRESPONSE/
 * CANDIDATEADD), monta el WebRTC y, por el canal fiable, recibe a la consola en un mundo
 * vacío temporal y la manda al servidor real con un paquete Transfer.
 */
class NetherNetSession(
    val connectionId: String,
    private val backend: RtcBackend,
    private val identity: NetherNetIdentity,
    private val targetHost: String,
    private val targetPort: Int,
    private val worldName: String,
    /** Envía un texto de señalización a la consola (por el puerto 7551). */
    private val signal: (String) -> Unit,
    private val log: (ProxyEvent, String) -> Unit,
    /** Se llama con el protocolo de la consola en cuanto se conoce. */
    private val onProtocol: (Int) -> Unit = {},
) {
    enum class State { NEGOTIATING, CONNECTED, TRANSFERRED, FAILED, CLOSED }

    @Volatile var state = State.NEGOTIATING
        private set
    @Volatile var lastActivity = System.nanoTime()
        private set
    @Volatile var protocol = 0
        private set

    private val lock = Any()
    private var peer: RtcPeer? = null
    private var remoteDescriptionSet = false
    private val pendingCandidates = ArrayList<String>()
    private var mid = "0"

    // Reensamblado de mensajes troceados: el primer byte dice cuántos trozos faltan.
    private var promisedSegments = 0
    private val partial = ByteArrayOutputStream()

    private var compression = false
    private var packResponses = 0
    private var spawnSentAt = 0L
    private var transferSentAt = 0L

    // ------------------------------------------------------------ señalización

    fun onConnectRequest(offer: String) {
        val cleanOffer = Sdp.stripIdentity(offer)
        mid = Sdp.mid(cleanOffer) ?: "0"
        val p = try {
            backend.createPeer(listener)
        } catch (e: Throwable) {
            fail("no se pudo crear la conexión WebRTC: $e", ERROR_FAILED_TO_CREATE_PEER)
            return
        }
        synchronized(lock) { peer = p }
        p.answer(cleanOffer) { answer, error ->
            if (answer == null) {
                fail("la consola envió una oferta WebRTC que no se pudo aceptar: $error", ERROR_FAILED_TO_CREATE_ANSWER)
                return@answer
            }
            val signed = try {
                identity.signAnswer(answer)
            } catch (e: Exception) {
                fail("no se pudo firmar la respuesta: $e", ERROR_FAILED_TO_CREATE_ANSWER)
                return@answer
            }
            signal("CONNECTRESPONSE $connectionId $signed")
            // Candidatos que ya vengan dentro de la respuesta: se envían también por separado.
            for (c in Sdp.candidates(signed)) signal("CANDIDATEADD $connectionId $c")
            val queued = synchronized(lock) {
                remoteDescriptionSet = true
                pendingCandidates.toList().also { pendingCandidates.clear() }
            }
            for (c in queued) p.addRemoteCandidate(c, mid)
        }
    }

    fun onCandidateAdd(candidate: String) {
        val c = Sdp.normalizeCandidate(candidate) ?: return
        val p = synchronized(lock) {
            if (!remoteDescriptionSet) { pendingCandidates += c; null } else peer
        } ?: return
        p.addRemoteCandidate(c, mid)
    }

    fun onConnectError(code: String) {
        log(ProxyEvent.ERROR, "La consola canceló la conexión NetherNet (código $code).")
        close(State.FAILED)
    }

    private val listener = object : RtcPeer.Listener {
        override fun onLocalCandidate(candidate: String) {
            signal("CANDIDATEADD $connectionId ${Sdp.normalizeCandidate(candidate) ?: return}")
        }

        override fun onReliableOpen() {
            state = State.CONNECTED
            lastActivity = System.nanoTime()
            log(ProxyEvent.INFO, "Conexión NetherNet establecida con la consola. Preparando la transferencia…")
        }

        override fun onReliableMessage(data: ByteArray) {
            lastActivity = System.nanoTime()
            try {
                onSegment(data)
            } catch (e: Exception) {
                log(ProxyEvent.ERROR, "Datos inesperados de la consola: $e")
            }
        }

        override fun onClosed(reason: String) {
            close(if (transferSentAt != 0L) State.TRANSFERRED else State.CLOSED)
        }
    }

    // ------------------------------------------------------------ juego

    private fun onSegment(data: ByteArray) {
        if (data.size < 2) return
        val remaining = data[0].toInt() and 0xFF
        synchronized(lock) {
            if (promisedSegments > 0 && promisedSegments - 1 != remaining) partial.reset()
            promisedSegments = remaining
            partial.write(data, 1, data.size - 1)
            if (partial.size() > 8 * 1024 * 1024) { partial.reset(); promisedSegments = 0; return }
            if (remaining > 0) return
        }
        val batch = synchronized(lock) { partial.toByteArray().also { partial.reset() } }
        onBatch(batch)
    }

    private fun onBatch(raw: ByteArray) {
        val data = when {
            !compression -> raw
            protocol >= BedrockPackets.PROTOCOL_COMPRESSOR_IN_HEADER -> when (raw[0].toInt() and 0xFF) {
                0x00 -> BedrockPackets.inflate(raw, 1)
                0xFF -> raw.copyOfRange(1, raw.size)
                else -> throw IllegalStateException("compresión no soportada (${raw[0]})")
            }
            else -> BedrockPackets.inflate(raw)
        }
        for (packet in BedrockPackets.splitBatch(data)) onPacket(packet)
    }

    private fun onPacket(packet: ByteArray) {
        when (BedrockPackets.packetId(packet)) {
            BedrockPackets.ID_REQUEST_NETWORK_SETTINGS -> {
                protocol = BedrockPackets.readProtocol(packet)
                log(ProxyEvent.INFO, "La consola usa el protocolo $protocol.")
                onProtocol(protocol)
                if (protocol > BedrockVersions.MAX_SUPPORTED) {
                    log(
                        ProxyEvent.ERROR,
                        "Tu consola tiene una versión de Minecraft más nueva que las comprobadas por la app " +
                            "(protocolo $protocol > ${BedrockVersions.MAX_SUPPORTED}). Se intenta igualmente; si " +
                            "no entra, hace falta actualizar la app.",
                    )
                }
                sendBatch(listOf(BedrockPackets.networkSettings(protocol)))
                compression = true
            }
            BedrockPackets.ID_LOGIN -> {
                if (protocol == 0) protocol = BedrockPackets.readProtocol(packet)
                sendBatch(listOf(BedrockPackets.playStatus(0), BedrockPackets.resourcePacksInfo(protocol)))
            }
            BedrockPackets.ID_RESOURCE_PACK_CLIENT_RESPONSE -> {
                packResponses++
                if (packResponses == 1) {
                    sendBatch(listOf(BedrockPackets.resourcePackStack(protocol)))
                } else if (packResponses == 2) {
                    if (protocol >= BedrockPackets.PROTOCOL_NEEDS_SPAWN) {
                        sendBatch(
                            listOf(
                                BedrockPackets.jigsawStructureData(),
                                BedrockPackets.voxelShapes(),
                                BedrockPackets.startGame(protocol, worldName),
                                BedrockPackets.itemRegistry(),
                            ),
                        )
                        spawnSentAt = System.nanoTime()
                    } else {
                        sendTransfer()
                    }
                }
            }
            BedrockPackets.ID_REQUEST_CHUNK_RADIUS -> {
                val radius = BedrockPackets.readChunkRadius(packet).coerceIn(1, 8)
                sendBatch(
                    listOf(
                        BedrockPackets.chunkRadiusUpdated(radius),
                        BedrockPackets.playStatus(3), // player_spawn
                        BedrockPackets.creativeContent(),
                    ),
                )
            }
            BedrockPackets.ID_SET_LOCAL_PLAYER_AS_INITIALIZED -> sendTransfer()
        }
    }

    private fun sendTransfer() {
        if (transferSentAt != 0L) return
        sendBatch(listOf(BedrockPackets.transfer(protocol, targetHost, targetPort)))
        transferSentAt = System.nanoTime()
        log(ProxyEvent.INFO, "Enviando la consola a $targetHost:$targetPort…")
    }

    private fun sendBatch(packets: List<ByteArray>) {
        val raw = BedrockPackets.batch(packets)
        val payload = when {
            !compression -> raw
            protocol >= BedrockPackets.PROTOCOL_COMPRESSOR_IN_HEADER -> byteArrayOf(0) + BedrockPackets.deflate(raw)
            else -> BedrockPackets.deflate(raw)
        }
        val p = synchronized(lock) { peer } ?: return
        // Trozos de como mucho 10 000 bytes, cada uno con el número de trozos que faltan.
        val chunks = (payload.size + MAX_SEGMENT - 1) / MAX_SEGMENT
        for (i in 0 until chunks) {
            val start = i * MAX_SEGMENT
            val end = minOf(start + MAX_SEGMENT, payload.size)
            p.send(byteArrayOf((chunks - 1 - i).toByte()) + payload.copyOfRange(start, end))
        }
    }

    /** Llamar periódicamente: plazos de la aparición y de la transferencia. */
    fun tick(now: Long) {
        if (state == State.CONNECTED && spawnSentAt != 0L && transferSentAt == 0L && now - spawnSentAt > SPAWN_TIMEOUT_NS) {
            sendTransfer() // la consola no confirmó que apareció: se transfiere igualmente
        }
        if (transferSentAt != 0L && now - transferSentAt > AFTER_TRANSFER_NS) {
            close(State.TRANSFERRED) // la consola ya debería haberse ido al servidor
        }
        if (state == State.NEGOTIATING && now - lastActivity > NEGOTIATION_TIMEOUT_NS) {
            fail("la conexión WebRTC con la consola no llegó a establecerse", ERROR_NEGOTIATION_TIMEOUT)
        }
    }

    private fun fail(message: String, code: Int) {
        log(ProxyEvent.ERROR, "NetherNet: $message.")
        signal("CONNECTERROR $connectionId $code")
        close(State.FAILED)
    }

    fun close(final: State = State.CLOSED) {
        val p = synchronized(lock) {
            if (state == State.TRANSFERRED || state == State.FAILED || state == State.CLOSED) return
            state = final
            peer.also { peer = null }
        }
        try { p?.close() } catch (_: Throwable) {}
    }

    val finished: Boolean get() = state == State.TRANSFERRED || state == State.FAILED || state == State.CLOSED
    val transferSent: Boolean get() = transferSentAt != 0L

    companion object {
        private const val MAX_SEGMENT = 10_000
        private const val SPAWN_TIMEOUT_NS = 8_000_000_000L
        private const val AFTER_TRANSFER_NS = 15_000_000_000L
        private const val NEGOTIATION_TIMEOUT_NS = 20_000_000_000L
        const val ERROR_FAILED_TO_CREATE_PEER = 4
        const val ERROR_NEGOTIATION_TIMEOUT = 2
        const val ERROR_FAILED_TO_CREATE_ANSWER = 11
    }
}
