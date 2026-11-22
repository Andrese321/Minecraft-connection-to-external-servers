"""Servidor Bedrock de mentira: responde pings y hace eco del resto de paquetes."""

import socket
import threading

from bedrock_lan_proxy import raknet

MOTD = "MCPE;Servidor de Prueba;712;1.21.40;5;100;1234567890;Lobby;Survival;1;19132;19133;"


class FakeServer:
    def __init__(self, motd: str = MOTD):
        self.motd = motd
        self.guid = 42
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.bind(("127.0.0.1", 0))
        self.sock.settimeout(0.2)
        self.port = self.sock.getsockname()[1]
        self.peers = set()
        self._stop = threading.Event()
        self._thread = threading.Thread(target=self._run, daemon=True)

    def __enter__(self):
        self._thread.start()
        return self

    def __exit__(self, *exc):
        self._stop.set()
        self._thread.join(2)
        self.sock.close()

    def _run(self):
        while not self._stop.is_set():
            try:
                data, addr = self.sock.recvfrom(65535)
            except socket.timeout:
                continue
            except OSError:
                return
            ping = raknet.parse_ping(data)
            if ping:
                self.sock.sendto(raknet.build_pong(ping[0], self.guid, self.motd), addr)
            else:
                self.peers.add(addr)
                self.sock.sendto(b"ECHO:" + data, addr)
