package com.bedrocklanproxy.app

import java.net.Inet4Address
import java.net.NetworkInterface

object NetUtils {
    /** IPs IPv4 de la red local de este teléfono (Wi-Fi y punto de acceso). */
    fun localIps(): List<String> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress }
            .mapNotNull { it.hostAddress }
            .distinct()
    } catch (_: Exception) {
        emptyList()
    }
}
