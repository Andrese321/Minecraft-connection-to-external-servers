"""Utilidades mínimas del protocolo RakNet que usa Minecraft Bedrock.

Solo necesitamos entender los paquetes "desconectados" que usa la consola para
descubrir partidas LAN:

* ``0x01`` / ``0x02`` Unconnected Ping  -> la consola pregunta "¿hay alguien?"
* ``0x1C`` Unconnected Pong             -> el servidor responde con su MOTD

Todo lo demás (la conexión real de juego) se reenvía tal cual sin tocarlo.
"""

from __future__ import annotations

import os
import socket
import struct
import time
from dataclasses import dataclass, field
from typing import List, Optional, Tuple

MAGIC = bytes.fromhex("00ffff00fefefefefdfdfdfd12345678")

ID_UNCONNECTED_PING = 0x01
ID_UNCONNECTED_PING_OPEN_CONNECTIONS = 0x02
ID_UNCONNECTED_PONG = 0x1C

DEFAULT_PORT = 19132

# Índices de los campos del MOTD de Bedrock:
# MCPE;Línea1;Protocolo;Versión;Jugadores;Máx;ServerID;Línea2;Modo;ModoNum;PuertoV4;PuertoV6;
F_EDITION, F_MOTD, F_PROTOCOL, F_VERSION, F_PLAYERS, F_MAX_PLAYERS = range(6)
F_SERVER_ID, F_LEVEL_NAME, F_GAMEMODE, F_GAMEMODE_NUM, F_PORT_V4, F_PORT_V6 = range(6, 12)

_DEFAULT_FIELDS = [
    "MCPE", "Servidor", "0", "0.0.0", "0", "0", "0",
    "Bedrock level", "Survival", "1", "19132", "19133",
]


def now_ms() -> int:
    return int(time.monotonic() * 1000) & 0xFFFFFFFFFFFFFFFF


def random_guid() -> int:
    return struct.unpack(">q", os.urandom(8))[0]


# --------------------------------------------------------------------------- #
# Ping / Pong
# --------------------------------------------------------------------------- #

def build_ping(client_guid: int, timestamp: Optional[int] = None) -> bytes:
    ts = now_ms() if timestamp is None else timestamp
    return (
        struct.pack(">BQ", ID_UNCONNECTED_PING, ts & 0xFFFFFFFFFFFFFFFF)
        + MAGIC
        + struct.pack(">q", client_guid)
    )


def is_ping(data: bytes) -> bool:
    return (
        len(data) >= 25
        and data[0] in (ID_UNCONNECTED_PING, ID_UNCONNECTED_PING_OPEN_CONNECTIONS)
        and data[9:25] == MAGIC
    )


def parse_ping(data: bytes) -> Optional[Tuple[int, int]]:
    """Devuelve ``(timestamp, guid_cliente)`` o ``None`` si no es un ping."""
    if not is_ping(data):
        return None
    timestamp = struct.unpack_from(">Q", data, 1)[0]
    guid = struct.unpack_from(">q", data, 25)[0] if len(data) >= 33 else 0
    return timestamp, guid


def build_pong(timestamp: int, server_guid: int, motd: str) -> bytes:
    raw = motd.encode("utf-8")[:0xFFFF]
    return (
        struct.pack(">BQq", ID_UNCONNECTED_PONG, timestamp & 0xFFFFFFFFFFFFFFFF, server_guid)
        + MAGIC
        + struct.pack(">H", len(raw))
        + raw
    )


def parse_pong(data: bytes) -> Optional[Tuple[int, int, str]]:
    """Devuelve ``(timestamp, guid_servidor, motd)`` o ``None``."""
    if len(data) < 35 or data[0] != ID_UNCONNECTED_PONG or data[17:33] != MAGIC:
        return None
    timestamp, guid = struct.unpack_from(">Qq", data, 1)
    (length,) = struct.unpack_from(">H", data, 33)
    motd = data[35:35 + length].decode("utf-8", errors="replace")
    return timestamp, guid, motd


# --------------------------------------------------------------------------- #
# Información del servidor (MOTD)
# --------------------------------------------------------------------------- #

@dataclass
class ServerInfo:
    fields: List[str] = field(default_factory=lambda: list(_DEFAULT_FIELDS))
    guid: int = 0

    @classmethod
    def from_motd(cls, motd: str, guid: int = 0) -> "ServerInfo":
        parts = motd.split(";")
        if parts and parts[-1] == "":
            parts.pop()
        return cls(fields=parts, guid=guid)

    def _get(self, index: int) -> str:
        return self.fields[index] if index < len(self.fields) else ""

    @property
    def name(self) -> str:
        return self._get(F_MOTD)

    @property
    def level_name(self) -> str:
        return self._get(F_LEVEL_NAME)

    @property
    def protocol(self) -> str:
        return self._get(F_PROTOCOL)

    @property
    def version(self) -> str:
        return self._get(F_VERSION)

    @property
    def players(self) -> str:
        return self._get(F_PLAYERS)

    @property
    def max_players(self) -> str:
        return self._get(F_MAX_PLAYERS)

    @property
    def gamemode(self) -> str:
        return self._get(F_GAMEMODE)

    def to_motd(self) -> str:
        return ";".join(self.fields) + ";"

    def for_lan(self, local_port: int, server_guid: int, custom_name: Optional[str] = None) -> str:
        """MOTD reescrito para anunciarlo como partida LAN.

        Los puertos se cambian por el puerto local del proxy (la consola se
        conecta al puerto que lee aquí) y el ID del servidor por el GUID del
        proxy para que la entrada LAN sea única.
        """
        f = list(self.fields)
        if len(f) < len(_DEFAULT_FIELDS):
            f.extend(_DEFAULT_FIELDS[len(f):])
        f[F_SERVER_ID] = str(server_guid & 0xFFFFFFFFFFFFFFFF)
        f[F_PORT_V4] = str(local_port)
        f[F_PORT_V6] = str(local_port)
        if custom_name:
            f[F_MOTD] = sanitize_name(custom_name)
        return ";".join(f) + ";"


def sanitize_name(name: str) -> str:
    return name.replace(";", ",").replace("\n", " ").strip()


# --------------------------------------------------------------------------- #
# Direcciones
# --------------------------------------------------------------------------- #

def parse_address(text: str, default_port: int = DEFAULT_PORT) -> Tuple[str, int]:
    """Convierte ``"host"``, ``"host:puerto"`` o ``"[ipv6]:puerto"`` en tupla."""
    text = text.strip()
    if not text:
        raise ValueError("La dirección está vacía")
    if text.startswith("["):
        end = text.find("]")
        if end == -1:
            raise ValueError(f"Dirección IPv6 mal formada: {text}")
        host, rest = text[1:end], text[end + 1:]
        port = int(rest[1:]) if rest.startswith(":") else default_port
    elif text.count(":") == 1:
        host, port_text = text.split(":")
        port = int(port_text) if port_text else default_port
    else:
        host, port = text, default_port
    if not (0 < port < 65536):
        raise ValueError(f"Puerto inválido: {port}")
    return host, port


def resolve(host: str, port: int) -> Tuple[int, tuple]:
    """Resuelve el host (prefiere IPv4). Devuelve ``(familia, sockaddr)``."""
    infos = socket.getaddrinfo(host, port, type=socket.SOCK_DGRAM)
    if not infos:
        raise OSError(f"No se pudo resolver {host}")
    infos.sort(key=lambda i: 0 if i[0] == socket.AF_INET else 1)
    family, _, _, _, sockaddr = infos[0]
    return family, sockaddr


def query_server(host: str, port: int = DEFAULT_PORT, timeout: float = 3.0,
                 attempts: int = 3) -> Tuple[ServerInfo, float]:
    """Hace ping a un servidor Bedrock. Devuelve ``(info, latencia_ms)``."""
    family, addr = resolve(host, port)
    guid = random_guid()
    with socket.socket(family, socket.SOCK_DGRAM) as sock:
        sock.settimeout(timeout / max(attempts, 1))
        for _ in range(max(attempts, 1)):
            start = time.monotonic()
            sock.sendto(build_ping(guid), addr)
            try:
                while True:
                    data, _ = sock.recvfrom(65535)
                    pong = parse_pong(data)
                    if pong:
                        latency = (time.monotonic() - start) * 1000
                        return ServerInfo.from_motd(pong[2], pong[1]), latency
            except (socket.timeout, ConnectionResetError):
                continue
    raise TimeoutError(f"El servidor {host}:{port} no respondió")


def local_ip() -> str:
    """IP de este equipo en la red local (no envía ningún paquete)."""
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        try:
            s.connect(("10.255.255.255", 1))
            return s.getsockname()[0]
        except OSError:
            return "127.0.0.1"
