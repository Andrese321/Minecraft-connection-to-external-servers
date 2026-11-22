"""Interfaz gráfica (tkinter, incluido con Python) estilo "un botón y listo"."""

from __future__ import annotations

import queue
import threading
import time
import tkinter as tk
from tkinter import messagebox, ttk
from typing import List, Optional

from . import APP_NAME, __version__, config, raknet
from .proxy import (EV_CLIENT_JOIN, EV_CLIENT_LEAVE, EV_ERROR, EV_OFFLINE,
                    EV_ONLINE, EV_STOPPED, LanProxy, ProxyError)

GREEN, ORANGE, RED, GREY = "#2e9d43", "#d98a00", "#c62828", "#777777"


class App:
    def __init__(self, root: tk.Tk) -> None:
        self.root = root
        self.events: "queue.Queue[tuple]" = queue.Queue()
        self.proxy: Optional[LanProxy] = None
        self.servers: List[config.SavedServer] = config.load_servers()

        root.title(f"{APP_NAME} v{__version__}")
        root.minsize(560, 560)
        root.protocol("WM_DELETE_WINDOW", self.on_close)

        self.host_var = tk.StringVar()
        self.port_var = tk.StringVar(value=str(raknet.DEFAULT_PORT))
        self.name_var = tk.StringVar()
        self.status_var = tk.StringVar(value="Detenido")
        self.info_var = tk.StringVar(value="Escribe la IP del servidor y pulsa «Iniciar partida LAN».")

        self._build()
        self._refresh_saved()
        self.root.after(150, self._poll_events)

    # ------------------------------------------------------------ layout

    def _build(self) -> None:
        pad = {"padx": 10, "pady": 4}
        main = ttk.Frame(self.root, padding=12)
        main.pack(fill="both", expand=True)
        main.columnconfigure(1, weight=1)

        title = ttk.Label(main, text="Conecta tu consola a cualquier servidor Bedrock",
                          font=("TkDefaultFont", 13, "bold"))
        title.grid(row=0, column=0, columnspan=4, sticky="w", pady=(0, 8))

        ttk.Label(main, text="IP o dominio:").grid(row=1, column=0, sticky="w", **pad)
        self.host_entry = ttk.Entry(main, textvariable=self.host_var)
        self.host_entry.grid(row=1, column=1, sticky="ew", **pad)
        ttk.Label(main, text="Puerto:").grid(row=1, column=2, sticky="e")
        self.port_entry = ttk.Entry(main, textvariable=self.port_var, width=7)
        self.port_entry.grid(row=1, column=3, sticky="w", **pad)

        ttk.Label(main, text="Nombre LAN (opcional):").grid(row=2, column=0, sticky="w", **pad)
        self.name_entry = ttk.Entry(main, textvariable=self.name_var)
        self.name_entry.grid(row=2, column=1, columnspan=3, sticky="ew", **pad)

        buttons = ttk.Frame(main)
        buttons.grid(row=3, column=0, columnspan=4, sticky="ew", pady=8)
        self.start_btn = ttk.Button(buttons, text="▶  Iniciar partida LAN", command=self.toggle)
        self.start_btn.pack(side="left", ipadx=10, ipady=4)
        self.test_btn = ttk.Button(buttons, text="Probar servidor", command=self.test_server)
        self.test_btn.pack(side="left", padx=6)
        ttk.Button(buttons, text="★ Guardar", command=self.save_current).pack(side="left")

        status = ttk.Frame(main)
        status.grid(row=4, column=0, columnspan=4, sticky="ew", padx=10)
        self.dot = tk.Canvas(status, width=14, height=14, highlightthickness=0)
        self.dot_id = self.dot.create_oval(2, 2, 12, 12, fill=GREY, outline="")
        self.dot.pack(side="left")
        ttk.Label(status, textvariable=self.status_var,
                  font=("TkDefaultFont", 10, "bold")).pack(side="left", padx=6)
        ttk.Label(main, textvariable=self.info_var, wraplength=520, foreground="#444").grid(
            row=5, column=0, columnspan=4, sticky="w", padx=10, pady=(2, 8))

        saved = ttk.LabelFrame(main, text="Servidores guardados (doble clic para usar)", padding=6)
        saved.grid(row=6, column=0, columnspan=4, sticky="nsew", pady=4)
        saved.columnconfigure(0, weight=1)
        self.saved_list = tk.Listbox(saved, height=5, activestyle="none")
        self.saved_list.grid(row=0, column=0, sticky="nsew")
        self.saved_list.bind("<Double-Button-1>", lambda e: self.use_saved(start=True))
        self.saved_list.bind("<<ListboxSelect>>", lambda e: self.use_saved(start=False))
        side = ttk.Frame(saved)
        side.grid(row=0, column=1, sticky="ns", padx=(6, 0))
        ttk.Button(side, text="Conectar", command=lambda: self.use_saved(start=True)).pack(fill="x")
        ttk.Button(side, text="Eliminar", command=self.delete_saved).pack(fill="x", pady=4)

        log_frame = ttk.LabelFrame(main, text="Registro", padding=6)
        log_frame.grid(row=7, column=0, columnspan=4, sticky="nsew", pady=4)
        log_frame.columnconfigure(0, weight=1)
        log_frame.rowconfigure(0, weight=1)
        self.log = tk.Text(log_frame, height=8, state="disabled", wrap="word")
        self.log.grid(row=0, column=0, sticky="nsew")
        scroll = ttk.Scrollbar(log_frame, command=self.log.yview)
        scroll.grid(row=0, column=1, sticky="ns")
        self.log.configure(yscrollcommand=scroll.set)
        main.rowconfigure(6, weight=1)
        main.rowconfigure(7, weight=2)

        ttk.Label(
            main,
            text=(f"IP de este equipo: {raknet.local_ip()}  ·  La consola debe estar en la misma red "
                  "Wi-Fi. En Minecraft: Jugar → Amigos → partidas LAN."),
            foreground=GREY, wraplength=540,
        ).grid(row=8, column=0, columnspan=4, sticky="w", pady=(6, 0))

        self.host_entry.focus_set()
        self.root.bind("<Return>", lambda e: self.toggle() if not self._running() else None)

    # ------------------------------------------------------------ helpers

    def _running(self) -> bool:
        return self.proxy is not None and self.proxy.running

    def _set_status(self, text: str, color: str) -> None:
        self.status_var.set(text)
        self.dot.itemconfigure(self.dot_id, fill=color)

    def _append_log(self, text: str) -> None:
        self.log.configure(state="normal")
        self.log.insert("end", f"{time.strftime('%H:%M:%S')}  {text}\n")
        self.log.see("end")
        self.log.configure(state="disabled")

    def _read_form(self) -> Optional[tuple]:
        host_text = self.host_var.get().strip()
        if not host_text:
            messagebox.showwarning(APP_NAME, "Escribe la IP o el dominio del servidor.")
            return None
        try:
            default_port = int(self.port_var.get() or raknet.DEFAULT_PORT)
            host, port = raknet.parse_address(host_text, default_port)
        except ValueError as exc:
            messagebox.showwarning(APP_NAME, f"Dirección no válida: {exc}")
            return None
        self.host_var.set(host)
        self.port_var.set(str(port))
        return host, port, self.name_var.get().strip() or None

    def _set_form_enabled(self, enabled: bool) -> None:
        state = "normal" if enabled else "disabled"
        for widget in (self.host_entry, self.port_entry, self.name_entry):
            widget.configure(state=state)

    # ------------------------------------------------------------ acciones

    def toggle(self) -> None:
        if self._running():
            self.stop()
        else:
            self.start()

    def start(self) -> None:
        form = self._read_form()
        if not form:
            return
        host, port, name = form
        self.proxy = LanProxy(host, port, custom_name=name,
                              on_event=lambda k, m: self.events.put(("proxy", k, m)))
        try:
            self.proxy.start()
        except ProxyError as exc:
            self.proxy = None
            self._append_log(f"ERROR: {exc}")
            messagebox.showerror(APP_NAME, str(exc))
            return
        self._set_form_enabled(False)
        self.start_btn.configure(text="■  Detener")
        self._set_status("Contactando con el servidor…", ORANGE)
        self.info_var.set(f"Haciendo ping a {host}:{port}…")

    def stop(self) -> None:
        if self.proxy is not None:
            proxy, self.proxy = self.proxy, None
            proxy.stop()  # síncrono (<0,3 s) para liberar el puerto antes de reiniciar
        self._set_form_enabled(True)
        self.start_btn.configure(text="▶  Iniciar partida LAN")
        self._set_status("Detenido", GREY)

    def test_server(self) -> None:
        form = self._read_form()
        if not form:
            return
        host, port, _ = form
        self.test_btn.configure(state="disabled")
        self._append_log(f"Probando {host}:{port}…")

        def worker() -> None:
            try:
                info, latency = raknet.query_server(host, port)
                self.events.put(("test_ok", info, latency))
            except Exception as exc:  # noqa: BLE001
                self.events.put(("test_fail", host, str(exc)))

        threading.Thread(target=worker, daemon=True).start()

    def save_current(self) -> None:
        form = self._read_form()
        if not form:
            return
        host, port, name = form
        server = config.SavedServer(name or host, host, port)
        self.servers = config.upsert_server(self.servers, server)
        try:
            config.save_servers(self.servers)
        except OSError as exc:
            messagebox.showerror(APP_NAME, f"No se pudo guardar: {exc}")
            return
        self._refresh_saved()
        self._append_log(f"Guardado: {server.label}")

    def _selected(self) -> Optional[config.SavedServer]:
        sel = self.saved_list.curselection()
        return self.servers[sel[0]] if sel else None

    def use_saved(self, start: bool) -> None:
        server = self._selected()
        if server is None or self._running():
            return
        self.host_var.set(server.host)
        self.port_var.set(str(server.port))
        self.name_var.set(server.name if server.name != server.host else "")
        if start:
            self.start()

    def delete_saved(self) -> None:
        server = self._selected()
        if server is None:
            return
        self.servers = [s for s in self.servers if s is not server]
        config.save_servers(self.servers)
        self._refresh_saved()

    def _refresh_saved(self) -> None:
        self.saved_list.delete(0, "end")
        for s in self.servers:
            self.saved_list.insert("end", s.label)

    # ------------------------------------------------------------ eventos

    def _poll_events(self) -> None:
        try:
            while True:
                self._handle(self.events.get_nowait())
        except queue.Empty:
            pass
        if self._running() and self.proxy and self.proxy.online and self.proxy.server_info:
            info = self.proxy.server_info
            ping = f" · {self.proxy.latency_ms:.0f} ms" if self.proxy.latency_ms else ""
            consoles = self.proxy.client_count
            self.info_var.set(
                f"{info.name} — {info.version} — {info.players}/{info.max_players} jugadores{ping}"
                f" · {consoles} consola(s) conectada(s)")
        self.root.after(250, self._poll_events)

    def _handle(self, event: tuple) -> None:
        kind = event[0]
        if kind == "proxy":
            _, ev, message = event
            self._append_log(message)
            if ev == EV_ONLINE:
                self._set_status("Partida LAN visible en la consola", GREEN)
            elif ev == EV_OFFLINE:
                self._set_status("Servidor sin respuesta", RED)
            elif ev in (EV_CLIENT_JOIN, EV_CLIENT_LEAVE):
                pass
            elif ev == EV_ERROR:
                self._set_status("Error (mira el registro)", RED)
            elif ev == EV_STOPPED and self.proxy is None:
                self._set_status("Detenido", GREY)
        elif kind == "test_ok":
            _, info, latency = event
            self.test_btn.configure(state="normal")
            text = (f"✔ {info.name} | {info.level_name} | versión {info.version} | "
                    f"{info.players}/{info.max_players} jugadores | {latency:.0f} ms")
            self._append_log(text)
            if not self._running():
                self.info_var.set(text)
        elif kind == "test_fail":
            _, host, err = event
            self.test_btn.configure(state="normal")
            self._append_log(f"✖ {host} no responde: {err}")
            if not self._running():
                self.info_var.set(f"✖ {host} no responde. Revisa la IP/puerto.")

    def on_close(self) -> None:
        if self.proxy is not None:
            self.proxy.stop(timeout=1.0)
        self.root.destroy()


def main() -> int:
    root = tk.Tk()
    try:
        ttk.Style(root).theme_use("vista" if root.tk.call("tk", "windowingsystem") == "win32" else "clam")
    except tk.TclError:
        pass
    App(root)
    root.mainloop()
    return 0
