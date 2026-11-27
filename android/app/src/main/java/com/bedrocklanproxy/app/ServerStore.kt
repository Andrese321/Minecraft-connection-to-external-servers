package com.bedrocklanproxy.app

import android.content.Context
import com.bedrocklanproxy.core.RakNet
import org.json.JSONArray
import org.json.JSONObject

data class SavedServer(val name: String, val host: String, val port: Int = RakNet.DEFAULT_PORT) {
    val subtitle: String get() = if (port == RakNet.DEFAULT_PORT) host else "$host:$port"
}

/** Servidores favoritos y último servidor usado, en SharedPreferences. */
class ServerStore(context: Context) {
    private val prefs = context.getSharedPreferences("servidores", Context.MODE_PRIVATE)

    fun load(): MutableList<SavedServer> {
        val out = mutableListOf<SavedServer>()
        val arr = try { JSONArray(prefs.getString(KEY_SERVERS, "[]")) } catch (_: Exception) { JSONArray() }
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val host = o.optString("host")
            if (host.isBlank()) continue
            out += SavedServer(o.optString("name", host), host, o.optInt("port", RakNet.DEFAULT_PORT))
        }
        return out
    }

    fun save(servers: List<SavedServer>) {
        val arr = JSONArray()
        servers.forEach { arr.put(JSONObject().put("name", it.name).put("host", it.host).put("port", it.port)) }
        prefs.edit().putString(KEY_SERVERS, arr.toString()).apply()
    }

    /** Añade el servidor o reemplaza el que tenga el mismo host y puerto. */
    fun upsert(server: SavedServer): MutableList<SavedServer> {
        val list = load().filterNot { it.host.equals(server.host, ignoreCase = true) && it.port == server.port }
            .toMutableList()
        list += server
        save(list)
        return list
    }

    fun remove(server: SavedServer): MutableList<SavedServer> {
        val list = load().filterNot { it == server }.toMutableList()
        save(list)
        return list
    }

    var lastHost: String
        get() = prefs.getString("last_host", "") ?: ""
        set(v) = prefs.edit().putString("last_host", v).apply()
    var lastPort: Int
        get() = prefs.getInt("last_port", RakNet.DEFAULT_PORT)
        set(v) = prefs.edit().putInt("last_port", v).apply()
    var transferMode: Boolean
        get() = prefs.getBoolean("transfer_mode", true)
        set(v) = prefs.edit().putBoolean("transfer_mode", v).apply()
    /** Protocolo que usó la consola la última vez: se anuncia si el servidor no responde al ping. */
    var lastClientProtocol: Int
        get() = prefs.getInt("last_client_protocol", 0)
        set(v) = prefs.edit().putInt("last_client_protocol", v).apply()
    var lastName: String
        get() = prefs.getString("last_name", "") ?: ""
        set(v) = prefs.edit().putString("last_name", v).apply()

    private companion object {
        const val KEY_SERVERS = "servers"
    }
}
