"""Guarda la lista de servidores favoritos en un JSON en la carpeta del usuario."""

from __future__ import annotations

import json
import os
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import List, Optional

from .raknet import DEFAULT_PORT


@dataclass
class SavedServer:
    name: str
    host: str
    port: int = DEFAULT_PORT

    @property
    def label(self) -> str:
        suffix = "" if self.port == DEFAULT_PORT else f":{self.port}"
        return f"{self.name}  ({self.host}{suffix})"


def config_path() -> Path:
    override = os.environ.get("BEDROCK_LAN_PROXY_CONFIG")
    if override:
        return Path(override)
    return Path.home() / ".bedrock_lan_proxy" / "servidores.json"


def load_servers(path: Optional[Path] = None) -> List[SavedServer]:
    path = path or config_path()
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return []
    servers = []
    for item in data.get("servers", []) if isinstance(data, dict) else []:
        try:
            servers.append(SavedServer(str(item["name"]), str(item["host"]), int(item.get("port", DEFAULT_PORT))))
        except (KeyError, TypeError, ValueError):
            continue
    return servers


def save_servers(servers: List[SavedServer], path: Optional[Path] = None) -> None:
    path = path or config_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps({"servers": [asdict(s) for s in servers]}, indent=2, ensure_ascii=False),
                   encoding="utf-8")
    tmp.replace(path)


def upsert_server(servers: List[SavedServer], server: SavedServer) -> List[SavedServer]:
    """Añade el servidor o reemplaza el que tenga el mismo host y puerto."""
    out = [s for s in servers if not (s.host.lower() == server.host.lower() and s.port == server.port)]
    out.append(server)
    return out
