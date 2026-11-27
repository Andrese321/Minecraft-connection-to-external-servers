package com.bedrocklanproxy.core

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NetherNetTest {
    @Test fun encodeDecodeRoundtrip() {
        val pkt = NetherNet.encode(NetherNet.TYPE_MESSAGE, 1234L, NetherNet.messageBody(99L, "CONNECTREQUEST 5 v=0"))
        val d = assertNotNull(NetherNet.decode(pkt))
        assertEquals(NetherNet.TYPE_MESSAGE, d.type)
        assertEquals(1234L, d.senderId)
        assertEquals(99L to "CONNECTREQUEST 5 v=0", NetherNet.parseMessage(d.body))
        // Una petición vacía mide 64 bytes, como la que envían las consolas.
        assertEquals(64, NetherNet.encode(NetherNet.TYPE_REQUEST, 1L, ByteArray(0)).size)
    }

    @Test fun rejectsTamperedPackets() {
        val pkt = NetherNet.encode(NetherNet.TYPE_REQUEST, 1L, ByteArray(0))
        pkt[0] = (pkt[0] + 1).toByte()
        assertNull(NetherNet.decode(pkt))
        assertNull(NetherNet.decode(ByteArray(10)))
    }

    @Test fun proxyAnswersNetherNetDiscovery() {
        FakeServer().use { srv ->
            val p = LanProxy(
                "127.0.0.1", srv.port, listenHost = "127.0.0.1", listenPort = 0,
                customName = "Mi LAN", pingIntervalMs = 200, netherNetPort = 0,
            )
            p.start()
            try {
                assertTrue(p.waitOnline(3000))
                assertTrue(p.netherNetBoundPort > 0)
                DatagramSocket(0, InetAddress.getByName("127.0.0.1")).use { c ->
                    c.soTimeout = 2000
                    val req = NetherNet.encode(NetherNet.TYPE_REQUEST, 777L, ByteArray(0))
                    c.send(DatagramPacket(req, req.size, InetSocketAddress("127.0.0.1", p.netherNetBoundPort)))
                    val buf = ByteArray(2048)
                    val dp = DatagramPacket(buf, buf.size)
                    c.receive(dp)
                    val resp = assertNotNull(NetherNet.decode(buf, dp.length))
                    assertEquals(NetherNet.TYPE_RESPONSE, resp.type)
                    assertEquals(p.netherNetId, resp.senderId)
                    val hexLen = ByteReaderLE(resp.body).u32()
                    val hex = String(resp.body, 4, hexLen, Charsets.US_ASCII)
                    val ad = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                    val r = ByteReaderLE(ad)
                    assertEquals(7, r.u8())
                    assertEquals("Mi LAN", r.string())
                    assertEquals(712, r.zigzag32())
                    assertEquals("1.21.40", r.string())
                    assertEquals("Lobby", r.string())
                }
            } finally {
                p.stop()
            }
        }
    }
}

/** Lector mínimo para las pruebas. */
class ByteReaderLE(private val b: ByteArray) {
    private var pos = 0
    fun u8() = b[pos++].toInt() and 0xFF
    fun u32(): Int { var v = 0; for (i in 0 until 4) v = v or (u8() shl (8 * i)); return v }
    fun varUInt(): Int { var r = 0; var s = 0; while (true) { val x = u8(); r = r or ((x and 0x7F) shl s); if (x and 0x80 == 0) return r; s += 7 } }
    fun zigzag32(): Int { val u = varUInt(); return (u ushr 1) xor -(u and 1) }
    fun string(): String { val n = varUInt(); return String(b, pos, n, Charsets.UTF_8).also { pos += n } }
}
