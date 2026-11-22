"""Punto de entrada: ``python -m bedrock_lan_proxy``.

Sin argumentos abre la ventana; si no hay entorno gráfico, el menú de terminal.
"""

from __future__ import annotations

import argparse
import sys

from . import APP_NAME, __version__, raknet


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        prog="bedrock-lan-proxy",
        description=f"{APP_NAME}: haz que un servidor Bedrock externo aparezca como partida LAN en tu consola.",
    )
    p.add_argument("servidor", nargs="?", help="IP o dominio del servidor (opcional: host:puerto)")
    p.add_argument("-p", "--puerto", type=int, default=raknet.DEFAULT_PORT, help="puerto del servidor (19132)")
    p.add_argument("-n", "--nombre", help="nombre que se mostrará en la lista LAN")
    p.add_argument("--puerto-local", type=int, default=raknet.DEFAULT_PORT,
                   help="puerto UDP local donde escuchar (por defecto 19132; la consola solo busca en 19132)")
    p.add_argument("--cli", action="store_true", help="usar el menú de terminal en vez de la ventana")
    p.add_argument("--version", action="version", version=f"{APP_NAME} {__version__}")
    return p


def main(argv=None) -> int:
    args = build_parser().parse_args(argv)
    from . import cli

    if args.servidor:
        try:
            host, port = raknet.parse_address(args.servidor, args.puerto)
        except ValueError as exc:
            print(f"Error: {exc}", file=sys.stderr)
            return 2
        return cli.run_proxy(host, port, args.nombre, args.puerto_local)

    if not args.cli:
        try:
            from . import gui
            return gui.main()
        except Exception as exc:  # tkinter ausente o sin pantalla
            print(f"(No se pudo abrir la ventana: {exc}. Usando el menú de terminal.)")
    return cli.interactive_menu()


if __name__ == "__main__":
    sys.exit(main())
