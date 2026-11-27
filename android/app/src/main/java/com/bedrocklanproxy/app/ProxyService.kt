package com.bedrocklanproxy.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.bedrocklanproxy.core.LanProxy
import com.bedrocklanproxy.core.NetherNetIdentity
import com.bedrocklanproxy.core.ProxyMode
import com.bedrocklanproxy.core.ProxyException
import com.bedrocklanproxy.core.RakNet
import kotlin.concurrent.thread

/**
 * Servicio en primer plano: mantiene el proxy vivo con la pantalla apagada o la
 * app en segundo plano, y muestra una notificación con un botón para detenerlo.
 */
class ProxyService : Service() {
    private var multicastLock: WifiManager.MulticastLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val hubListener: () -> Unit = { updateNotification() }

    /** Cambia cada vez que se arranca o se detiene: invalida arranques que quedaron a medias. */
    @Volatile private var generation = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val host = intent.getStringExtra(EXTRA_HOST).orEmpty()
                val port = intent.getIntExtra(EXTRA_PORT, RakNet.DEFAULT_PORT)
                val name = intent.getStringExtra(EXTRA_NAME)
                val mode = if (intent.getBooleanExtra(EXTRA_TRANSFER, true)) ProxyMode.TRANSFER else ProxyMode.RELAY
                goForeground("Conectando con $host…")
                startProxy(host, port, name, mode)
            }
            ACTION_STOP -> shutdown()
            else -> {
                // El sistema reinició el servicio sin datos: no hay nada que reanudar.
                if (ProxyHub.proxy == null) shutdown()
            }
        }
        return START_NOT_STICKY
    }

    private fun startProxy(host: String, port: Int, name: String?, mode: ProxyMode) {
        stopProxy()
        val myGeneration = ++generation
        ProxyHub.setStatus(ProxyHub.Status.STARTING)
        ProxyHub.addListener(hubListener)
        acquireLocks()
        // start() resuelve DNS: Android no permite red en el hilo principal.
        thread(name = "ProxyStart") {
            lateinit var proxy: LanProxy
            proxy = LanProxy(
                host, port, customName = name, mode = mode,
                listener = { e, m -> ProxyHub.onProxyEvent(proxy, e, m) },
                rtcBackend = AndroidRtcBackend(this),
                netherNetIdentity = NetherNetIdentity.loadOrCreate(java.io.File(filesDir, "nethernet-identity.key")),
            )
            synchronized(this) {
                if (myGeneration != generation) return@thread
                ProxyHub.proxy = proxy // antes de start() para no perder sus primeros eventos
            }
            try {
                proxy.start()
            } catch (e: ProxyException) {
                synchronized(this) { if (ProxyHub.proxy === proxy) ProxyHub.proxy = null }
                if (myGeneration != generation) return@thread
                ProxyHub.append("ERROR: ${e.message}")
                ProxyHub.setStatus(ProxyHub.Status.ERROR, e.message)
                shutdown(keepStatus = true)
                return@thread
            }
            // Si pulsaron «Detener» mientras arrancaba, lo paramos ahora.
            val cancelled = synchronized(this) { myGeneration != generation }
            if (cancelled) proxy.stop()
        }
    }

    private fun stopProxy() {
        val p = synchronized(this) {
            generation++
            ProxyHub.proxy.also { ProxyHub.proxy = null }
        }
        p?.stop()
    }

    private fun shutdown(keepStatus: Boolean = false) {
        thread(name = "ProxyStop") {
            stopProxy()
            if (!keepStatus) ProxyHub.setStatus(ProxyHub.Status.STOPPED)
        }.join(4000)
        ProxyHub.removeListener(hubListener)
        releaseLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        ProxyHub.removeListener(hubListener)
        if (ProxyHub.proxy != null) {
            thread { stopProxy(); ProxyHub.setStatus(ProxyHub.Status.STOPPED) }
        }
        releaseLocks()
        super.onDestroy()
    }

    // ------------------------------------------------------------ bloqueos

    private fun acquireLocks() {
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (multicastLock == null) {
            // Sin esto muchos teléfonos descartan los paquetes de difusión de la consola.
            multicastLock = wifi.createMulticastLock("BedrockLanProxy:multicast").apply {
                setReferenceCounted(false); acquire()
            }
        }
        if (wifiLock == null) {
            @Suppress("DEPRECATION")
            val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wifi.createWifiLock(mode, "BedrockLanProxy:wifi").apply {
                setReferenceCounted(false); acquire()
            }
        }
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BedrockLanProxy:cpu").apply {
                setReferenceCounted(false); acquire()
            }
        }
    }

    private fun releaseLocks() {
        multicastLock?.takeIf { it.isHeld }?.release(); multicastLock = null
        wifiLock?.takeIf { it.isHeld }?.release(); wifiLock = null
        wakeLock?.takeIf { it.isHeld }?.release(); wakeLock = null
    }

    // ------------------------------------------------------------ notificación

    private fun goForeground(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification() {
        val proxy = ProxyHub.proxy
        val info = proxy?.serverInfo
        val text = when (ProxyHub.status) {
            ProxyHub.Status.ONLINE ->
                "Partida LAN visible: ${proxy?.customName ?: info?.name ?: ""} · ${proxy?.clientCount ?: 0} consola(s)"
            ProxyHub.Status.BLIND -> "Partida LAN anunciada (el servidor no responde al ping)"
            ProxyHub.Status.OFFLINE -> "El servidor dejó de responder"
            ProxyHub.Status.STARTING -> "Conectando…"
            ProxyHub.Status.ERROR -> "Error: revisa la app"
            ProxyHub.Status.STOPPED -> return
        }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Partida LAN activa", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Se muestra mientras la consola puede ver el servidor como partida LAN"
                },
            )
        }
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ProxyService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Detener", stop).build())
            .build()
    }

    companion object {
        const val ACTION_START = "com.bedrocklanproxy.app.START"
        const val ACTION_STOP = "com.bedrocklanproxy.app.STOP"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_NAME = "name"
        const val EXTRA_TRANSFER = "transfer"
        private const val CHANNEL_ID = "proxy"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context, host: String, port: Int, name: String?, transfer: Boolean) {
            val intent = Intent(context, ProxyService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_HOST, host)
                .putExtra(EXTRA_PORT, port)
                .putExtra(EXTRA_NAME, name)
                .putExtra(EXTRA_TRANSFER, transfer)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ProxyService::class.java).setAction(ACTION_STOP))
        }
    }
}
