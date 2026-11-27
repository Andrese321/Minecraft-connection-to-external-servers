package com.bedrocklanproxy.core

/**
 * Lanzador manual para pruebas de extremo a extremo con un cliente/servidor Bedrock reales.
 * Argumentos: host puerto hostLocal puertoLocal [nombre] [ms] [relay|transfer]
 */
fun main(args: Array<String>) {
    val mode = if (args.getOrNull(6) == "transfer") ProxyMode.TRANSFER else ProxyMode.RELAY
    val p = LanProxy(
        args[0], args[1].toInt(), listenHost = args[2], listenPort = args[3].toInt(),
        customName = args.getOrNull(4), listener = { e, m -> println("[proxy-kotlin] $e $m"); System.out.flush() }, mode = mode,
        rtcBackend = JvmRtcBackend(),
    )
    p.start()
    Thread.sleep(args.getOrNull(5)?.toLong() ?: 20_000)
    p.stop()
}
