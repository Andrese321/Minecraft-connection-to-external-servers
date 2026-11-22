package com.bedrocklanproxy.core

/** Lanzador manual para pruebas de extremo a extremo con un cliente/servidor Bedrock reales. */
fun main(args: Array<String>) {
    val p = LanProxy(args[0], args[1].toInt(), listenHost = args[2], listenPort = args[3].toInt(),
        customName = args.getOrNull(4), listener = { e, m -> println("[proxy-kotlin] $e $m") })
    p.start()
    Thread.sleep(args.getOrNull(5)?.toLong() ?: 20_000)
    p.stop()
}
