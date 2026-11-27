package com.bedrocklanproxy.core

import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Paquetes de Minecraft Bedrock que el teléfono necesita para recibir a una consola,
 * hacerla aparecer en un mundo vacío temporal y enviarla al servidor real (Transfer).
 *
 * Los campos y sus cortes por versión de protocolo siguen la documentación del protocolo de
 * Mojang, contrastada con el proyecto abierto BedrockLAN (DanTheMan827), que hace lo mismo
 * con consolas 26.60+. "since X" = el campo existe desde el protocolo X.
 */
object BedrockPackets {
    const val ID_LOGIN = 1
    const val ID_PLAY_STATUS = 2
    const val ID_DISCONNECT = 5
    const val ID_RESOURCE_PACKS_INFO = 6
    const val ID_RESOURCE_PACK_STACK = 7
    const val ID_RESOURCE_PACK_CLIENT_RESPONSE = 8
    const val ID_START_GAME = 11
    const val ID_REQUEST_CHUNK_RADIUS = 69
    const val ID_CHUNK_RADIUS_UPDATED = 70
    const val ID_TRANSFER = 85
    const val ID_SET_LOCAL_PLAYER_AS_INITIALIZED = 113
    const val ID_NETWORK_SETTINGS = 143
    const val ID_CREATIVE_CONTENT = 145
    const val ID_ITEM_REGISTRY = 162
    const val ID_REQUEST_NETWORK_SETTINGS = 193
    const val ID_JIGSAW_STRUCTURE_DATA = 313
    const val ID_VOXEL_SHAPES = 337

    /** Desde este protocolo la consola ignora Transfer si no ha aparecido antes en un mundo. */
    const val PROTOCOL_NEEDS_SPAWN = 2207
    const val PROTOCOL_COMPRESSOR_IN_HEADER = 649

    private fun packet(id: Int, body: GameWriter.() -> Unit): ByteArray =
        GameWriter().apply { varUInt(id); body() }.toByteArray()

    fun networkSettings(protocol: Int) = packet(ID_NETWORK_SETTINGS) {
        u16le(256) // umbral de compresión
        u16le(0) // algoritmo: deflate
        bool(false) // limitar jugadores
        u8(0)
        f32le(0f)
        if (protocol >= 2259) u8(0) // perfil de recuperación SCTP
    }

    fun playStatus(status: Int) = packet(ID_PLAY_STATUS) { i32be(status) }

    fun resourcePacksInfo(protocol: Int) = packet(ID_RESOURCE_PACKS_INFO) {
        bool(false) // packs obligatorios
        bool(false) // addons
        bool(false) // scripts
        if (protocol >= 818) bool(false) // desactivar "vibrant visuals"
        if (protocol >= 766) { uuid(0, 0); string("") } // plantilla del mundo
        if (protocol >= 2168) varUInt(0) else u16le(0) // lista de packs vacía
        if (protocol < 748) varUInt(0) // URLs de CDN
    }

    fun resourcePackStack(protocol: Int) = packet(ID_RESOURCE_PACK_STACK) {
        bool(false) // texturas obligatorias
        if (protocol < 898) varUInt(0) // packs de comportamiento
        varUInt(0) // packs de recursos
        string("*") // versión base del juego
        u32le(0); bool(false) // experimentos: ninguno
        if (protocol < 2239) bool(false) else u8(0) // packs del editor
    }

    fun jigsawStructureData() = packet(ID_JIGSAW_STRUCTURE_DATA) {
        // NBT de red: compuesto con cuatro listas vacías de compuestos.
        u8(10); string("")
        for (name in listOf("processors", "template_pools", "jigsaws", "structure_sets")) {
            u8(9); string(name); u8(10); varInt(0)
        }
        u8(0)
    }

    fun voxelShapes() = packet(ID_VOXEL_SHAPES) {
        varUInt(0) // formas
        varUInt(0) // nombres
        u16le(0) // formas personalizadas
    }

    fun startGame(protocol: Int, worldName: String) = packet(ID_START_GAME) {
        varLong(1) // id único de la entidad
        varULong(1) // id de ejecución
        varInt(2) // modo de juego del jugador: aventura
        f32le(0f); f32le(100f); f32le(0f) // posición
        f32le(0f); f32le(0f) // rotación
        // --- LevelSettings ---
        u64le(0) // semilla
        i16le(0); string(""); varInt(0) // bioma de aparición y dimensión (mundo normal)
        varInt(2) // generador: plano
        varInt(2) // modo de juego: aventura
        bool(false) // extremo
        varInt(1) // dificultad: fácil
        varInt(0); varInt(100); varInt(0) // punto de aparición
        bool(true) // logros desactivados
        varInt(0) // tipo de mundo del editor
        bool(false); bool(false) // creado / exportado desde el editor
        if (protocol >= 2216) u8(0) // versión de migración del editor
        varInt(0) // hora detenida del ciclo día/noche
        varUInt(0) // oferta de Education Edition
        bool(false); string("") // funciones educativas, id de producto
        f32le(0f); f32le(0f) // lluvia, rayos
        bool(false) // contenido de plataforma confirmado
        bool(true); bool(true) // partida multijugador, anunciar en LAN
        varInt(0); varInt(0) // difusión Xbox Live / plataforma
        bool(false) // comandos
        bool(false) // texturas obligatorias
        varUInt(0) // reglas del juego: ninguna
        u32le(0); bool(false) // experimentos: ninguno
        bool(false); bool(false) // cofre bonus, empezar con mapa
        u8(1) // permisos del jugador: miembro
        i32le(4) // distancia de ticks de chunks
        repeat(8) { bool(false) } // packs bloqueados, plantillas, gamertags MSA, aldeanos v1, persona
        bool(false) // skins personalizadas desactivadas
        bool(false) // chat de emotes silenciado
        string("*") // versión base del juego
        i32le(0); i32le(0) // mundo limitado
        bool(true) // nether nuevo
        string(""); string("") // recurso educativo compartido
        bool(false) // forzar jugabilidad experimental: ausente
        u8(0) // restricción del chat
        bool(false) // interacciones desactivadas
        varInt(0) // política de conexión del editor
        bool(false) // drops anónimos en el editor
        // --- fin de LevelSettings ---
        string("") // id del nivel
        string(worldName) // nombre del nivel
        string("") // identidad de la plantilla
        bool(false) // prueba
        varInt(0); bool(false) // movimiento sincronizado
        u64le(0) // hora actual
        varInt(0) // semilla de encantamientos
        varUInt(0) // propiedades de bloques personalizados
        string(UUID.randomUUID().toString()) // id de correlación multijugador
        bool(true) // inventario autoritativo del servidor
        string("*") // versión del servidor
        u8(10); string(""); u8(0) // propiedades del jugador: NBT vacío
        u64le(0) // checksum del registro de bloques
        uuid(0, 0) // id de plantilla del mundo
        bool(false) // generación en el cliente
        bool(false) // ids de bloques como hash
        bool(false) // sonidos autoritativos del servidor
        bool(false) // configuración de unión: ausente
        string(""); string(""); string(""); string("") // telemetría
    }

    fun itemRegistry() = packet(ID_ITEM_REGISTRY) { varUInt(0) }

    fun chunkRadiusUpdated(radius: Int) = packet(ID_CHUNK_RADIUS_UPDATED) { varInt(radius) }

    fun creativeContent() = packet(ID_CREATIVE_CONTENT) { varUInt(0); varUInt(0) }

    fun transfer(protocol: Int, host: String, port: Int) = packet(ID_TRANSFER) {
        string(host)
        u16le(port)
        if (protocol >= 729) bool(false) // recargar mundo
        if (protocol >= 2168) bool(false) // configuración de "gatherings": ausente
        if (protocol >= 2250) bool(false) // opciones de la pantalla de transferencia: ausentes
    }

    fun disconnect(protocol: Int, message: String) = packet(ID_DISCONNECT) {
        if (protocol >= 622) varInt(0)
        bool(false)
        string(message)
        if (protocol >= 712) string(message)
    }

    // ------------------------------------------------------------ lotes (batches)

    fun batch(packets: List<ByteArray>): ByteArray {
        val w = GameWriter()
        for (p in packets) { w.varUInt(p.size); w.bytes(p) }
        return w.toByteArray()
    }

    fun splitBatch(data: ByteArray): List<ByteArray> {
        val r = ByteReader(data)
        val out = ArrayList<ByteArray>()
        while (r.remaining > 0) out += r.bytes(r.varUInt())
        return out
    }

    fun packetId(packet: ByteArray): Int = ByteReader(packet).varUInt() and 0x3FF

    /** Lee el entero big endian que sigue al id (protocolo en RequestNetworkSettings y Login). */
    fun readProtocol(packet: ByteArray): Int {
        val r = ByteReader(packet)
        r.varUInt()
        return r.intBE()
    }

    /** Lee el radio pedido en RequestChunkRadius. */
    fun readChunkRadius(packet: ByteArray): Int {
        val r = ByteReader(packet)
        r.varUInt()
        val u = r.varUInt()
        return (u ushr 1) xor -(u and 1)
    }

    fun inflate(data: ByteArray, offset: Int = 0): ByteArray {
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

    fun deflate(data: ByteArray): ByteArray {
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

/** Escritor con los tipos de Bedrock (little endian salvo que se indique). */
internal class GameWriter {
    private val out = ByteArrayOutputStream()
    fun u8(v: Int) = out.write(v and 0xFF)
    fun bool(v: Boolean) = u8(if (v) 1 else 0)
    fun bytes(b: ByteArray) = out.write(b)
    fun u16le(v: Int) { u8(v); u8(v shr 8) }
    fun i16le(v: Int) = u16le(v)
    fun u32le(v: Int) { for (i in 0 until 4) u8(v shr (8 * i)) }
    fun i32le(v: Int) = u32le(v)
    fun i32be(v: Int) { for (i in 3 downTo 0) u8(v shr (8 * i)) }
    fun u64le(v: Long) { for (i in 0 until 8) u8((v shr (8 * i)).toInt()) }
    fun f32le(f: Float) = u32le(java.lang.Float.floatToIntBits(f))
    fun varUInt(value: Int) {
        var v = value
        while (v and 0x7F.inv() != 0) { u8((v and 0x7F) or 0x80); v = v ushr 7 }
        u8(v)
    }
    fun varInt(v: Int) = varUInt((v shl 1) xor (v shr 31))
    fun varULong(value: Long) {
        var v = value
        while (v and 0x7FL.inv() != 0L) { u8(((v and 0x7F) or 0x80).toInt()); v = v ushr 7 }
        u8(v.toInt())
    }
    fun varLong(v: Long) = varULong((v shl 1) xor (v shr 63))
    fun string(s: String) { val b = s.toByteArray(Charsets.UTF_8); varUInt(b.size); bytes(b) }
    fun uuid(most: Long, least: Long) { u64le(most); u64le(least) }
    fun toByteArray(): ByteArray = out.toByteArray()
}
