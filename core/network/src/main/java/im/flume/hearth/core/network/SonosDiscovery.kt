// Finds Sonos speakers on the network: an SSDP search over UDP, then the device description of everything that
// answered. Only `search` touches a socket; picking the replies apart is pure and tested.
package im.flume.hearth.core.network

import im.flume.hearth.core.common.SonosException
import java.io.IOException
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Looks for speakers by shouting on the local network and seeing who answers.
 *
 * Replies are collected for a couple of seconds because a speaker answers more than once, and because on a
 * phone the search goes out of every usable interface (Wi-Fi, Ethernet over USB, a hotspot), so the same
 * speaker can reply by more than one route. Duplicates are dropped by address and then by the speaker's own
 * identifier, so a room only ever appears once.
 */
class SonosDiscovery(http: OkHttpClient) {
    // A speaker that isn't there shouldn't hold the search up: its description is a small file on the LAN.
    private val http = http.newBuilder()
        .callTimeout(DESCRIPTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /** Every speaker that answered within [timeoutMs], by room name. Empty when there are none. */
    suspend fun search(timeoutMs: Long = DEFAULT_SEARCH_MS): List<SonosSpeaker> = withContext(Dispatchers.IO) {
        val found = coroutineScope {
            listen(timeoutMs).map { location -> async { describe(location) } }.awaitAll()
        }
        found.filterNotNull().distinctBy { it.uuid }.sortedBy { it.roomName.lowercase() }
    }

    /** Sends the search and gathers the `LOCATION` of everything that replies before the deadline. */
    private fun listen(timeoutMs: Long): List<String> {
        val replies = mutableListOf<String>()
        try {
            MulticastSocket().use { socket ->
                socket.soTimeout = RECEIVE_SLICE_MS
                runCatching { socket.timeToLive = SEARCH_TTL }
                send(socket)
                val deadline = System.nanoTime() + timeoutMs * 1_000_000L
                val buffer = ByteArray(BUFFER_BYTES)
                while (System.nanoTime() < deadline) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    // SSDP is ASCII headers; ISO-8859-1 never throws on a stray byte.
                    replies += String(packet.data, packet.offset, packet.length, Charsets.ISO_8859_1)
                }
            }
        } catch (e: IOException) {
            throw SonosException("Couldn't look for speakers on this network", e)
        }
        return locations(replies)
    }

    /**
     * Shouts the search out of every interface that could reach a speaker, twice each because UDP drops
     * packets. A phone on Wi-Fi with a VPN or a USB tether has several; sending on the default route only
     * would miss the speaker whenever that route isn't the Wi-Fi.
     */
    private fun send(socket: MulticastSocket) {
        val group = InetAddress.getByName(SSDP_ADDRESS)
        val bytes = SEARCH_MESSAGE.toByteArray(Charsets.ISO_8859_1)
        val packet = DatagramPacket(bytes, bytes.size, group, SSDP_PORT)
        var sent = false
        for (candidate in candidates()) {
            try {
                socket.networkInterface = candidate
                repeat(2) { socket.send(packet) }
                sent = true
            } catch (_: IOException) {
                // This interface can't carry multicast after all; the others still might.
            }
        }
        // Nothing looked usable: try the route the system would have picked anyway.
        if (!sent) socket.send(packet)
    }

    /** The interfaces worth shouting on: up, not loopback, multicast-capable and holding an IPv4 address. */
    private fun candidates(): List<NetworkInterface> {
        val all = runCatching { Collections.list(NetworkInterface.getNetworkInterfaces()) }.getOrNull().orEmpty()
        return all.filter { ni ->
            runCatching {
                ni.isUp && !ni.isLoopback && !ni.isPointToPoint && ni.supportsMulticast() &&
                    Collections.list(ni.inetAddresses).any { it is Inet4Address && !it.isLoopbackAddress }
            }.getOrDefault(false)
        }
    }

    /** Fetches and reads one device description; a box that doesn't answer properly is simply not a speaker. */
    private fun describe(location: String): SonosSpeaker? {
        val request = Request.Builder().url(location).get().build()
        val body = runCatching {
            http.newCall(request).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }
        }.getOrNull() ?: return null
        return parseDescription(body, location)
    }

    companion object {
        private const val SSDP_ADDRESS = "239.255.255.250"
        private const val SSDP_PORT = 1900

        /** The device type every Sonos speaker, old or new, answers to. */
        const val ZONE_PLAYER = "urn:schemas-upnp-org:device:ZonePlayer:1"

        private const val DEFAULT_SEARCH_MS = 2_000L
        private const val RECEIVE_SLICE_MS = 250
        private const val BUFFER_BYTES = 8 * 1024
        private const val SEARCH_TTL = 2
        private const val DESCRIPTION_TIMEOUT_SECONDS = 4L

        /** SSDP is HTTP-shaped but needs CRLF line endings and the blank line at the end. */
        private val SEARCH_MESSAGE = listOf(
            "M-SEARCH * HTTP/1.1",
            "HOST: $SSDP_ADDRESS:$SSDP_PORT",
            "MAN: \"ssdp:discover\"",
            "MX: 2",
            "ST: $ZONE_PLAYER",
        ).joinToString("\r\n", postfix = "\r\n\r\n")

        /**
         * The `LOCATION` header of each reply, in the order they arrived and without repeats: the same
         * speaker answers the search several times, and once per interface it can be reached on.
         */
        fun locations(replies: List<String>): List<String> = replies
            .mapNotNull { reply ->
                reply.lineSequence()
                    .firstOrNull { it.startsWith("LOCATION:", ignoreCase = true) }
                    ?.substringAfter(':')
                    ?.trim()
                    ?.takeIf { it.startsWith("http", ignoreCase = true) }
            }
            .distinct()

        /**
         * A speaker read out of its own `device_description.xml`, or null when the XML isn't one: anything
         * without a room name and an identifier is no use to us.
         */
        fun parseDescription(xml: String, location: String): SonosSpeaker? {
            val url = location.toHttpUrlOrNull() ?: return null
            val room = SonosXml.tagText(xml, "roomName")?.let { SonosXml.unescape(it).trim() }
            val uuid = SonosXml.tagText(xml, "UDN")?.trim()?.removePrefix("uuid:")?.trim()
            if (room.isNullOrBlank() || uuid.isNullOrBlank()) return null
            return SonosSpeaker(
                uuid = uuid,
                roomName = room,
                model = SonosXml.tagText(xml, "modelName")?.let { SonosXml.unescape(it).trim() }.orEmpty(),
                host = "${url.scheme}://${url.host}:${url.port}",
                ip = url.host,
            )
        }
    }
}
