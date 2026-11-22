package com.bedrocklanproxy.core

import kotlin.test.Test
import kotlin.test.assertEquals

class TransferSessionTest {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    // id 0x55, "ab" (varint 2 + bytes), puerto 19132 en little endian (0x4abc -> bc 4a)
    private val base = "55" + "02" + "6162" + "bc4a"

    @Test fun transferAntiguo() = assertEquals(base, hex(TransferSession.transferPacket(712, "ab", 19132)))

    @Test fun transferConReloadWorld() = assertEquals(base + "00", hex(TransferSession.transferPacket(729, "ab", 19132)))

    @Test fun transferConGatherings() = assertEquals(base + "0000", hex(TransferSession.transferPacket(2225, "ab", 19132)))

    @Test fun varIntYDirecciones() {
        val w = ByteWriter()
        w.varUInt(300)
        w.address(java.net.InetSocketAddress("192.168.1.2", 19132))
        // 300 = ac 02; IPv4 se envía con los bytes invertidos (~b) y el puerto en big endian.
        assertEquals("ac02" + "04" + "3f57fefd" + "4abc", hex(w.toByteArray()))
        val r = ByteReader(w.toByteArray())
        assertEquals(300, r.varUInt())
    }
}
