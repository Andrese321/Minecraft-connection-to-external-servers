# Bedrock LAN Proxy

Conecta tu **consola** (Xbox, PlayStation, Nintendo Switch) a **cualquier servidor
externo de Minecraft Bedrock**, aunque la consola no deje añadir servidores.

La idea es la misma que la de apps como *BedrockTogether*: escribes la IP del
servidor, pulsas un botón y en la consola aparece una **partida LAN**. Al entrar
en ella, la app te lleva al servidor real.

![Ventana principal](docs/captura.png)

*Captura hecha con un servidor de prueba local.*

> ⚠️ **Limitación actual:** desde la versión **26.60** de Minecraft Bedrock
> (octubre de 2026), las consolas buscan y abren las partidas LAN con un
> protocolo nuevo, *NetherNet* (WebRTC, puerto UDP 7551), en lugar de RakNet
> (puerto 19132). Esta app todavía solo habla RakNet, así que **las consolas
> con la versión 26.60 o posterior no ven la partida LAN**. Funciona con
> versiones hasta la 26.5x.

## Cómo funciona

La app de Android tiene dos modos:

- **Transferir (recomendado):** el teléfono hace de mini servidor. Cuando la
  consola entra en la partida LAN, le envía el paquete oficial *Transfer* de
  Minecraft con la IP y el puerto del servidor real. La consola se conecta
  directamente y **ya puedes cerrar la app**: solo hace falta para entrar. Si
  una consola ignora la transferencia, la app la desconecta con un aviso y en
  el siguiente intento usa el modo puente automáticamente.
- **Puente:** el teléfono reenvía todo el tráfico, así que debe seguir
  encendido mientras juegas. La versión de PC funciona siempre así.

Esquema del modo puente:

```
 Consola  ──(red local / Wi-Fi)──▶  Esta app (móvil o PC)  ──(internet)──▶  Servidor externo
   ▲  "¿hay partidas LAN?"                    │  responde: "sí, <nombre del servidor>"
   └──────────────────────────────────────────┘  y luego reenvía todo el tráfico
```

1. Cuando abres la pestaña **Amigos** en Minecraft, la consola busca partidas
   LAN en el puerto UDP **19132** de tu red local.
2. La app responde con el nombre, la versión y los jugadores del servidor real.
   Para eso le hace ping cada pocos segundos.
3. Cuando entras, cada paquete de la consola se reenvía al servidor y cada
   respuesta vuelve a la consola. Para el servidor, eres un jugador normal y tu
   cuenta de Xbox Live funciona igual.

No hace falta instalar nada en la consola ni tocar el router.

Hay dos versiones que hacen lo mismo:

- **📱 Android** (recomendada): el teléfono hace de puente. Ver [App Android](#-app-android).
- **💻 PC** (Windows, macOS, Linux): ver [Uso rápido en PC](#uso-rápido-en-pc).

## 📱 App Android

### Instalar

1. En GitHub, abre la pestaña **Actions** → el último run de *Pruebas y
   ejecutables* → sección **Artifacts** → descarga **BedrockLanProxy-Android**.
2. Es un `.zip`: ábrelo con la app *Archivos* del teléfono y saca
   `BedrockLanProxy.apk`.
3. Toca la APK para instalarla. Android pedirá permitir **instalar apps de
   origen desconocido** para tu navegador o gestor de archivos.
4. Requiere Android 8.0 o superior.

> La APK es de depuración y se firma con una clave distinta en cada
> compilación. Para instalar una versión nueva, desinstala antes la anterior.

### Usar

1. Conecta el teléfono y la consola a la **misma red Wi-Fi**. También vale
   conectar la consola al **punto de acceso** del teléfono.
2. Abre la app, escribe la IP o el dominio del servidor y pulsa
   **▶ Iniciar partida LAN**. Acepta el permiso de notificaciones.
3. Cuando el punto se ponga verde, abre Minecraft en la consola → **Jugar** →
   **Amigos**. El servidor aparece en *Partidas LAN*.
4. En modo **Transferir**, cuando la consola ya está en el servidor la app lo
   indica («ya puedes cerrar la app»). En modo **Puente** puedes apagar la
   pantalla: la app sigue funcionando y deja una notificación con un botón
   **Detener**.

Si el servidor no responde al ping (está apagado, la IP o el puerto están mal,
o es un servidor de Java Edition), la partida se anuncia igualmente a los
4 segundos para que puedas intentar entrar, y la app lo avisa.

La app también tiene servidores guardados (toca para rellenar, mantén pulsado
para borrar), **Probar servidor** y un registro de lo que pasa.

**Si la partida deja de verse con la pantalla apagada:** algunos fabricantes
(Xiaomi, Samsung, Huawei…) cierran apps en segundo plano. Ve a *Ajustes →
Aplicaciones → Bedrock LAN Proxy → Batería* y elige **Sin restricciones**.

## Uso rápido en PC

**Requisito:** el PC con el programa y la consola tienen que estar en **la misma red**
(el mismo router o Wi-Fi).

### Opción A: ejecutable (sin instalar Python)

Cada vez que se sube código a GitHub, el workflow *Pruebas y ejecutables* genera:

- `BedrockLanProxy.exe` para Windows,
- binarios para macOS y Linux.

Descárgalos desde la pestaña **Actions** del repositorio, en el último run,
sección *Artifacts*.

### Opción B: con Python 3.8 o superior

No necesita dependencias externas.

```bash
python run.py                       # abre la ventana
python run.py --cli                 # menú interactivo en la terminal
python run.py play.servidor.net     # conecta directamente, sin menús
python run.py 1.2.3.4:19150 -n "Server de mis amigos"
```

También se puede instalar con `pip install .` y ejecutar con `bedrock-lan-proxy`.

### En la ventana

1. Escribe la **IP o dominio** del servidor y el **puerto** (normalmente 19132).
2. Opcional: escribe el nombre que quieres ver en la lista LAN.
3. Pulsa **▶ Iniciar partida LAN**. Cuando el punto se ponga verde
   (*Partida LAN visible en la consola*), ya está.
4. En la consola, abre Minecraft → **Jugar** → pestaña **Amigos**. El servidor
   aparece en *Partidas LAN*.

Con **★ Guardar** el servidor queda en la lista de favoritos (doble clic para
conectar). Los favoritos se guardan en `~/.bedrock_lan_proxy/servidores.json`.
**Probar servidor** comprueba si el servidor responde y muestra su versión y
jugadores.

### Menú de terminal (`--cli`)

```
=== Bedrock LAN Proxy v1.0.0 ===
1) Conectar a un servidor
2) Servidores guardados
3) Guardar un servidor
4) Probar un servidor
5) Salir
```

## Problemas frecuentes

| Problema | Solución |
|---|---|
| No aparece la partida LAN | Comprueba que la consola y el teléfono o PC están en la misma red. Las redes de invitados y algunos routers con «aislamiento de clientes» o «AP isolation» bloquean el descubrimiento LAN. |
| Windows pregunta por el firewall | Pulsa **Permitir** en *redes privadas*. Si lo rechazaste, ve a *Firewall de Windows → Permitir una aplicación* y activa la app (o `python.exe`). |
| «No se pudo abrir el puerto UDP 19132» | Minecraft u otro programa está usando ese puerto en el mismo dispositivo. Cierra Minecraft en el teléfono o PC mientras usas la app. |
| El punto se queda en naranja o rojo | El servidor no responde: revisa la IP y el puerto con **Probar servidor**. |
| «Versión desactualizada» al entrar | El servidor usa otra versión de Minecraft que tu consola. Actualiza el juego o busca un servidor compatible. |
| Expulsión justo al entrar en un servidor con puerto distinto de 19132 | Algunos servidores (poco comunes) comprueban que el puerto de conexión coincida con el suyo. Prueba la dirección alternativa del servidor en el puerto 19132, si la tiene. |

## Estructura del proyecto

```
bedrock_lan_proxy/
  raknet.py    # paquetes RakNet de descubrimiento (ping/pong) y MOTD
  proxy.py     # el proxy: anuncia la partida LAN y reenvía el tráfico UDP
  gui.py       # ventana (tkinter)
  cli.py       # menú interactivo y modo directo
  config.py    # servidores guardados
tests/         # pruebas con un servidor Bedrock simulado
run.py         # lanzador (también lo usa PyInstaller)

android/
  core/        # el mismo proxy en Kotlin puro (RakNet.kt, LanProxy.kt) + pruebas
  app/         # app Android: pantalla, servicio en primer plano y notificación
```

Para abrir la versión Android, abre la carpeta `android/` en Android Studio.
Para compilar desde la terminal: `cd android && ./gradlew :app:assembleDebug`.

## Pruebas

```bash
python -m unittest discover -v           # versión PC
cd android && ./gradlew :core:test        # núcleo de la versión Android
```

Las pruebas levantan un servidor Bedrock simulado en local y comprueban dos
cosas: que la consola recibe la partida LAN con el nombre y los puertos
correctos, y que el tráfico de juego se reenvía en los dos sentidos (con varias
consolas a la vez, desconexiones y caídas del servidor).

## Próximos pasos posibles

- Publicar la APK firmada como *Release* en GitHub, para descargarla
  directamente y actualizar sin desinstalar.
- Lista de servidores destacados precargada.
- Versión para iPhone.
