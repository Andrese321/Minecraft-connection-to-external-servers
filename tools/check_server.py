"""Diagnóstico: ¿la dirección es un servidor Bedrock (UDP) o Java (TCP)?

Uso: python tools/check_server.py host:puerto [host:puerto ...]
"""

import json
import socket
import struct
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from bedrock_lan_proxy import raknet  # noqa: E402


def _varint(n: int) -> bytes:
    out = b""
    while True:
        b = n & 0x7F
        n >>= 7
        out += bytes([b | (0x80 if n else 0)])
        if not n:
            return out


def _read_varint(sock) -> int:
    result = shift = 0
    while True:
        b = sock.recv(1)
        if not b:
            raise ConnectionError("conexión cerrada")
        result |= (b[0] & 0x7F) << shift
        if not b[0] & 0x80:
            return result
        shift += 7


def java_status(host: str, port: int, timeout: float = 5.0) -> dict:
    with socket.create_connection((host, port), timeout=timeout) as s:
        hs = _varint(0) + _varint(767) + _varint(len(host)) + host.encode() + struct.pack(">H", port) + _varint(1)
        s.sendall(_varint(len(hs)) + hs)
        s.sendall(_varint(1) + _varint(0))
        _read_varint(s)  # longitud del paquete
        _read_varint(s)  # id
        length = _read_varint(s)
        data = b""
        while len(data) < length:
            chunk = s.recv(length - len(data))
            if not chunk:
                break
            data += chunk
        return json.loads(data.decode("utf-8", "replace"))


def main() -> None:
    for arg in sys.argv[1:]:
        host, port = raknet.parse_address(arg)
        print(f"== {host}:{port}")
        try:
            info, ms = raknet.query_server(host, port, timeout=6, attempts=3)
            print(f"  Bedrock (UDP): RESPONDE en {ms:.0f} ms -> {info.to_motd()}")
        except Exception as exc:  # noqa: BLE001
            print(f"  Bedrock (UDP): no responde ({exc})")
        try:
            start = time.monotonic()
            st = java_status(host, port)
            ver = st.get("version", {})
            players = st.get("players", {})
            print(f"  Java (TCP): RESPONDE en {(time.monotonic()-start)*1000:.0f} ms -> "
                  f"versión {ver.get('name')} | {players.get('online')}/{players.get('max')} jugadores")
        except Exception as exc:  # noqa: BLE001
            print(f"  Java (TCP): no responde ({exc})")


if __name__ == "__main__":
    main()
