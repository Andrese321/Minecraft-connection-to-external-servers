"""Modo terminal: menú interactivo o conexión directa por argumentos."""

from __future__ import annotations

import threading
import time
from typing import Optional

from . import APP_NAME, __version__, config, raknet
from .proxy import EV_ERROR, EV_STOPPED, LanProxy, ProxyError

_ICONS = {
    "info": "[i]", "online": "[OK]", "offline": "[!]", "join": "[+]",
    "leave": "[-]", "error": "[ERROR]", "stopped": "[x]",
}


def _print_event(kind: str, message: str) -> None:
    stamp = time.strftime("%H:%M:%S")
    print(f"{stamp} {_ICONS.get(kind, '[?]')} {message}", flush=True)


def run_proxy(host: str, port: int = raknet.DEFAULT_PORT, name: Optional[str] = None,
              listen_port: int = raknet.DEFAULT_PORT) -> int:
    """Arranca el proxy y bloquea hasta Ctrl+C. Devuelve el código de salida."""
    stopped = threading.Event()

    def on_event(kind: str, message: str) -> None:
        _print_event(kind, message)
        if kind == EV_STOPPED:
            stopped.set()

    proxy = LanProxy(host, port, listen_port=listen_port, custom_name=name, on_event=on_event)
    try:
        proxy.start()
    except ProxyError as exc:
        _print_event(EV_ERROR, str(exc))
        return 1

    print()
    print(f"  IP de este equipo en tu red: {raknet.local_ip()}")
    print("  En la consola: Jugar -> pestaña Amigos -> busca la partida LAN.")
    print("  La consola y este equipo deben estar en la MISMA red (mismo Wi-Fi/router).")
    print("  Pulsa Ctrl+C para detener.\n")
    try:
        while not stopped.wait(0.5):
            pass
    except KeyboardInterrupt:
        print()
    finally:
        proxy.stop()
    return 0


def _ask(prompt: str, default: Optional[str] = None) -> str:
    suffix = f" [{default}]" if default else ""
    try:
        value = input(f"{prompt}{suffix}: ").strip()
    except EOFError:
        raise KeyboardInterrupt
    return value or (default or "")


def _ask_server() -> Optional[tuple]:
    text = _ask("IP o dominio del servidor (ej. play.servidor.net o 1.2.3.4:19132)")
    if not text:
        return None
    try:
        return raknet.parse_address(text)
    except ValueError as exc:
        print(f"  {exc}")
        return None


def _test(host: str, port: int) -> None:
    print(f"  Probando {host}:{port}...")
    try:
        info, latency = raknet.query_server(host, port)
    except (OSError, TimeoutError) as exc:
        print(f"  No responde: {exc}")
        return
    print(f"  {info.name} | {info.level_name}")
    print(f"  Versión {info.version} (protocolo {info.protocol}) | "
          f"{info.players}/{info.max_players} jugadores | {latency:.0f} ms")


def _saved_menu() -> None:
    servers = config.load_servers()
    if not servers:
        print("  No tienes servidores guardados todavía.")
        return
    for i, s in enumerate(servers, 1):
        print(f"  {i}) {s.label}")
    choice = _ask("Número para conectar, 'b' + número para borrar (ej. b2), Enter para volver")
    if not choice:
        return
    delete = choice.lower().startswith("b")
    try:
        index = int(choice[1:] if delete else choice) - 1
        if index < 0:
            raise IndexError
        server = servers[index]
    except (ValueError, IndexError):
        print("  Opción no válida.")
        return
    if delete:
        servers.pop(index)
        config.save_servers(servers)
        print(f"  Borrado: {server.name}")
    else:
        run_proxy(server.host, server.port, server.name)


def interactive_menu() -> int:
    print(f"\n=== {APP_NAME} v{__version__} ===")
    print("Haz que un servidor externo aparezca como partida LAN en tu consola.\n")
    try:
        while True:
            print("1) Conectar a un servidor")
            print("2) Servidores guardados")
            print("3) Guardar un servidor")
            print("4) Probar un servidor")
            print("5) Salir")
            option = _ask("Elige una opción")
            print()
            if option == "1":
                addr = _ask_server()
                if addr:
                    name = _ask("Nombre para la partida LAN (Enter = el del servidor)")
                    run_proxy(addr[0], addr[1], name or None)
            elif option == "2":
                _saved_menu()
            elif option == "3":
                addr = _ask_server()
                if addr:
                    name = _ask("Nombre", addr[0])
                    servers = config.upsert_server(config.load_servers(),
                                                   config.SavedServer(name, addr[0], addr[1]))
                    config.save_servers(servers)
                    print(f"  Guardado: {name}")
            elif option == "4":
                addr = _ask_server()
                if addr:
                    _test(*addr)
            elif option in ("5", "q", "salir"):
                return 0
            else:
                print("  Opción no válida.")
            print()
    except KeyboardInterrupt:
        print()
        return 0
