package me.Integer.Mediaplayer.library

import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Discovers an AI DJ Mashup Server (`ai_router/server.py`) on the local Wi-Fi network
 * by sending a UDP broadcast probe to port 8766 and listening for the server's reply.
 * Blocking: call off the main thread.
 */
object AiServerDiscovery {

    data class DiscoveredServer(val url: String, val device: String)

    private const val DISCOVERY_PORT = 8766
    private const val TIMEOUT_MS = 1800
    private val PROBE = "AIDJ_DISCOVER_V1".toByteArray(Charsets.UTF_8)

    fun discover(): DiscoveredServer? {
        return try {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = TIMEOUT_MS
                val broadcastAddr = InetAddress.getByName("255.255.255.255")
                val packet = DatagramPacket(PROBE, PROBE.size, broadcastAddr, DISCOVERY_PORT)
                socket.send(packet)

                val buf = ByteArray(512)
                val reply = DatagramPacket(buf, buf.size)
                socket.receive(reply)
                val json = JSONObject(String(reply.data, 0, reply.length, Charsets.UTF_8))
                if (json.optString("service") != "ai_dj_router") return null
                val host = reply.address?.hostAddress ?: return null
                val port = json.optInt("port", 8765)
                val device = json.optString("device", "cpu")
                DiscoveredServer("http://$host:$port", device)
            }
        } catch (_: Exception) {
            null
        }
    }
}
