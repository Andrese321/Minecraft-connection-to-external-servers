package com.bedrocklanproxy.core

import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Identidad que Minecraft exige en cada respuesta SDP de NetherNet (atributo `a=identity`):
 * un JWT ES384 autofirmado con la clave pública en la reclamación `cpk`, más una firma JWS
 * separada sobre las huellas DTLS de la respuesta. Formato según la documentación de
 * señalización de NetherNet y las implementaciones abiertas (go-nethernet, BedrockLAN).
 *
 * La clave se guarda para que la consola vea siempre la misma identidad.
 */
class NetherNetIdentity(private val keys: KeyPair, val domain: String = DEFAULT_DOMAIN) {

    /** Devuelve la respuesta SDP con una línea `a=identity:` recién firmada. */
    fun signAnswer(answerSdp: String): String {
        val cleaned = Sdp.stripIdentity(answerSdp)
        val fingerprints = Sdp.fingerprints(cleaned)
        require(fingerprints.isNotEmpty()) { "La respuesta SDP no tiene huellas DTLS" }
        val assertion = "{\"fingerprints\":" + jsonString(signDetached(canonicalFingerprints(fingerprints))) +
            ",\"token\":" + jsonString(createToken()) + "}"
        val envelope = "{\"assertion\":" + jsonString(assertion) +
            ",\"idp\":{\"domain\":" + jsonString(domain) + ",\"protocol\":\"default\"}}"
        val attribute = "a=identity:" + Base64.getEncoder().encodeToString(envelope.toByteArray(Charsets.UTF_8))
        return Sdp.insertSessionAttribute(cleaned, attribute)
    }

    private fun createToken(): String {
        val pub = keys.public as ECPublicKey
        val issuedAt = System.currentTimeMillis() / 1000
        val header = "{\"alg\":\"ES384\",\"x5u\":" + jsonString(Base64.getEncoder().encodeToString(pub.encoded)) + "}"
        val claims = "{\"cpk\":{\"crv\":\"P-384\",\"kty\":\"EC\"," +
            "\"x\":" + jsonString(b64url(unsigned48(pub.w.affineX))) + "," +
            "\"y\":" + jsonString(b64url(unsigned48(pub.w.affineY))) + "}," +
            "\"exp\":${issuedAt + 3600},\"iat\":$issuedAt,\"iss\":" + jsonString(domain) + "}"
        val signingInput = b64url(header.toByteArray()) + "." + b64url(claims.toByteArray())
        return signingInput + "." + b64url(sign(signingInput))
    }

    private fun signDetached(payload: String): String {
        val header = b64url("{\"alg\":\"ES384\"}".toByteArray())
        return header + ".." + b64url(sign(header + "." + b64url(payload.toByteArray(Charsets.UTF_8))))
    }

    /** Firma ECDSA P-384 con SHA-384 en formato JOSE (r || s, 48 bytes cada uno). */
    private fun sign(input: String): ByteArray {
        val der = Signature.getInstance("SHA384withECDSA").run {
            initSign(keys.private)
            update(input.toByteArray(Charsets.US_ASCII))
            sign()
        }
        return derToP1363(der)
    }

    companion object {
        const val DEFAULT_DOMAIN = "bedrock-lan-proxy"

        fun generate(domain: String = DEFAULT_DOMAIN): NetherNetIdentity {
            val gen = KeyPairGenerator.getInstance("EC")
            gen.initialize(ECGenParameterSpec("secp384r1"))
            return NetherNetIdentity(gen.generateKeyPair(), domain)
        }

        /** Carga la clave del archivo o crea una nueva y la guarda. */
        fun loadOrCreate(file: File, domain: String = DEFAULT_DOMAIN): NetherNetIdentity {
            try {
                if (file.exists()) {
                    val (privB64, pubB64) = file.readText().trim().split("\n")
                    val kf = KeyFactory.getInstance("EC")
                    val priv = kf.generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(privB64)))
                    val pub = kf.generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(pubB64)))
                    if (priv is ECPrivateKey && pub is ECPublicKey) return NetherNetIdentity(KeyPair(pub, priv), domain)
                }
            } catch (_: Exception) {
                // clave dañada: se crea otra
            }
            val id = generate(domain)
            try {
                file.parentFile?.mkdirs()
                file.writeText(
                    Base64.getEncoder().encodeToString(id.keys.private.encoded) + "\n" +
                        Base64.getEncoder().encodeToString(id.keys.public.encoded),
                )
            } catch (_: Exception) {
            }
            return id
        }

        internal fun canonicalFingerprints(fps: List<Pair<String, String>>): String =
            fps.joinToString(",", prefix = "{\"fingerprint\":[", postfix = "]}") { (alg, digest) ->
                "{\"algorithm\":" + jsonString(alg) + ",\"digest\":" + jsonString(digest) + "}"
            }

        internal fun jsonString(s: String): String {
            val sb = StringBuilder("\"")
            for (c in s) {
                when {
                    c == '"' -> sb.append("\\\"")
                    c == '\\' -> sb.append("\\\\")
                    c == '\n' -> sb.append("\\n")
                    c == '\r' -> sb.append("\\r")
                    c == '\t' -> sb.append("\\t")
                    c < ' ' -> sb.append("\\u%04x".format(c.code))
                    else -> sb.append(c)
                }
            }
            return sb.append('"').toString()
        }

        private fun b64url(b: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(b)

        private fun unsigned48(v: BigInteger): ByteArray {
            val raw = v.toByteArray()
            val out = ByteArray(48)
            val src = if (raw.size > 48) raw.copyOfRange(raw.size - 48, raw.size) else raw
            System.arraycopy(src, 0, out, 48 - src.size, src.size)
            return out
        }

        /** Convierte una firma ECDSA en DER a r || s de 48 bytes cada uno. */
        internal fun derToP1363(der: ByteArray): ByteArray {
            var i = 2
            if (der[1].toInt() and 0x80 != 0) i += der[1].toInt() and 0x7F // longitud larga
            require(der[i].toInt() == 0x02)
            val rLen = der[i + 1].toInt() and 0xFF
            val r = BigInteger(1, der.copyOfRange(i + 2, i + 2 + rLen))
            i += 2 + rLen
            require(der[i].toInt() == 0x02)
            val sLen = der[i + 1].toInt() and 0xFF
            val s = BigInteger(1, der.copyOfRange(i + 2, i + 2 + sLen))
            return unsigned48(r) + unsigned48(s)
        }
    }
}

/** Utilidades de SDP por líneas. */
object Sdp {
    private fun lines(sdp: String): MutableList<String> =
        sdp.replace("\r\n", "\n").split("\n").toMutableList().also { while (it.isNotEmpty() && it.last().isEmpty()) it.removeAt(it.size - 1) }

    private fun newline(sdp: String) = if (sdp.contains("\r\n")) "\r\n" else "\n"

    private fun join(lines: List<String>, nl: String) = lines.joinToString(nl) + nl

    fun stripIdentity(sdp: String): String = join(lines(sdp).filterNot { it.startsWith("a=identity:") }, newline(sdp))

    fun insertSessionAttribute(sdp: String, attribute: String): String {
        val l = lines(sdp)
        val m = l.indexOfFirst { it.startsWith("m=") }
        l.add(if (m < 0) l.size else m, attribute)
        return join(l, newline(sdp))
    }

    fun fingerprints(sdp: String): List<Pair<String, String>> = lines(sdp)
        .filter { it.startsWith("a=fingerprint:") }
        .mapNotNull { line ->
            val v = line.removePrefix("a=fingerprint:").trim()
            val sp = v.indexOf(' ')
            if (sp > 0) v.substring(0, sp) to v.substring(sp + 1).trim() else null
        }

    fun mid(sdp: String): String? = lines(sdp).firstOrNull { it.startsWith("a=mid:") }?.removePrefix("a=mid:")?.trim()

    /** Candidatos ICE de una SDP en la forma `candidate:...`. */
    fun candidates(sdp: String): List<String> = lines(sdp).filter { it.startsWith("a=candidate:") }.map { it.substring(2) }

    fun normalizeCandidate(candidate: String): String? {
        var v = candidate.trim()
        if (v.startsWith("a=")) v = v.substring(2)
        if (v.isEmpty()) return null
        return if (v.startsWith("candidate:")) v else "candidate:$v"
    }
}
