"""Proxy UDP que convierte un servidor Bedrock externo en una "partida LAN".

Funcionamiento:

1. Escuchamos en el puerto 19132 de la red local (el que la consola sondea
   cuando abre la pestaña "Amigos" buscando partidas LAN).
2. Cada pocos segundos hacemos ping al servidor real y guardamos su MOTD
   (nombre, versión, jugadores...).
3. Cuando la consola envía un *Unconnected Ping* le respondemos nosotros con
   ese MOTD, así que aparece la partida LAN.
4. Cuando la consola entra, todos sus paquetes se reenvían al servidor real
   por un socket propio para cada consola, y las respuestas vuelven a ella.
"""

from __future__ import annotations

import selectors
import socket
import threading
import time
from dataclasses import dataclass, field
from typing import Callable, Dict, Optional, Tuple

from . import raknet

EventCallback = Callable[[str, str], None]

# Tipos de evento que se emiten a la interfaz.
EV_INFO = "info"
EV_ONLINE = "online"
EV_OFFLINE = "offline"
EV_UNREACHABLE = "unreachable"
EV_CLIENT_JOIN = "join"
EV_CLIENT_LEAVE = "leave"
EV_ERROR = "error"
EV_STOPPED = "stopped"

_MAX_DATAGRAM = 65535
_MAX_READS_PER_WAKE = 512
# Versión reciente de Bedrock para anunciar la partida si el servidor no responde al ping.
FALLBACK_PROTOCOL = "2225"
FALLBACK_VERSION = "1.26.60"


class ProxyError(Exception):
    """Error al arrancar el proxy (puerto ocupado, host inválido...)."""


@dataclass
class _Session:
    client: Tuple[str, int]
    sock: socket.socket
    last_seen: float = field(default_factory=time.monotonic)
    up_bytes: int = 0
    down_bytes: int = 0


def _disable_udp_connreset(sock: socket.socket) -> None:
    # En Windows, un ICMP "port unreachable" hace que recvfrom() lance
    # ConnectionResetError en sockets UDP. Lo desactivamos.
    ioctl = getattr(socket, "SIO_UDP_CONNRESET", None)
    if ioctl is not None:
        try:
            sock.ioctl(ioctl, False)  # type: ignore[attr-defined]
        except (OSError, AttributeError, ValueError):
            pass


class LanProxy:
    def __init__(
        self,
        remote_host: str,
        remote_port: int = raknet.DEFAULT_PORT,
        *,
        listen_host: str = "0.0.0.0",
        listen_port: int = raknet.DEFAULT_PORT,
        custom_name: Optional[str] = None,
        on_event: Optional[EventCallback] = None,
        ping_interval: float = 3.0,
        session_timeout: float = 30.0,
        fallback_after: float = 4.0,
    ) -> None:
        self.remote_host = remote_host
        self.remote_port = remote_port
        self.listen_host = listen_host
        self.listen_port = listen_port
        self.custom_name = custom_name or None
        self.on_event = on_event
        self.ping_interval = ping_interval
        self.session_timeout = session_timeout
        self.fallback_after = fallback_after
        self.blind = False  # anunciando sin respuesta del servidor
        self._started_at = 0.0

        self.guid = raknet.random_guid()
        self.server_info: Optional[raknet.ServerInfo] = None
        self.latency_ms: Optional[float] = None
        self.remote_addr: Optional[tuple] = None

        self._family = socket.AF_INET
        self._lan_sock: Optional[socket.socket] = None
        self._status_sock: Optional[socket.socket] = None
        self._sessions: Dict[Tuple[str, int], _Session] = {}
        self._thread: Optional[threading.Thread] = None
        self._stop = threading.Event()
        self._last_pong = 0.0
        self._ping_sent_at = 0.0
        self._online = False
        self._lock = threading.Lock()

    # ------------------------------------------------------------------ API

    @property
    def running(self) -> bool:
        return self._thread is not None and self._thread.is_alive()

    @property
    def online(self) -> bool:
        return self._online

    @property
    def client_count(self) -> int:
        with self._lock:
            return len(self._sessions)

    def start(self) -> None:
        """Resuelve el servidor y abre el puerto. Lanza ``ProxyError`` si falla."""
        if self.running:
            return
        try:
            self._family, self.remote_addr = raknet.resolve(self.remote_host, self.remote_port)
        except (OSError, UnicodeError) as exc:
            raise ProxyError(f"No se pudo encontrar el servidor '{self.remote_host}': {exc}") from exc

        lan = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            lan.bind((self.listen_host, self.listen_port))
        except OSError as exc:
            lan.close()
            raise ProxyError(
                f"No se pudo abrir el puerto UDP {self.listen_port}: {exc}.\n"
                "¿Está Minecraft u otro programa usando ese puerto en este equipo? "
                "Ciérralo o elige otro puerto."
            ) from exc
        lan.setblocking(False)
        _disable_udp_connreset(lan)
        self._lan_sock = lan
        self.listen_port = lan.getsockname()[1]

        status = socket.socket(self._family, socket.SOCK_DGRAM)
        status.setblocking(False)
        _disable_udp_connreset(status)
        self._status_sock = status

        self._stop.clear()
        self._online = False
        self.server_info = None
        self.blind = False
        self._started_at = time.monotonic()
        self._thread = threading.Thread(target=self._run, name="LanProxy", daemon=True)
        self._thread.start()
        self._emit(
            EV_INFO,
            f"Proxy iniciado en el puerto {self.listen_port} -> "
            f"{self.remote_host}:{self.remote_port} ({self.remote_addr[0]})",
        )

    def stop(self, timeout: float = 3.0) -> None:
        self._stop.set()
        if self._thread is not None:
            self._thread.join(timeout)
            self._thread = None

    def wait_online(self, timeout: float) -> bool:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if self._online:
                return True
            time.sleep(0.05)
        return self._online

    # ------------------------------------------------------------- internos

    def _emit(self, kind: str, message: str) -> None:
        if self.on_event is not None:
            try:
                self.on_event(kind, message)
            except Exception:  # la interfaz nunca debe tumbar el proxy
                pass

    def _run(self) -> None:
        sel = selectors.DefaultSelector()
        assert self._lan_sock is not None and self._status_sock is not None
        sel.register(self._lan_sock, selectors.EVENT_READ, "lan")
        sel.register(self._status_sock, selectors.EVENT_READ, "status")
        next_ping = 0.0
        next_cleanup = time.monotonic() + 1.0
        try:
            while not self._stop.is_set():
                now = time.monotonic()
                if now >= next_ping:
                    self._ping_remote(now)
                    next_ping = now + self.ping_interval
                if (not self.blind and self.server_info is None
                        and now - self._started_at >= self.fallback_after):
                    self.blind = True
                    self._emit(
                        EV_UNREACHABLE,
                        "El servidor no responde al ping. Se anuncia la partida LAN igualmente para que "
                        "puedas intentar entrar. Si no funciona, revisa la IP y el puerto, o puede que sea "
                        "un servidor de Java Edition.",
                    )
                if now >= next_cleanup:
                    self._cleanup(sel, now)
                    next_cleanup = now + 1.0

                for key, _ in sel.select(timeout=0.25):
                    tag = key.data
                    if tag == "lan":
                        self._read_lan(sel)
                    elif tag == "status":
                        self._read_status()
                    else:
                        self._read_session(tag)
        except Exception as exc:  # pragma: no cover - errores inesperados
            self._emit(EV_ERROR, f"Error inesperado en el proxy: {exc!r}")
        finally:
            with self._lock:
                sessions = list(self._sessions.values())
                self._sessions.clear()
            for s in sessions:
                s.sock.close()
            for sock in (self._lan_sock, self._status_sock):
                if sock is not None:
                    sock.close()
            sel.close()
            self._online = False
            self._emit(EV_STOPPED, "Proxy detenido")

    # Estado del servidor remoto ------------------------------------------

    def _ping_remote(self, now: float) -> None:
        assert self._status_sock is not None
        try:
            self._status_sock.sendto(raknet.build_ping(self.guid), self.remote_addr)
            self._ping_sent_at = now
        except OSError as exc:
            self._emit(EV_ERROR, f"No se pudo hacer ping al servidor: {exc}")
        if self._online and now - self._last_pong > max(self.ping_interval * 3, 5.0):
            self._online = False
            self._emit(EV_OFFLINE, "El servidor ha dejado de responder al ping. La partida LAN sigue anunciada.")

    def _read_status(self) -> None:
        assert self._status_sock is not None
        for _ in range(_MAX_READS_PER_WAKE):
            try:
                data, _ = self._status_sock.recvfrom(_MAX_DATAGRAM)
            except (BlockingIOError, InterruptedError):
                return
            except OSError:
                return
            pong = raknet.parse_pong(data)
            if not pong:
                continue
            now = time.monotonic()
            self._last_pong = now
            if self._ping_sent_at:
                self.latency_ms = (now - self._ping_sent_at) * 1000
            info = raknet.ServerInfo.from_motd(pong[2], pong[1])
            self.server_info = info
            if not self._online:
                self._online = True
                self.blind = False
                self._emit(
                    EV_ONLINE,
                    f"Servidor en línea: {info.name} | {info.version} | "
                    f"{info.players}/{info.max_players} jugadores. "
                    "Ya debería aparecer como partida LAN en la consola.",
                )

    # Tráfico de la consola -----------------------------------------------

    def _read_lan(self, sel: selectors.BaseSelector) -> None:
        assert self._lan_sock is not None
        for _ in range(_MAX_READS_PER_WAKE):
            try:
                data, client = self._lan_sock.recvfrom(_MAX_DATAGRAM)
            except (BlockingIOError, InterruptedError):
                return
            except OSError:
                return
            if not data:
                continue

            if raknet.is_ping(data):
                self._answer_ping(data, client)
                continue

            session = self._sessions.get(client)
            if session is None:
                session = self._open_session(sel, client)
                if session is None:
                    continue
            session.last_seen = time.monotonic()
            session.up_bytes += len(data)
            try:
                session.sock.send(data)
            except OSError:
                pass

    def _answer_ping(self, data: bytes, client: Tuple[str, int]) -> None:
        # Con datos del servidor real (aunque ahora no responda) o, si nunca respondió, con unos genéricos.
        info = self.server_info
        if info is None:
            if not self.blind:
                return
            info = self._fallback_info()
        parsed = raknet.parse_ping(data)
        if parsed is None:
            return
        motd = info.for_lan(self.listen_port, self.guid, self.custom_name)
        pong = raknet.build_pong(parsed[0], self.guid, motd)
        try:
            assert self._lan_sock is not None
            self._lan_sock.sendto(pong, client)
        except OSError:
            pass

    def _fallback_info(self) -> raknet.ServerInfo:
        return raknet.ServerInfo(fields=[
            "MCPE", self.custom_name or self.remote_host, FALLBACK_PROTOCOL, FALLBACK_VERSION,
            "0", "100", "0", f"{self.remote_host}:{self.remote_port}", "Survival", "1", "19132", "19133",
        ])

    def _open_session(self, sel: selectors.BaseSelector,
                      client: Tuple[str, int]) -> Optional[_Session]:
        try:
            sock = socket.socket(self._family, socket.SOCK_DGRAM)
            _disable_udp_connreset(sock)
            sock.connect(self.remote_addr)
            sock.setblocking(False)
        except OSError as exc:
            self._emit(EV_ERROR, f"No se pudo abrir conexión con el servidor: {exc}")
            return None
        session = _Session(client=client, sock=sock)
        with self._lock:
            self._sessions[client] = session
        sel.register(sock, selectors.EVENT_READ, session)
        self._emit(EV_CLIENT_JOIN, f"Consola conectándose desde {client[0]}:{client[1]}")
        return session

    def _read_session(self, session: _Session) -> None:
        assert self._lan_sock is not None
        for _ in range(_MAX_READS_PER_WAKE):
            try:
                data = session.sock.recv(_MAX_DATAGRAM)
            except (BlockingIOError, InterruptedError):
                return
            except OSError:
                return
            session.last_seen = time.monotonic()
            session.down_bytes += len(data)
            try:
                self._lan_sock.sendto(data, session.client)
            except OSError:
                pass

    def _cleanup(self, sel: selectors.BaseSelector, now: float) -> None:
        with self._lock:
            expired = [s for s in self._sessions.values()
                       if now - s.last_seen > self.session_timeout]
            for s in expired:
                del self._sessions[s.client]
        for s in expired:
            try:
                sel.unregister(s.sock)
            except (KeyError, ValueError):
                pass
            s.sock.close()
            mb = (s.up_bytes + s.down_bytes) / 1_048_576
            self._emit(EV_CLIENT_LEAVE,
                       f"Consola {s.client[0]} desconectada ({mb:.1f} MB transferidos)")
