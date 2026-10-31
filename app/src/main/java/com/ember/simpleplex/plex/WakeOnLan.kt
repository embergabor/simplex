package com.ember.simpleplex.plex

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.URI

/**
 * Wakes a sleeping server with a Wake-on-LAN packet. The server's network (MAC) address is typed in
 * Settings: Plex doesn't report it, and Android doesn't let apps read the network's address table.
 */
object WakeOnLan {

    /** The 6 bytes from "20:07:11:1a:19:86", "20-07-11-1A-19-86" or "2007111a1986"; null if it isn't one. */
    fun parseMac(text: String): ByteArray? {
        val hex = text.filter { it !in ":-. " }
        if (hex.length != 12 || hex.any { it.lowercaseChar() !in "0123456789abcdef" }) return null
        return ByteArray(6) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    /** "20:07:11:1a:19:86". */
    fun format(mac: ByteArray) = mac.joinToString(":") { "%02x".format(it) }

    /** Six 0xff bytes, then the address 16 times. */
    fun packet(mac: ByteArray) = ByteArray(6) { 0xff.toByte() } + List(16) { mac }.reduce(ByteArray::plus)

    /**
     * Where the packet goes: everyone on the network, and the server's own /24 broadcast address when
     * the server is an IPv4 address (some routers drop the all-ones one).
     */
    fun targets(serverUri: String?): List<String> {
        val host = serverUri?.let { runCatching { URI(it).host }.getOrNull() }
        val parts = host?.split('.')?.takeIf { p -> p.size == 4 && p.all { it.toIntOrNull() in 0..255 } }
        return listOfNotNull("255.255.255.255", parts?.let { it.take(3).joinToString(".") + ".255" })
    }

    suspend fun send(mac: ByteArray, serverUri: String?) = withContext(Dispatchers.IO) {
        val data = packet(mac)
        DatagramSocket().use { socket ->
            socket.broadcast = true
            for (target in targets(serverUri)) {
                val address = InetAddress.getByName(target)
                for (port in intArrayOf(9, 7)) socket.send(DatagramPacket(data, data.size, address, port))
            }
        }
    }

    /** The saved address, kept on the TV. */
    class Prefs(context: Context) {
        private val prefs = context.getSharedPreferences("ui", Context.MODE_PRIVATE)

        var mac: ByteArray?
            get() = prefs.getString("serverMac", null)?.let(::parseMac)
            set(value) { prefs.edit().putString("serverMac", value?.let(::format)).apply() }
    }
}
