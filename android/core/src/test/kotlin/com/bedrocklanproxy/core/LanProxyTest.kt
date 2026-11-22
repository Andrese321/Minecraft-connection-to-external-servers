package com.bedrocklanproxy.core

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LanProxyTest {
    private val cleanups = mutableListOf<() -> Unit>()

    @AfterTest fun tearDown() = cleanups.asReversed().forEach { it() }

    private fun proxy(serverPort: Int, name: String? = null, sessionTimeoutMs: Long = 30_000): Pair<LanProxy, List<ProxyEvent>> {
        val events = Collections.synchronizedList(mutableListOf<ProxyEvent>())
        val p = LanProxy(
            "127.0.0.1", serverPort, listenHost = "127.0.0.1", listenPort = 0, customName = name,
            listener = { e, _ -> events.add(e) }, pingIntervalMs = 200, sessionTimeoutMs = sessionTimeoutMs,
        )
        p.start()
        cleanups += { p.stop() }
        return p to events
    }

    private fun console(): DatagramSocket =
        DatagramSocket(0, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 2000; cleanups += { close() } }

    private fun DatagramSocket.sendTo(data: ByteArray, port: Int) =
        send(DatagramPacket(data, data.size, InetSocketAddress("127.0.0.1", port)))

    private fun DatagramSocket.recv(): ByteArray {
        val buf = ByteArray(65535)
        val p = DatagramPacket(buf, buf.size)
        receive(p)
        return buf.copyOf(p.length)
    }

    @Test fun queryServer() {
        FakeServer().use { srv ->
            val r = RakNet.query("127.0.0.1", srv.port)
            assertEquals("Servidor de Prueba", r.info.name)
        }
    }

    @Test fun lanDiscoveryAndRelay() {
        FakeServer().use { srv ->
            val (p, events) = proxy(srv.port, name = "Mi LAN")
            assertTrue(p.waitOnline(3000), "el proxy no detectó el servidor")

            // 1) La consola busca partidas LAN y recibe nuestra respuesta.
            val c = console()
            c.sendTo(RakNet.buildPing(555, timestamp = 31337), p.listenPort)
            val pong = assertNotNull(RakNet.parsePong(c.recv()))
            val f = pong.motd.trimEnd(';').split(";")
            assertEquals(31337, pong.timestamp)
            assertEquals(p.guid, pong.guid)
            assertEquals("Mi LAN", f[1])
            assertEquals("1.21.40", f[3])
            assertEquals(p.listenPort.toString(), f[10])

            // 2) La consola "entra": el tráfico va al servidor y vuelve.
            val ocr1 = byteArrayOf(0x05) + RakNet.MAGIC + ByteArray(1400)
            c.sendTo(ocr1, p.listenPort)
            assertContentEquals("ECHO:".toByteArray() + ocr1, c.recv())
            c.sendTo("\u0084juego".toByteArray(Charsets.ISO_8859_1), p.listenPort)
            assertContentEquals("ECHO:\u0084juego".toByteArray(Charsets.ISO_8859_1), c.recv())

            // 3) Una segunda consola usa su propio túnel.
            val c2 = console()
            c2.sendTo("otra".toByteArray(), p.listenPort)
            assertContentEquals("ECHO:otra".toByteArray(), c2.recv())
            assertEquals(2, p.clientCount)
            assertEquals(2, srv.peers.size)
            assertContains(events, ProxyEvent.ONLINE)
            assertContains(events, ProxyEvent.JOIN)
        }
    }

    @Test fun noPongWhenServerOffline() {
        val deadPort = DatagramSocket(0, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val (p, _) = proxy(deadPort)
        assertFalse(p.waitOnline(600))
        val c = console().apply { soTimeout = 500 }
        c.sendTo(RakNet.buildPing(1), p.listenPort)
        assertFailsWith<SocketTimeoutException> { c.recv() }
    }

    @Test fun serverGoesOffline() {
        val (p, events) = FakeServer().use { srv ->
            proxy(srv.port).also { assertTrue(it.first.waitOnline(3000)) }
        }
        val deadline = System.currentTimeMillis() + 8000
        while (p.online && System.currentTimeMillis() < deadline) Thread.sleep(100)
        assertFalse(p.online)
        assertContains(events, ProxyEvent.OFFLINE)
    }

    @Test fun sessionTimeout() {
        FakeServer().use { srv ->
            val (p, events) = proxy(srv.port, sessionTimeoutMs = 300)
            val c = console()
            c.sendTo("hola".toByteArray(), p.listenPort)
            c.recv()
            assertEquals(1, p.clientCount)
            Thread.sleep(1600)
            assertEquals(0, p.clientCount)
            assertContains(events, ProxyEvent.LEAVE)
        }
    }

    @Test fun stopEmitsStoppedAndFreesPort() {
        FakeServer().use { srv ->
            val (p, events) = proxy(srv.port)
            val port = p.listenPort
            p.stop()
            assertFalse(p.running)
            assertContains(events, ProxyEvent.STOPPED)
            DatagramSocket(port, InetAddress.getByName("127.0.0.1")).close() // el puerto quedó libre
        }
    }

    @Test fun portInUse() {
        val busy = DatagramSocket(0, InetAddress.getByName("127.0.0.1")).also { s -> cleanups += { s.close() } }
        val p = LanProxy("127.0.0.1", 19132, listenHost = "127.0.0.1", listenPort = busy.localPort)
        assertFailsWith<ProxyException> { p.start() }
    }

    @Test fun badHost() {
        assertFailsWith<ProxyException> {
            LanProxy("esto-no-existe.invalid", listenHost = "127.0.0.1", listenPort = 0).start()
        }
    }
}
