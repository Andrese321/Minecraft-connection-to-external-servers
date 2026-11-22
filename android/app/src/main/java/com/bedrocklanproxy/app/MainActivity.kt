package com.bedrocklanproxy.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.bedrocklanproxy.core.RakNet
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var host: EditText
    private lateinit var port: EditText
    private lateinit var name: EditText
    private lateinit var btnStart: Button
    private lateinit var btnTest: Button
    private lateinit var dot: View
    private lateinit var status: TextView
    private lateinit var info: TextView
    private lateinit var ips: TextView
    private lateinit var savedList: LinearLayout
    private lateinit var log: TextView
    private lateinit var store: ServerStore

    private val refresher = Runnable { refreshLoop() }
    private var lastActive: Boolean? = null
    private val hubListener: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setupEdgeToEdge()
        store = ServerStore(this)

        host = findViewById(R.id.host)
        port = findViewById(R.id.port)
        name = findViewById(R.id.name)
        btnStart = findViewById(R.id.btnStart)
        btnTest = findViewById(R.id.btnTest)
        dot = findViewById(R.id.dot)
        status = findViewById(R.id.status)
        info = findViewById(R.id.info)
        ips = findViewById(R.id.ips)
        savedList = findViewById(R.id.savedList)
        log = findViewById(R.id.log)

        if (savedInstanceState == null) {
            host.setText(store.lastHost)
            port.setText(store.lastPort.toString())
            name.setText(store.lastName)
        }

        btnStart.setOnClickListener { toggle() }
        btnTest.setOnClickListener { testServer() }
        findViewById<Button>(R.id.btnSave).setOnClickListener { saveCurrent() }

        renderSaved()
        askNotificationPermission()
    }

    override fun onResume() {
        super.onResume()
        ProxyHub.addListener(hubListener)
        render()
        refreshLoop()
    }

    override fun onPause() {
        ProxyHub.removeListener(hubListener)
        status.removeCallbacks(refresher)
        super.onPause()
    }

    private fun refreshLoop() {
        render()
        status.postDelayed(refresher, 1000)
    }

    // ------------------------------------------------------------ acciones

    private fun readForm(): Triple<String, Int, String?>? {
        val defaultPort = port.text.toString().toIntOrNull() ?: RakNet.DEFAULT_PORT
        return try {
            val (h, p) = RakNet.parseAddress(host.text.toString(), defaultPort)
            host.setText(h)
            port.setText(p.toString())
            Triple(h, p, name.text.toString().trim().ifEmpty { null })
        } catch (e: IllegalArgumentException) {
            toast(if (host.text.isBlank()) "Escribe la IP o el dominio del servidor" else "Dirección no válida: ${e.message}")
            null
        }
    }

    private fun isActive() = ProxyHub.status != ProxyHub.Status.STOPPED && ProxyHub.status != ProxyHub.Status.ERROR

    private fun toggle() {
        if (isActive()) {
            ProxyService.stop(this)
            return
        }
        val (h, p, n) = readForm() ?: return
        store.lastHost = h
        store.lastPort = p
        store.lastName = n.orEmpty()
        ProxyHub.append("Iniciando partida LAN para $h:$p…")
        ProxyService.start(this, h, p, n)
    }

    private fun testServer() {
        val (h, p, _) = readForm() ?: return
        btnTest.isEnabled = false
        ProxyHub.append("Probando $h:$p…")
        thread {
            val message = try {
                val r = RakNet.query(h, p)
                "✔ ${r.info.name} | ${r.info.levelName} | versión ${r.info.version} | " +
                    "${r.info.players}/${r.info.maxPlayers} jugadores | ${r.latencyMs} ms"
            } catch (e: Exception) {
                "✖ $h no responde: ${e.message}"
            }
            ProxyHub.append(message)
            runOnUiThread {
                btnTest.isEnabled = true
                if (!isActive()) info.text = message
            }
        }
    }

    private fun saveCurrent() {
        val (h, p, n) = readForm() ?: return
        store.upsert(SavedServer(n ?: h, h, p))
        renderSaved()
        toast("Guardado")
    }

    private fun useSaved(s: SavedServer, start: Boolean) {
        if (isActive()) {
            toast("Detén la partida actual primero")
            return
        }
        host.setText(s.host)
        port.setText(s.port.toString())
        name.setText(if (s.name != s.host) s.name else "")
        if (start) toggle()
    }

    private fun confirmDelete(s: SavedServer) {
        AlertDialog.Builder(this)
            .setTitle("Eliminar servidor")
            .setMessage("¿Eliminar «${s.name}» de tus servidores guardados?")
            .setPositiveButton("Eliminar") { _, _ -> store.remove(s); renderSaved() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ------------------------------------------------------------ pintar

    private fun render() {
        val st = ProxyHub.status
        val proxy = ProxyHub.proxy
        val (label, color) = when (st) {
            ProxyHub.Status.STOPPED -> "Detenido" to R.color.status_grey
            ProxyHub.Status.STARTING -> "Contactando con el servidor…" to R.color.status_orange
            ProxyHub.Status.ONLINE -> "Partida LAN visible en la consola" to R.color.status_green
            ProxyHub.Status.OFFLINE -> "El servidor no responde" to R.color.status_red
            ProxyHub.Status.ERROR -> "Error" to R.color.status_red
        }
        status.setIfChanged(label)
        dot.backgroundTintList = ColorStateList.valueOf(getColor(color))

        val active = isActive()
        if (active != lastActive) {
            lastActive = active
            btnStart.setText(if (active) R.string.stop else R.string.start)
            btnStart.backgroundTintList =
                ColorStateList.valueOf(getColor(if (active) R.color.status_red else R.color.status_green))
            host.isEnabled = !active
            port.isEnabled = !active
            name.isEnabled = !active
        }

        val serverInfo = proxy?.serverInfo
        when {
            st == ProxyHub.Status.ONLINE && serverInfo != null -> {
                val ping = proxy.latencyMs?.let { " · $it ms" } ?: ""
                info.setIfChanged(
                    "${serverInfo.name} — ${serverInfo.version} — " +
                        "${serverInfo.players}/${serverInfo.maxPlayers} jugadores$ping\n" +
                        "${proxy.clientCount} consola(s) conectada(s)",
                )
            }
            st == ProxyHub.Status.ERROR -> info.setIfChanged(ProxyHub.lastError ?: "")
            st == ProxyHub.Status.STARTING -> info.setIfChanged("Haciendo ping a ${host.text}…")
            st == ProxyHub.Status.STOPPED && info.text.isNullOrEmpty() ->
                info.setIfChanged("Escribe la IP del servidor y pulsa «Iniciar partida LAN».")
        }

        val localIps = NetUtils.localIps()
        ips.setIfChanged(if (localIps.isEmpty()) {
            "⚠ No estás conectado a ninguna red Wi-Fi. Conecta el teléfono a la misma red que la consola."
        } else {
            "IP de este teléfono: ${localIps.joinToString(", ")}"
        })

        val lines = ProxyHub.log
        log.setIfChanged(if (lines.isEmpty()) "—" else lines.takeLast(60).joinToString("\n"))
    }

    private fun renderSaved() {
        savedList.removeAllViews()
        val servers = store.load()
        if (servers.isEmpty()) {
            savedList.addView(TextView(this).apply {
                text = getString(R.string.saved_empty)
                setTextColor(getColor(R.color.text_secondary))
                setPadding(dp(14), dp(10), dp(14), dp(10))
            })
            return
        }
        for (s in servers) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(8), dp(4), dp(8))
                isClickable = true
                setBackgroundResource(android.R.drawable.list_selector_background)
                setOnClickListener { useSaved(s, start = false) }
                setOnLongClickListener { confirmDelete(s); true }
            }
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(this).apply {
                text = s.name
                textSize = 15f
                setTextColor(getColor(R.color.text))
            })
            texts.addView(TextView(this).apply {
                text = s.subtitle
                textSize = 12f
                setTextColor(getColor(R.color.text_secondary))
            })
            row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(Button(this, null, android.R.attr.borderlessButtonStyle).apply {
                text = "Conectar"
                setOnClickListener { useSaved(s, start = true) }
            })
            savedList.addView(row)
        }
        savedList.addView(TextView(this).apply {
            text = "Toca para rellenar · mantén pulsado para eliminar"
            textSize = 11f
            setTextColor(getColor(R.color.text_secondary))
            setPadding(dp(14), dp(2), dp(14), dp(6))
        })
    }

    // ------------------------------------------------------------ utilidades

    /**
     * Desde Android 15 la app se dibuja detrás de la barra de estado y la de navegación.
     * Lo activamos en todas las versiones y dejamos el hueco exacto: la cabecera se
     * estira bajo la barra de estado y el contenido evita barras, recortes y teclado.
     */
    @Suppress("DEPRECATION")
    private fun setupEdgeToEdge() {
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = getColor(R.color.background)
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.setSystemBarsAppearance(
                if (night) 0 else WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
            )
        } else {
            var flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            if (!night) flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            window.decorView.systemUiVisibility = flags
        }

        val header = findViewById<View>(R.id.header)
        val scroll = findViewById<View>(R.id.scroll)
        val headerPad = dp(14)
        val sidePad = dp(16)
        findViewById<View>(R.id.root).setOnApplyWindowInsetsListener { _, insets ->
            val left: Int
            val top: Int
            val right: Int
            val bottom: Int
            if (Build.VERSION.SDK_INT >= 30) {
                val i = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime(),
                )
                left = i.left; top = i.top; right = i.right; bottom = i.bottom
            } else {
                left = insets.systemWindowInsetLeft; top = insets.systemWindowInsetTop
                right = insets.systemWindowInsetRight; bottom = insets.systemWindowInsetBottom
            }
            header.setPadding(sidePad + left, headerPad + top, sidePad + right, headerPad)
            scroll.setPadding(left, 0, right, bottom)
            insets
        }
    }

    /** Cambiar el texto provoca un nuevo cálculo del diseño: solo lo hacemos si cambió. */
    private fun TextView.setIfChanged(value: CharSequence) {
        if (text.toString() != value.toString()) text = value
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
