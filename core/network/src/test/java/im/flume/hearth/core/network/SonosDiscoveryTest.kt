package im.flume.hearth.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reading SSDP replies and a real device description; the socket itself needs a speaker, so it isn't here. */
class SonosDiscoveryTest {
    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResource("sonos/$name")!!.readText()

    /** What a Play:1 actually sends back, down to the header order and the CRLFs. */
    private fun reply(ip: String, uuid: String = "RINCON_7828CA301BFC01400"): String = listOf(
        "HTTP/1.1 200 OK",
        "CACHE-CONTROL: max-age = 1800",
        "EXT:",
        "LOCATION: http://$ip:1400/xml/device_description.xml",
        "SERVER: Linux UPnP/1.0 Sonos/86.10-57210 (ZPS1)",
        "ST: urn:schemas-upnp-org:device:ZonePlayer:1",
        "USN: uuid:${uuid}::urn:schemas-upnp-org:device:ZonePlayer:1",
        "X-RINCON-HOUSEHOLD: Sonos_abc123",
    ).joinToString("\r\n", postfix = "\r\n\r\n")

    @Test
    fun `a speaker answering the search five times is one address`() {
        val replies = List(3) { reply("192.168.1.121") } + reply("192.168.1.130") + reply("192.168.1.121")
        assertEquals(
            listOf(
                "http://192.168.1.121:1400/xml/device_description.xml",
                "http://192.168.1.130:1400/xml/device_description.xml",
            ),
            SonosDiscovery.locations(replies),
        )
    }

    @Test
    fun `replies with nothing to fetch are ignored`() {
        val noLocation = "HTTP/1.1 200 OK\r\nST: urn:schemas-upnp-org:device:ZonePlayer:1\r\n\r\n"
        val notHttp = "HTTP/1.1 200 OK\r\nLOCATION: ftp://192.168.1.9/thing.xml\r\n\r\n"
        assertEquals(emptyList<String>(), SonosDiscovery.locations(listOf(noLocation, notHttp, "", "garbage")))
    }

    @Test
    fun `the location header is read whatever case it arrives in`() {
        val lower = "HTTP/1.1 200 OK\r\nlocation:http://192.168.1.121:1400/xml/device_description.xml\r\n\r\n"
        assertEquals(
            listOf("http://192.168.1.121:1400/xml/device_description.xml"),
            SonosDiscovery.locations(listOf(lower)),
        )
    }

    @Test
    fun `a real device description gives the room, the model and the speaker's own id`() {
        val speaker = SonosDiscovery.parseDescription(
            fixture("device_description.xml"),
            "http://192.168.1.121:1400/xml/device_description.xml",
        )
        assertEquals(
            SonosSpeaker(
                uuid = "RINCON_7828CA301BFC01400",
                roomName = "Living Room",
                model = "Sonos Play:1",
                host = "http://192.168.1.121:1400",
                ip = "192.168.1.121",
            ),
            speaker,
        )
    }

    @Test
    fun `the speaker's id is the one at the top, not its media renderer's`() {
        // The description nests a MediaRenderer device whose UDN ends _MR; the queue is named by the outer one.
        val uuid = SonosDiscovery.parseDescription(
            fixture("device_description.xml"),
            "http://192.168.1.121:1400/xml/device_description.xml",
        )!!.uuid
        assertTrue(!uuid.endsWith("_MR"))
    }

    @Test
    fun `something that isn't a speaker is not one`() {
        val printer = """<root><device><friendlyName>Office printer</friendlyName></device></root>"""
        assertNull(SonosDiscovery.parseDescription(printer, "http://192.168.1.50:80/desc.xml"))
        assertNull(SonosDiscovery.parseDescription("", "http://192.168.1.50:1400/x.xml"))
        assertNull(SonosDiscovery.parseDescription(fixture("device_description.xml"), "not a url"))
    }

    @Test
    fun `a room name with an ampersand in it comes back as the user typed it`() {
        val xml = """<root><device><roomName>Kitchen &amp; Diner</roomName>
            |<modelName>Sonos One</modelName><UDN>uuid:RINCON_ABC01400</UDN></device></root>
        """.trimMargin()
        val speaker = SonosDiscovery.parseDescription(xml, "http://10.0.0.5:1400/xml/device_description.xml")!!
        assertEquals("Kitchen & Diner", speaker.roomName)
        assertEquals("RINCON_ABC01400", speaker.uuid)
        assertEquals("10.0.0.5", speaker.ip)
    }
}
