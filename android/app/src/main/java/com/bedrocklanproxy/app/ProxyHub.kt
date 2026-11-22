package com.bedrocklanproxy.app

import android.os.Handler
import android.os.Looper
import com.bedrocklanproxy.core.LanProxy
import com.bedrocklanproxy.core.ProxyEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet

/** Estado compartido entre el servicio (que ejecuta el proxy) y la pantalla. */
object ProxyHub {
    enum class Status { STOPPED, STARTING, ONLINE, BLIND, OFFLINE, ERROR }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val logLines = ArrayDeque<String>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    @Volatile var proxy: LanProxy? = null
    @Volatile var status: Status = Status.STOPPED
        private set
    @Volatile var lastError: String? = null
        private set
    /** Consolas enviadas al servidor con éxito desde que se inició la partida LAN. */
    @Volatile var transferred: Int = 0
        private set

    val log: List<String> get() = synchronized(logLines) { logLines.toList() }

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    fun setStatus(s: Status, error: String? = null) {
        if (s == Status.STARTING) transferred = 0
        status = s
        if (error != null) lastError = error
        notifyChanged()
    }

    fun append(message: String) {
        synchronized(logLines) {
            logLines.addLast("${timeFormat.format(Date())}  $message")
            while (logLines.size > 200) logLines.removeFirst()
        }
        notifyChanged()
    }

    /** Traduce los eventos del proxy a estado de la app. Se llama desde el hilo del proxy. */
    fun onProxyEvent(source: LanProxy, event: ProxyEvent, message: String) {
        append(message)
        if (source !== proxy) return // evento de un proxy antiguo: solo se registra
        when (event) {
            ProxyEvent.ONLINE -> setStatus(Status.ONLINE)
            ProxyEvent.OFFLINE -> setStatus(Status.OFFLINE)
            ProxyEvent.UNREACHABLE -> setStatus(Status.BLIND)
            ProxyEvent.TRANSFERRED -> { transferred++; notifyChanged() }
            // Un proxy que se detiene sin que nadie lo pidiera (seguía registrado) es un fallo.
            ProxyEvent.STOPPED -> {
                proxy = null
                setStatus(Status.ERROR, "El proxy se detuvo inesperadamente. Pulsa «Iniciar» de nuevo.")
            }
            // Los errores puntuales (p. ej. un ping fallido) solo se registran: el proxy sigue activo.
            else -> notifyChanged()
        }
    }

    fun notifyChanged() {
        main.post { listeners.forEach { it() } }
    }
}
