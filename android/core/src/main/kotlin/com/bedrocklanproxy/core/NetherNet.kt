package com.bedrocklanproxy.core

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Descubrimiento LAN de NetherNet (Minecraft Bedrock 26.60 y posteriores).
 *
 * Desde la 26.60 las consolas ya no buscan partidas LAN con RakNet (UDP 19132):
 * envían por difusión un "DiscoveryRequest" al puerto UDP 7551 y esperan un
 * "DiscoveryResponse" con los datos del servidor.
 *
 * Cada datagrama es: HMAC-SHA256(texto) [32 bytes] + AES-256-ECB/PKCS7(texto).
 * La clave de ambos es SHA-256 del entero de 64 bits 0xDEADBEEF en little endian.
 * Texto (little endian): u16 longitud del resto, u16 tipo, u64 id del emisor,
 * 8 bytes a cero y el cuerpo del paquete.
 */
object NetherNet {
    const val DISCOVERY_PORT = 7551

    const val TYPE_REQUEST = 0
    const val TYPE_RESPONSE = 1
    const val TYPE_MESSAGE = 2

    private val key: ByteArray = MessageDigest.getInstance("SHA-256").digest(
        ByteArray(8).also { b -> val v = 0xDEADBEEFL; for (i in 0 until 8) b[i] = (v shr (8 * i)).toByte() },
    )

    data class Packet(val type: Int, val senderId: Long, val body: ByteArray)

    private fun cipher(mode: Int): Cipher =
        Cipher.getInstance("AES/ECB/PKCS5Padding").apply { init(mode, SecretKeySpec(key, "AES")) }

    private fun hmac(data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

    fun encode(type: Int, senderId: Long, body: ByteArray): ByteArray {
        val inner = ByteWriterLE()
        inner.u16(type)
        inner.u64(senderId)
        inner.bytes(ByteArray(8))
        inner.bytes(body)
        val innerBytes = inner.toByteArray()
        val plain = ByteWriterLE().apply { u16(innerBytes.size); bytes(innerBytes) }.toByteArray()
        return hmac(plain) + cipher(Cipher.ENCRYPT_MODE).doFinal(plain)
    }

    /** Descifra y verifica un datagrama. Devuelve null si no es válido. */
    fun decode(data: ByteArray, length: Int = data.size): Packet? {
        if (length < 32 + 16 || (length - 32) % 16 != 0) return null
        val plain = try {
            cipher(Cipher.DECRYPT_MODE).doFinal(data, 32, length - 32)
        } catch (_: Exception) {
            return null
        }
        if (!MessageDigest.isEqual(hmac(plain), data.copyOfRange(0, 32))) return null
        if (plain.size < 20) return null
        val type = (plain[2].toInt() and 0xFF) or ((plain[3].toInt() and 0xFF) shl 8)
        var sender = 0L
        for (i in 0 until 8) sender = sender or ((plain[4 + i].toLong() and 0xFF) shl (8 * i))
        return Packet(type, sender, plain.copyOfRange(20, plain.size))
    }

    /** Cuerpo de un DiscoveryResponse: u32 longitud + datos del servidor en hexadecimal (ASCII). */
    fun responseBody(advertisement: ByteArray): ByteArray {
        val hex = advertisement.joinToString("") { "%02x".format(it) }.toByteArray(Charsets.US_ASCII)
        return ByteWriterLE().apply { u32(hex.size); bytes(hex) }.toByteArray()
    }

    /** Cuerpo de un DiscoveryMessage: u64 destinatario + u32 longitud + texto de señalización. */
    fun messageBody(recipientId: Long, text: String): ByteArray {
        val b = text.toByteArray(Charsets.UTF_8)
        return ByteWriterLE().apply { u64(recipientId); u32(b.size); bytes(b) }.toByteArray()
    }

    /** Lee un DiscoveryMessage: (destinatario, texto). */
    fun parseMessage(body: ByteArray): Pair<Long, String>? {
        if (body.size < 12) return null
        var recipient = 0L
        for (i in 0 until 8) recipient = recipient or ((body[i].toLong() and 0xFF) shl (8 * i))
        // La longitud declarada no es fiable en algunas consolas: se usa el resto del paquete.
        return recipient to String(body, 12, body.size - 12, Charsets.UTF_8)
    }

    /**
     * Datos del servidor que muestra la consola en la lista LAN (formato versión 7):
     * nombre, protocolo, versión, mundo, jugadores, máximo, modo de juego, flags,
     * nonce y tipo de conexión (4 = LAN).
     */
    fun advertisement(
        name: String,
        protocol: Int,
        gameVersion: String,
        levelName: String,
        players: Int,
        maxPlayers: Int,
        gamemode: Int,
        nonce: String,
    ): ByteArray {
        val w = ByteWriterLE()
        w.u8(7)
        w.string(name)
        w.zigzag32(protocol)
        w.string(gameVersion)
        w.string(levelName)
        w.zigzag32(players)
        w.zigzag32(maxPlayers)
        w.zigzag32(gamemode)
        w.u8(0) // mundo del editor
        w.u8(0) // extremo
        w.u8(1) // acepta cuentas con sesión de Xbox
        w.u8(1) // acepta cuentas autofirmadas
        w.string(nonce)
        w.zigzag32(4) // tipo de conexión: LAN
        return w.toByteArray()
    }
}

internal class ByteWriterLE {
    private val out = java.io.ByteArrayOutputStream()
    fun u8(v: Int) = out.write(v and 0xFF)
    fun bytes(b: ByteArray) = out.write(b)
    fun u16(v: Int) { u8(v); u8(v shr 8) }
    fun u32(v: Int) { for (i in 0 until 4) u8(v shr (8 * i)) }
    fun u64(v: Long) { for (i in 0 until 8) u8((v shr (8 * i)).toInt()) }
    fun varUInt(value: Int) {
        var v = value
        while (v and 0x7F.inv() != 0) { u8((v and 0x7F) or 0x80); v = v ushr 7 }
        u8(v)
    }
    fun zigzag32(v: Int) = varUInt((v shl 1) xor (v shr 31))
    fun string(s: String) { val b = s.toByteArray(Charsets.UTF_8); varUInt(b.size); bytes(b) }
    fun toByteArray(): ByteArray = out.toByteArray()
}
