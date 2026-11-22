package com.bedrocklanproxy.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RakNetTest {
    @Test fun pingRoundtrip() {
        val pkt = RakNet.buildPing(-5, timestamp = 123456)
        assertEquals(33, pkt.size)
        assertTrue(RakNet.isPing(pkt))
        assertEquals(123456L to -5L, RakNet.parsePing(pkt))
    }

    @Test fun pingOpenConnectionsVariant() {
        val pkt = RakNet.buildPing(1, 99).also { it[0] = 0x02 }
        assertEquals(99L to 1L, RakNet.parsePing(pkt))
    }

    @Test fun notAPing() {
        assertFalse(RakNet.isPing(byteArrayOf(0x05) + ByteArray(40)))
        assertNull(RakNet.parsePing(byteArrayOf(0x01, 0x00)))
    }

    @Test fun pongRoundtripUtf8() {
        val motd = "MCPE;Ñandú ✨;712;1.21.40;0;10;1;Mundo;Survival;1;19132;19133;"
        val pkt = RakNet.buildPong(77, 0x1122334455667788, motd)
        assertEquals(RakNet.Pong(77, 0x1122334455667788, motd), RakNet.parsePong(pkt))
    }

    @Test fun pongIsByteCompatibleWithPythonVersion() {
        // Mismos bytes que build_pong(1, 2, "MCPE;") en la versión de PC.
        val expected = "1c0000000000000001000000000000000200ffff00fefefefefdfdfdfd12345678" + "0005" + "4d4350453b"
        val hex = RakNet.buildPong(1, 2, "MCPE;").joinToString("") { "%02x".format(it) }
        assertEquals(expected, hex)
    }

    @Test fun badPong() {
        assertNull(RakNet.parsePong(byteArrayOf(0x1C) + ByteArray(40)))
    }

    @Test fun parseMotd() {
        val info = ServerInfo.fromMotd("MCPE;Mi Server;712;1.21.40;3;50;999;Lobby;Survival;1;25565;25566;")
        assertEquals("Mi Server", info.name)
        assertEquals("1.21.40", info.version)
        assertEquals("3" to "50", info.players to info.maxPlayers)
        assertEquals("Lobby", info.levelName)
    }

    @Test fun forLanRewritesPortsGuidAndName() {
        val info = ServerInfo.fromMotd("MCPE;Mi Server;712;1.21.40;3;50;999;Lobby;Survival;1;25565;25566;")
        val f = info.forLan(19132, 7, "Hola;Mundo").trimEnd(';').split(";")
        assertEquals("Hola,Mundo", f[1])
        assertEquals("7", f[6])
        assertEquals(listOf("19132", "19132"), f.subList(10, 12))
        assertEquals(listOf("712", "1.21.40"), f.subList(2, 4))
    }

    @Test fun forLanPadsShortMotd() {
        val f = ServerInfo.fromMotd("MCPE;Corto;712;1.21.40;0;10").forLan(19132, -1).trimEnd(';').split(";")
        assertEquals(12, f.size)
        assertEquals("Corto", f[1])
        assertEquals("18446744073709551615", f[6])
    }

    @Test fun parseAddress() {
        assertEquals("play.ejemplo.net" to 19132, RakNet.parseAddress("play.ejemplo.net"))
        assertEquals("1.2.3.4" to 19150, RakNet.parseAddress(" 1.2.3.4:19150 "))
        assertEquals("2001:db8::1" to 19133, RakNet.parseAddress("[2001:db8::1]:19133"))
        assertEquals("2001:db8::1" to 19132, RakNet.parseAddress("2001:db8::1"))
        assertEquals("host" to 1234, RakNet.parseAddress("host", 1234))
    }

    @Test fun parseAddressErrors() {
        for (bad in listOf("", "host:0", "host:70000", "host:abc", ":19132")) {
            assertFailsWith<IllegalArgumentException>(bad) { RakNet.parseAddress(bad) }
        }
    }
}
