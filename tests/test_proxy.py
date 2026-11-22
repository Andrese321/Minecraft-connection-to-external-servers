import socket
import tempfile
import unittest
from pathlib import Path

from bedrock_lan_proxy import config, raknet
from bedrock_lan_proxy.proxy import LanProxy, ProxyError
from tests.fake_server import FakeServer


def client_socket():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.bind(("127.0.0.1", 0))
    s.settimeout(2)
    return s


class ProxyTests(unittest.TestCase):
    def make_proxy(self, server_port, **kw):
        events = []
        proxy = LanProxy("127.0.0.1", server_port, listen_host="127.0.0.1", listen_port=0,
                         ping_interval=0.2, fallback_after=1.0,
                         on_event=lambda k, m: events.append(k), **kw)
        proxy.start()
        self.addCleanup(proxy.stop)
        return proxy, events

    def test_query_server(self):
        with FakeServer() as srv:
            info, latency = raknet.query_server("127.0.0.1", srv.port)
            self.assertEqual(info.name, "Servidor de Prueba")
            self.assertGreaterEqual(latency, 0)

    def test_lan_discovery_and_relay(self):
        with FakeServer() as srv:
            proxy, events = self.make_proxy(srv.port, custom_name="Mi LAN")
            self.assertTrue(proxy.wait_online(3), "el proxy no detectó el servidor")
            addr = ("127.0.0.1", proxy.listen_port)

            # 1) La consola busca partidas LAN y recibe nuestra respuesta.
            console = client_socket()
            self.addCleanup(console.close)
            console.sendto(raknet.build_ping(555, timestamp=31337), addr)
            ts, guid, motd = raknet.parse_pong(console.recvfrom(65535)[0])
            fields = motd.rstrip(";").split(";")
            self.assertEqual(ts, 31337)
            self.assertEqual(guid, proxy.guid)
            self.assertEqual(fields[1], "Mi LAN")
            self.assertEqual(fields[3], "1.21.40")
            self.assertEqual(fields[10], str(proxy.listen_port))

            # 2) La consola "entra": el tráfico se reenvía al servidor y vuelve.
            payload = bytes([0x05]) + raknet.MAGIC + b"\x00" * 1400  # Open Connection Request 1
            console.sendto(payload, addr)
            self.assertEqual(console.recvfrom(65535)[0], b"ECHO:" + payload)
            console.sendto(b"\x84juego", addr)
            self.assertEqual(console.recvfrom(65535)[0], b"ECHO:\x84juego")

            # 3) Una segunda consola usa su propio túnel.
            other = client_socket()
            self.addCleanup(other.close)
            other.sendto(b"\x84otra", addr)
            self.assertEqual(other.recvfrom(65535)[0], b"ECHO:\x84otra")
            self.assertEqual(proxy.client_count, 2)
            self.assertEqual(len(srv.peers), 2)
            self.assertIn("online", events)
            self.assertIn("join", events)

    def test_blind_advertising_when_server_does_not_answer(self):
        dead = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        dead.bind(("127.0.0.1", 0))
        port = dead.getsockname()[1]
        dead.close()
        proxy, events = self.make_proxy(port, custom_name="Mi Server")
        console = client_socket()
        self.addCleanup(console.close)
        console.settimeout(0.5)
        # Antes del plazo no se anuncia nada.
        console.sendto(raknet.build_ping(1), ("127.0.0.1", proxy.listen_port))
        with self.assertRaises(socket.timeout):
            console.recvfrom(65535)
        # Después, sí: con datos genéricos y el nombre elegido.
        import time
        deadline = time.monotonic() + 3
        while not proxy.blind and time.monotonic() < deadline:
            time.sleep(0.05)
        self.assertTrue(proxy.blind)
        self.assertFalse(proxy.online)
        self.assertIn("unreachable", events)
        console.sendto(raknet.build_ping(1, timestamp=7), ("127.0.0.1", proxy.listen_port))
        ts, _, motd = raknet.parse_pong(console.recvfrom(65535)[0])
        fields = motd.rstrip(";").split(";")
        self.assertEqual(ts, 7)
        self.assertEqual(fields[1], "Mi Server")
        self.assertEqual(fields[10], str(proxy.listen_port))

    def test_server_goes_offline(self):
        with FakeServer() as srv:
            proxy, events = self.make_proxy(srv.port)
            self.assertTrue(proxy.wait_online(3))
        # el servidor se ha cerrado: tras unos segundos debe marcarse offline
        import time
        deadline = time.monotonic() + 8
        while proxy.online and time.monotonic() < deadline:
            time.sleep(0.1)
        self.assertFalse(proxy.online)
        self.assertIn("offline", events)

    def test_session_timeout(self):
        with FakeServer() as srv:
            proxy, events = self.make_proxy(srv.port, session_timeout=0.3)
            console = client_socket()
            self.addCleanup(console.close)
            console.sendto(b"\x84hola", ("127.0.0.1", proxy.listen_port))
            console.recvfrom(65535)
            self.assertEqual(proxy.client_count, 1)
            import time
            time.sleep(1.6)
            self.assertEqual(proxy.client_count, 0)
            self.assertIn("leave", events)

    def test_port_in_use(self):
        busy = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        busy.bind(("127.0.0.1", 0))
        self.addCleanup(busy.close)
        proxy = LanProxy("127.0.0.1", 19132, listen_host="127.0.0.1",
                         listen_port=busy.getsockname()[1])
        with self.assertRaises(ProxyError):
            proxy.start()

    def test_bad_host(self):
        with self.assertRaises(ProxyError):
            LanProxy("esto-no-existe.invalid", listen_host="127.0.0.1", listen_port=0).start()


class ConfigTests(unittest.TestCase):
    def test_save_and_load(self):
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / "sub" / "servers.json"
            servers = config.upsert_server([], config.SavedServer("A", "a.net"))
            servers = config.upsert_server(servers, config.SavedServer("B", "b.net", 19150))
            servers = config.upsert_server(servers, config.SavedServer("A2", "A.NET"))
            config.save_servers(servers, path)
            loaded = config.load_servers(path)
            self.assertEqual([s.name for s in loaded], ["B", "A2"])
            self.assertEqual(loaded[0].port, 19150)

    def test_load_missing_or_corrupt(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "x.json"
            self.assertEqual(config.load_servers(p), [])
            p.write_text("{no json")
            self.assertEqual(config.load_servers(p), [])


if __name__ == "__main__":
    unittest.main()
