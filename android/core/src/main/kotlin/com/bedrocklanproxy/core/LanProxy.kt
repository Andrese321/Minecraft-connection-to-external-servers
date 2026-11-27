package com.bedrocklanproxy.core

import java.io.IOException
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.net.StandardProtocolFamily
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.util.concurrent.ConcurrentHashMap

enum class ProxyEvent { INFO, ONLINE, OFFLINE, UNREACHABLE, JOIN, LEAVE, TRANSFERRED, ERROR, STOPPED }

/**
 * RELAY: el teléfono reenvía todo el tráfico (debe seguir encendido mientras se juega).
 * TRANSFER: el teléfono solo recibe a la consola y la manda al servidor real con un paquete
 * Transfer; después se puede cerrar la app. Si una consola ignora la transferencia, en su
 * siguiente intento se usa RELAY automáticamente.
 */
enum class ProxyMode { RELAY, TRANSFER }

fun interface ProxyListener {
    fun onEvent(event: ProxyEvent, message: String)
}

class ProxyException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Proxy UDP que convierte un servidor Bedrock externo en una "partida LAN".
 *
 * 1. Escucha en el puerto 19132 de la red local (donde la consola busca partidas LAN).
 * 2. Hace ping al servidor real cada pocos segundos y guarda su MOTD.
 * 3. Responde a los pings de la consola con ese MOTD: aparece la partida LAN.
 * 4. Reenvía el tráfico de cada consola al servidor por un canal propio, y las respuestas de vuelta.
 */
class LanProxy(
    val remoteHost: String,
    val remotePort: Int = RakNet.DEFAULT_PORT,
    private val listenHost: String = "0.0.0.0",
    listenPort: Int = RakNet.DEFAULT_PORT,
    customName: String? = null,
    private val listener: ProxyListener? = null,
    private val pingIntervalMs: Long = 3000,
    private val sessionTimeoutMs: Long = 30_000,
    /** Si el servidor no responde al ping en este tiempo, se anuncia la partida igualmente. */
    private val fallbackAfterMs: Long = 4000,
    val mode: ProxyMode = ProxyMode.RELAY,
    /** Puerto del descubrimiento NetherNet (consolas 26.60+). 0 = aleatorio (pruebas), -1 = desactivado. */
    private val netherNetPort: Int = NetherNet.DISCOVERY_PORT,
    /** WebRTC para que las consolas 26.60+ puedan entrar. Sin él solo se anuncia la partida. */
    private val rtcBackend: RtcBackend? = null,
    private val netherNetIdentity: NetherNetIdentity? = null,
) {
    val customName: String? = customName?.takeIf { it.isNotBlank() }
    val guid: Long = RakNet.randomGuid()

    @Volatile var listenPort: Int = listenPort
        private set
    @Volatile var serverInfo: ServerInfo? = null
        private set
    @Volatile var latencyMs: Long? = null
        private set
    @Volatile var online: Boolean = false
        private set
    @Volatile var remoteAddress: InetSocketAddress? = null
        private set
    /** true si se está anunciando la partida sin haber recibido respuesta del servidor. */
    @Volatile var blind: Boolean = false
        private set

    val running: Boolean get() = thread?.isAlive == true
    val clientCount: Int get() = sessions.size

    private class Session(val client: SocketAddress, val channel: DatagramChannel) {
        @Volatile var lastSeen = System.nanoTime()
        var upBytes = 0L
        var downBytes = 0L
    }

    private val sessions = ConcurrentHashMap<SocketAddress, Session>()
    private val transfers = HashMap<SocketAddress, TransferSession>()
    /** Consolas que ignoraron la transferencia: hasta cuándo usan el modo puente. */
    private val relayFallback = HashMap<java.net.InetAddress, Long>()
    private val searchLogged = HashMap<String, Long>()
    private var pendingWarning: String? = null
    private var lan: DatagramChannel? = null
    private var status: DatagramChannel? = null
    private var netherNet: DatagramChannel? = null
    /** Identificador de este "servidor" en NetherNet. */
    val netherNetId: Long = RakNet.randomGuid() and Long.MAX_VALUE
    private val netherNetNonce: String = (1..16).joinToString("") { "%02x".format(RakNet.randomGuid().toInt() and 0xFF) }
    private var netherNetJoinLogged = false
    private val netherNetSessions = java.util.concurrent.ConcurrentHashMap<String, NetherNetSession>()
    private val identity: NetherNetIdentity by lazy { netherNetIdentity ?: NetherNetIdentity.generate() }
    /** Puerto real del descubrimiento NetherNet (o -1 si no se pudo abrir). */
    @Volatile var netherNetBoundPort: Int = -1
        private set
    private var selector: Selector? = null
    @Volatile private var thread: Thread? = null
    @Volatile private var stopRequested = false
    private var lastPong = 0L
    private var startedAt = 0L
    private var pingSentAt = 0L
    private val buffer: ByteBuffer = ByteBuffer.allocateDirect(MAX_DATAGRAM)
    private val bytes = ByteArray(MAX_DATAGRAM)

    // ------------------------------------------------------------------ API

    /** Resuelve el servidor y abre el puerto. Bloqueante (hace DNS). */
    @Throws(ProxyException::class)
    fun start() {
        if (running) return
        val remote = try {
            RakNet.resolve(remoteHost, remotePort)
        } catch (e: Exception) {
            throw ProxyException("No se pudo encontrar el servidor '$remoteHost': ${e.message}", e)
        }
        remoteAddress = remote

        val lanChannel = try {
            DatagramChannel.open(StandardProtocolFamily.INET).apply {
                bind(InetSocketAddress(listenHost, listenPort))
                configureBlocking(false)
            }
        } catch (e: IOException) {
            throw ProxyException(
                "No se pudo abrir el puerto UDP $listenPort: ${e.message}. " +
                    "¿Está Minecraft abierto en este mismo dispositivo? Ciérralo e inténtalo de nuevo.", e,
            )
        }
        listenPort = (lanChannel.localAddress as InetSocketAddress).port

        val statusChannel = DatagramChannel.open().apply { configureBlocking(false) }
        val sel = Selector.open()
        lanChannel.register(sel, SelectionKey.OP_READ, TAG_LAN)
        statusChannel.register(sel, SelectionKey.OP_READ, TAG_STATUS)
        netherNet = null
        netherNetBoundPort = -1
        netherNetJoinLogged = false
        if (netherNetPort >= 0) {
            try {
                val nn = DatagramChannel.open(StandardProtocolFamily.INET).apply {
                    bind(InetSocketAddress(listenHost, netherNetPort))
                    configureBlocking(false)
                }
                nn.register(sel, SelectionKey.OP_READ, TAG_NETHERNET)
                netherNet = nn
                netherNetBoundPort = (nn.localAddress as InetSocketAddress).port
            } catch (e: IOException) {
                pendingWarning = "No se pudo abrir el puerto UDP $netherNetPort para las consolas 26.60+ " +
                    "(${e.message}). Solo te verán las consolas con versiones anteriores."
            }
        }

        lan = lanChannel
        status = statusChannel
        selector = sel
        stopRequested = false
        online = false
        serverInfo = null
        lastPong = 0
        blind = false
        startedAt = System.nanoTime()
        thread = Thread(::run, "LanProxy").apply { isDaemon = true; start() }
        emit(
            ProxyEvent.INFO,
            "Proxy iniciado en el puerto $listenPort -> $remoteHost:$remotePort (${remote.address.hostAddress})" +
                if (netherNetBoundPort > 0) ". Escuchando también a consolas 26.60+ (NetherNet, puerto $netherNetBoundPort)." else "",
        )
        pendingWarning?.let { emit(ProxyEvent.ERROR, it); pendingWarning = null }
    }

    fun stop(timeoutMs: Long = 3000) {
        stopRequested = true
        selector?.wakeup()
        thread?.join(timeoutMs)
        thread = null
    }

    fun waitOnline(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (online) return true
            Thread.sleep(50)
        }
        return online
    }

    // ------------------------------------------------------------ internos

    private fun emit(event: ProxyEvent, message: String) {
        try {
            listener?.onEvent(event, message)
        } catch (_: Throwable) {
            // la interfaz nunca debe tumbar el proxy
        }
    }

    private fun run() {
        val sel = selector!!
        var nextPing = 0L
        var nextCleanup = System.nanoTime() + 1_000_000_000L
        try {
            while (!stopRequested) {
                val now = System.nanoTime()
                if (now >= nextPing) {
                    pingRemote(now)
                    nextPing = now + pingIntervalMs * 1_000_000
                }
                if (!blind && serverInfo == null && now - startedAt >= fallbackAfterMs * 1_000_000) {
                    blind = true
                    emit(
                        ProxyEvent.UNREACHABLE,
                        "El servidor no responde al ping. Se anuncia la partida LAN igualmente para que " +
                            "puedas intentar entrar. Si no funciona, revisa la IP y el puerto, o puede que " +
                            "sea un servidor de Java Edition.",
                    )
                }
                if (transfers.isNotEmpty()) tickTransfers(now)
                if (netherNetSessions.isNotEmpty()) tickNetherNet(now)
                if (now >= nextCleanup) {
                    cleanup(now)
                    nextCleanup = now + 1_000_000_000L
                }
                sel.select(250)
                val it = sel.selectedKeys().iterator()
                while (it.hasNext()) {
                    val key = it.next()
                    it.remove()
                    if (!key.isValid) continue
                    when (val tag = key.attachment()) {
                        TAG_LAN -> readLan(sel)
                        TAG_STATUS -> readStatus()
                        TAG_NETHERNET -> readNetherNet()
                        is Session -> readSession(tag)
                    }
                }
            }
        } catch (e: Throwable) {
            emit(ProxyEvent.ERROR, "Error inesperado en el proxy: $e")
        } finally {
            sessions.values.forEach { closeQuietly(it.channel) }
            sessions.clear()
            closeQuietly(lan)
            closeQuietly(status)
            netherNetSessions.values.forEach { it.close() }
            netherNetSessions.clear()
            closeQuietly(netherNet)
            try { sel.close() } catch (_: IOException) {}
            online = false
            emit(ProxyEvent.STOPPED, "Proxy detenido")
        }
    }

    private fun receive(channel: DatagramChannel): Pair<SocketAddress, Int>? {
        buffer.clear()
        val from = try { channel.receive(buffer) } catch (_: IOException) { null } ?: return null
        buffer.flip()
        val n = buffer.remaining()
        buffer.get(bytes, 0, n)
        return from to n
    }

    // Estado del servidor remoto -------------------------------------------

    private fun pingRemote(now: Long) {
        try {
            status!!.send(ByteBuffer.wrap(RakNet.buildPing(guid)), remoteAddress!!)
            pingSentAt = now
        } catch (e: IOException) {
            emit(ProxyEvent.ERROR, "No se pudo hacer ping al servidor: ${e.message}")
        }
        val offlineAfter = maxOf(pingIntervalMs * 3, 5000) * 1_000_000
        if (online && now - lastPong > offlineAfter) {
            online = false
            emit(ProxyEvent.OFFLINE, "El servidor ha dejado de responder al ping. La partida LAN sigue anunciada.")
        }
    }

    private fun readStatus() {
        repeat(MAX_READS_PER_WAKE) {
            val (_, n) = receive(status!!) ?: return
            val pong = RakNet.parsePong(bytes, n) ?: return@repeat
            val now = System.nanoTime()
            lastPong = now
            if (pingSentAt != 0L) latencyMs = (now - pingSentAt) / 1_000_000
            val info = ServerInfo.fromMotd(pong.motd, pong.guid)
            serverInfo = info
            if (!online) {
                online = true
                blind = false
                emit(
                    ProxyEvent.ONLINE,
                    "Servidor en línea: ${info.name} | ${info.version} | ${info.players}/${info.maxPlayers} " +
                        "jugadores. Ya debería aparecer como partida LAN en la consola.",
                )
            }
        }
    }

    // Tráfico de la consola -------------------------------------------------

    private fun readLan(sel: Selector) {
        repeat(MAX_READS_PER_WAKE) {
            val (client, n) = receive(lan!!) ?: return
            if (n == 0) return@repeat
            if (RakNet.isPing(bytes, n)) {
                answerPing(client, n)
                return@repeat
            }
            if (mode == ProxyMode.TRANSFER && !sessions.containsKey(client) && !usesRelay(client)) {
                handleTransfer(client, n)
                return@repeat
            }
            val session = sessions[client] ?: openSession(sel, client) ?: return@repeat
            session.lastSeen = System.nanoTime()
            session.upBytes += n
            try {
                session.channel.write(ByteBuffer.wrap(bytes, 0, n))
            } catch (_: IOException) {
            }
        }
    }

    private fun usesRelay(client: SocketAddress): Boolean {
        val ip = (client as? InetSocketAddress)?.address ?: return false
        val until = relayFallback[ip] ?: return false
        if (System.nanoTime() < until) return true
        relayFallback.remove(ip)
        return false
    }

    private fun handleTransfer(client: SocketAddress, n: Int) {
        val addr = client as? InetSocketAddress ?: return
        var t = transfers[client]
        if (t == null) {
            // Una conexión nueva siempre empieza con Open Connection Request 1.
            if (bytes[0].toInt() != TransferSession.ID_OPEN_CONNECTION_REQUEST_1) return
            t = TransferSession(
                client = addr,
                serverGuid = guid,
                targetHost = remoteHost,
                targetPort = remotePort,
                send = { data ->
                    try { lan!!.send(ByteBuffer.wrap(data), client) } catch (_: IOException) {}
                },
                log = ::emit,
            )
            transfers[client] = t
            emit(ProxyEvent.JOIN, "Consola conectándose desde ${describe(client)} (modo transferencia)")
        }
        t.handle(bytes, n)
        if (t.outcome != TransferSession.Outcome.PENDING) finishTransfer(t)
    }

    private fun tickTransfers(now: Long) {
        for (t in transfers.values.toList()) {
            t.tick(now)
            when {
                t.outcome != TransferSession.Outcome.PENDING -> finishTransfer(t)
                now - t.lastSeen > TRANSFER_IDLE_NS -> {
                    transfers.remove(t.client)
                    emit(ProxyEvent.LEAVE, "La consola ${describe(t.client)} dejó de responder")
                }
            }
        }
    }

    private fun finishTransfer(t: TransferSession) {
        transfers.remove(t.client)
        when (t.outcome) {
            TransferSession.Outcome.TRANSFERRED -> emit(
                ProxyEvent.TRANSFERRED,
                "La consola ${t.client.address.hostAddress} se ha ido al servidor. Ya puedes cerrar la app " +
                    "(hace falta otra vez solo para volver a entrar).",
            )
            TransferSession.Outcome.IGNORED -> {
                relayFallback[t.client.address] = System.nanoTime() + RELAY_FALLBACK_NS
                emit(
                    ProxyEvent.ERROR,
                    "La consola ${t.client.address.hostAddress} no aceptó la transferencia. Vuelve a entrar en " +
                        "la partida LAN: esta vez el teléfono hará de puente (deja la app abierta).",
                )
            }
            else -> emit(ProxyEvent.LEAVE, "La consola ${describe(t.client)} se desconectó")
        }
    }

    private fun answerPing(client: SocketAddress, n: Int) {
        // Con datos del servidor real (aunque ahora no responda) o, si nunca respondió, con unos genéricos.
        val info = serverInfo ?: if (blind) fallbackInfo() else null
        logSearch(client, info)
        if (info == null) return
        val (timestamp, _) = RakNet.parsePing(bytes, n) ?: return
        val pong = RakNet.buildPong(timestamp, guid, info.forLan(listenPort, guid, customName))
        try {
            lan!!.send(ByteBuffer.wrap(pong), client)
        } catch (_: IOException) {
        }
    }

    /** Anota (como mucho cada 30 s por dispositivo) que alguien está buscando partidas LAN. */
    private fun logSearch(client: SocketAddress, info: ServerInfo?, system: String = "RakNet") {
        val ip = (client as? InetSocketAddress)?.address ?: return
        val now = System.nanoTime()
        val key = "$system/${ip.hostAddress}"
        if (now - (searchLogged[key] ?: Long.MIN_VALUE / 2) < 30_000_000_000L) return
        searchLogged[key] = now
        val who = ip.hostAddress
        emit(
            ProxyEvent.INFO,
            if (info == null) {
                "La consola $who está buscando partidas LAN [$system] (aún no se anuncia: esperando al servidor)."
            } else {
                "La consola $who está buscando partidas LAN [$system]: respondiendo como «${customName ?: info.name}» " +
                    "(versión ${info.version}, protocolo ${info.protocol})."
            },
        )
    }

    // Descubrimiento NetherNet (consolas 26.60+) ---------------------------------

    private fun readNetherNet() {
        val channel = netherNet ?: return
        repeat(MAX_READS_PER_WAKE) {
            val (from, n) = receive(channel) ?: return
            val packet = NetherNet.decode(bytes, n) ?: return@repeat
            when (packet.type) {
                NetherNet.TYPE_REQUEST -> {
                    val info = serverInfo ?: if (blind) fallbackInfo() else null
                    logSearch(from, info, "NetherNet")
                    if (info == null) return@repeat
                    val ad = NetherNet.advertisement(
                        name = customName ?: info.name,
                        protocol = info.protocol.toIntOrNull() ?: FALLBACK_PROTOCOL.toInt(),
                        gameVersion = info.version,
                        levelName = info.levelName.ifBlank { "$remoteHost:$remotePort" },
                        players = info.players.toIntOrNull() ?: 0,
                        maxPlayers = info.maxPlayers.toIntOrNull()?.takeIf { it > 0 } ?: 100,
                        gamemode = if (info.gamemode.equals("Creative", ignoreCase = true)) 1 else 0,
                        nonce = netherNetNonce,
                    )
                    val reply = NetherNet.encode(NetherNet.TYPE_RESPONSE, netherNetId, NetherNet.responseBody(ad))
                    try { channel.send(ByteBuffer.wrap(reply), from) } catch (_: IOException) {}
                }
                NetherNet.TYPE_MESSAGE -> {
                    val (recipient, text) = NetherNet.parseMessage(packet.body) ?: return@repeat
                    if (recipient != netherNetId) return@repeat
                    handleSignal(from, packet.senderId, text)
                }
            }
        }
    }

    /** Señalización NetherNet: "TIPO idConexión datos". */
    private fun handleSignal(from: SocketAddress, consoleId: Long, text: String) {
        val firstSpace = text.indexOf(' ')
        if (firstSpace < 0) return // "Ping" u otros mensajes sin datos
        val type = text.substring(0, firstSpace)
        val rest = text.substring(firstSpace + 1)
        val secondSpace = rest.indexOf(' ')
        val connId = if (secondSpace < 0) rest else rest.substring(0, secondSpace)
        val data = if (secondSpace < 0) "" else rest.substring(secondSpace + 1)
        val who = (from as? InetSocketAddress)?.address?.hostAddress ?: from.toString()

        if (rtcBackend == null) {
            if (type == "CONNECTREQUEST" && !netherNetJoinLogged) {
                netherNetJoinLogged = true
                emit(ProxyEvent.INFO, "¡La consola $who pulsó la partida! (NetherNet), pero esta versión no puede recibirla.")
            }
            return
        }
        when (type) {
            "CONNECTREQUEST" -> {
                if (netherNetSessions.containsKey(connId)) return
                val session = NetherNetSession(
                    connectionId = connId,
                    backend = rtcBackend,
                    identity = identity,
                    targetHost = remoteHost,
                    targetPort = remotePort,
                    worldName = customName ?: serverInfo?.name ?: remoteHost,
                    signal = { reply -> sendSignal(from, consoleId, reply) },
                    log = ::emit,
                )
                netherNetSessions[connId] = session
                emit(ProxyEvent.JOIN, "La consola $who quiere entrar (NetherNet). Conectando…")
                session.onConnectRequest(data)
            }
            "CANDIDATEADD" -> netherNetSessions[connId]?.onCandidateAdd(data)
            "CONNECTERROR" -> netherNetSessions[connId]?.onConnectError(data)
        }
    }

    private fun sendSignal(to: SocketAddress, consoleId: Long, text: String) {
        val channel = netherNet ?: return
        val pkt = NetherNet.encode(NetherNet.TYPE_MESSAGE, netherNetId, NetherNet.messageBody(consoleId, text))
        try {
            synchronized(channel) { channel.send(ByteBuffer.wrap(pkt), to) }
        } catch (_: IOException) {
        }
    }

    private fun tickNetherNet(now: Long) {
        for ((id, s) in netherNetSessions) {
            s.tick(now)
            if (s.finished) {
                netherNetSessions.remove(id)
                when (s.state) {
                    NetherNetSession.State.TRANSFERRED -> emit(
                        ProxyEvent.TRANSFERRED,
                        "La consola se ha ido al servidor. Ya puedes cerrar la app (solo hace falta para volver a entrar).",
                    )
                    NetherNetSession.State.CLOSED ->
                        if (s.transferSent) emit(ProxyEvent.TRANSFERRED, "La consola se ha ido al servidor. Ya puedes cerrar la app.")
                        else emit(ProxyEvent.LEAVE, "La consola cerró la conexión NetherNet antes de transferirse.")
                    else -> Unit
                }
            }
        }
    }

    private fun fallbackInfo(): ServerInfo = ServerInfo(
        listOf(
            "MCPE", customName ?: remoteHost, FALLBACK_PROTOCOL, FALLBACK_VERSION, "0", "100", "0",
            "$remoteHost:$remotePort", "Survival", "1", "19132", "19133",
        ),
    )

    private fun openSession(sel: Selector, client: SocketAddress): Session? {
        val target = remoteAddress ?: return null
        val channel = try {
            // Ojo: dentro de apply{}, "remoteAddress" sería la del propio canal; por eso usamos "target".
            DatagramChannel.open().apply {
                connect(target)
                configureBlocking(false)
            }
        } catch (e: Exception) {
            emit(ProxyEvent.ERROR, "No se pudo abrir conexión con el servidor: ${e.message}")
            return null
        }
        val session = Session(client, channel)
        sessions[client] = session
        channel.register(sel, SelectionKey.OP_READ, session)
        emit(ProxyEvent.JOIN, "Consola conectándose desde ${describe(client)}")
        return session
    }

    private fun readSession(session: Session) {
        repeat(MAX_READS_PER_WAKE) {
            buffer.clear()
            val n = try { session.channel.read(buffer) } catch (_: IOException) { return }
            if (n <= 0) return
            buffer.flip()
            session.lastSeen = System.nanoTime()
            session.downBytes += n
            try {
                lan!!.send(buffer, session.client)
            } catch (_: IOException) {
            }
        }
    }

    private fun cleanup(now: Long) {
        val timeout = sessionTimeoutMs * 1_000_000
        for (s in sessions.values) {
            if (now - s.lastSeen <= timeout) continue
            sessions.remove(s.client)
            closeQuietly(s.channel) // también cancela su registro en el selector
            val mb = (s.upBytes + s.downBytes) / 1_048_576.0
            emit(ProxyEvent.LEAVE, "Consola ${describe(s.client)} desconectada (%.1f MB transferidos)".format(mb))
        }
    }

    private fun describe(addr: SocketAddress) =
        (addr as? InetSocketAddress)?.let { "${it.address.hostAddress}:${it.port}" } ?: addr.toString()

    private fun closeQuietly(c: DatagramChannel?) {
        try { c?.close() } catch (_: IOException) {}
    }

    private companion object {
        const val MAX_DATAGRAM = 65535
        const val MAX_READS_PER_WAKE = 512
        const val TAG_LAN = "lan"
        const val TAG_STATUS = "status"
        const val TAG_NETHERNET = "nethernet"
        const val TRANSFER_IDLE_NS = 20_000_000_000L
        const val RELAY_FALLBACK_NS = 10 * 60 * 1_000_000_000L
        // Versión reciente de Bedrock para anunciar la partida si el servidor no responde al ping.
        const val FALLBACK_PROTOCOL = "2225"
        const val FALLBACK_VERSION = "1.26.60"
    }
}
