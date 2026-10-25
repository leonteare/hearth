package im.flume.hearth.core.network

import im.flume.hearth.core.common.SonosUpnpException
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The escaping, the time format and the fault reading: everything that turns 714 into a bug if it's wrong. */
class SonosXmlTest {
    /** A real Subsonic stream URL: a query string, an ampersand between every parameter, and a token in it. */
    private val streamUrl =
        "http://192.168.1.10:4533/rest/stream.view?id=tr-42&u=leon&t=9f8e&s=abc&v=1.16.1&c=hearth&f=mp3"

    private fun parse(xml: String) = DocumentBuilderFactory.newInstance()
        .apply { isNamespaceAware = true }
        .newDocumentBuilder()
        .parse(ByteArrayInputStream(xml.toByteArray()))

    @Test
    fun `a stream url with ampersands survives being escaped twice`() {
        val didl = SonosXml.didl(streamUrl, SonosMime.MP3, "Get Lucky", "Daft Punk")
        // Once: legal inside the DIDL document.
        assertTrue(didl.contains("id=tr-42&amp;u=leon&amp;t=9f8e"))
        assertTrue(SonosXml.unescape(didl).contains(streamUrl))

        val envelope = SonosXml.envelope(
            SonosService.AV_TRANSPORT,
            "SetAVTransportURI",
            listOf("InstanceID" to "0", "CurrentURI" to streamUrl, "CurrentURIMetaData" to didl),
        )
        // Twice: legal inside the SOAP body, which is what the speaker is sent.
        assertTrue(envelope.contains("id=tr-42&amp;amp;u=leon"))
        val metadata = SonosXml.unescape(SonosXml.tagText(envelope, "CurrentURIMetaData")!!)
        assertEquals(didl, metadata)
        // Unescaping the second time gets the URL the speaker will actually fetch back, character for character.
        assertEquals(streamUrl, SonosXml.unescape(SonosXml.tagText(metadata, "res")!!))
        assertEquals(streamUrl, SonosXml.unescape(SonosXml.tagText(envelope, "CurrentURI")!!))
    }

    @Test
    fun `the soap envelope and the didl inside it are both well-formed xml`() {
        val didl = SonosXml.didl(streamUrl, SonosMime.MP3, "Tom & Jerry <2>", "AC/DC \"live\"", album = "Hits & More")
        val envelope = SonosXml.envelope(
            SonosService.AV_TRANSPORT,
            "SetAVTransportURI",
            listOf("InstanceID" to "0", "CurrentURI" to streamUrl, "CurrentURIMetaData" to didl),
        )
        val doc = parse(envelope)
        val action = doc.getElementsByTagNameNS(SonosService.AV_TRANSPORT.urn, "SetAVTransportURI")
        assertEquals(1, action.length)
        // The parser hands back the metadata unescaped once, which has to be a document in its own right.
        val meta = doc.getElementsByTagName("CurrentURIMetaData").item(0).textContent
        assertEquals(didl, meta)
        val didlDoc = parse(meta)
        val item = didlDoc.getElementsByTagName("item").item(0)
        assertNotNull(item)
        assertEquals("Tom & Jerry <2>", didlDoc.getElementsByTagName("dc:title").item(0).textContent)
        assertEquals("AC/DC \"live\"", didlDoc.getElementsByTagName("dc:creator").item(0).textContent)
        assertEquals("Hits & More", didlDoc.getElementsByTagName("upnp:album").item(0).textContent)
        val res = didlDoc.getElementsByTagName("res").item(0)
        assertEquals(streamUrl, res.textContent)
        assertEquals(
            "http-get:*:audio/mpeg:*",
            res.attributes.getNamedItem("protocolInfo").nodeValue,
        )
    }

    @Test
    fun `the didl for a plain track is the shape a speaker was proved to accept`() {
        // Checked against a real Play:1: this exact document is what makes a stream URL playable.
        assertEquals(
            """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" """ +
                """xmlns:dc="http://purl.org/dc/elements/1.1/" """ +
                """xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">""" +
                """<item id="hearth" parentID="-1" restricted="1">""" +
                "<dc:title>Get Lucky</dc:title><dc:creator>Daft Punk</dc:creator>" +
                "<upnp:class>object.item.audioItem.musicTrack</upnp:class>" +
                """<res protocolInfo="http-get:*:audio/mpeg:*">http://nas:4533/rest/stream.view?id=1&amp;c=hearth</res>""" +
                "</item></DIDL-Lite>",
            SonosXml.didl(
                "http://nas:4533/rest/stream.view?id=1&c=hearth",
                SonosMime.MP3,
                "Get Lucky",
                "Daft Punk",
            ),
        )
    }

    @Test
    fun `the mime type is whatever the caller is actually serving`() {
        val book = SonosXml.didl("http://abs.home/item/9/file.m4b", SonosMime.MP4, "Chapter 1", "Pratchett")
        assertTrue(book.contains("""protocolInfo="http-get:*:audio/mp4:*""""))
        assertTrue(book.contains("<upnp:class>object.item.audioItem.musicTrack</upnp:class>"))
        // Nothing optional is invented: no album, no artwork element.
        assertTrue(!book.contains("upnp:album"))
    }

    @Test
    fun `the soapaction header is the service urn, the action and the quotes`() {
        assertEquals(
            "\"urn:schemas-upnp-org:service:AVTransport:1#Play\"",
            SonosXml.soapAction(SonosService.AV_TRANSPORT, "Play"),
        )
        assertEquals(
            "\"urn:schemas-upnp-org:service:RenderingControl:1#SetVolume\"",
            SonosXml.soapAction(SonosService.RENDERING_CONTROL, "SetVolume"),
        )
    }

    @Test
    fun `times read and write as H MM SS, including past an hour`() {
        assertEquals(0L, SonosXml.parseTime("0:00:00"))
        assertEquals(92_000L, SonosXml.parseTime("0:01:32"))
        assertEquals(5_025_000L, SonosXml.parseTime("1:23:45"))
        assertEquals(45_296_000L, SonosXml.parseTime("12:34:56"))
        assertEquals(12_000L, SonosXml.parseTime("0:00:12.345"))

        assertEquals("0:00:00", SonosXml.formatTime(0L))
        assertEquals("0:01:32", SonosXml.formatTime(92_000L))
        assertEquals("1:23:45", SonosXml.formatTime(5_025_000L))
        assertEquals("12:34:56", SonosXml.formatTime(45_296_000L))
        // A seek target has no room for milliseconds, so it rounds to the nearest second.
        assertEquals("0:00:02", SonosXml.formatTime(1_600L))
        assertEquals("0:00:00", SonosXml.formatTime(-5_000L))
    }

    @Test
    fun `a time the speaker has no answer for is not a time`() {
        assertNull(SonosXml.parseTime("NOT_IMPLEMENTED"))
        assertNull(SonosXml.parseTime(""))
        assertNull(SonosXml.parseTime(null))
        assertNull(SonosXml.parseTime("3:61:00"))
        assertNull(SonosXml.parseTime("90"))
    }

    @Test
    fun `an http 500 carrying error 714 reads as a refusal, not a broken server`() {
        val fault = """<?xml version="1.0"?>
            |<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
            |<s:Body><s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring>
            |<detail><UPnPError xmlns="urn:schemas-upnp-org:control-1-0">
            |<errorCode>714</errorCode><errorDescription>Illegal MIME-Type</errorDescription>
            |</UPnPError></detail></s:Fault></s:Body></s:Envelope>
        """.trimMargin()
        val e: SonosUpnpException? = SonosXml.faultOrNull(fault)
        assertNotNull(e)
        assertEquals(714, e!!.errorCode)
        assertEquals("Illegal MIME-Type", e.description)
        assertTrue(e.message!!.contains("didn't recognise that audio"))
        assertTrue(e.message!!.contains("714"))
        // Nothing in the wording looks like an HTTP status, which the UI would read as "the server is broken".
        assertTrue(!e.message!!.contains("HTTP"))
    }

    @Test
    fun `a code with no wording of its own still says what happened`() {
        val e = SonosXml.faultOrNull("<errorCode>800</errorCode><errorDescription>Not supported</errorDescription>")!!
        assertEquals(800, e.errorCode)
        assertTrue(e.message!!.contains("800"))
        assertTrue(e.message!!.contains("Not supported"))
    }

    @Test
    fun `a reply with no fault in it is not a fault`() {
        assertNull(SonosXml.faultOrNull("<s:Envelope><s:Body><u:PlayResponse /></s:Body></s:Envelope>"))
        assertNull(SonosXml.faultOrNull(""))
    }

    @Test
    fun `transport state, position and volume are read out of the speaker's answers`() {
        assertEquals(SonosTransportState.PLAYING, SonosXml.transportState("<CurrentTransportState>PLAYING</CurrentTransportState>"))
        assertEquals(
            SonosTransportState.PAUSED,
            SonosXml.transportState("<CurrentTransportState>PAUSED_PLAYBACK</CurrentTransportState>"),
        )
        assertEquals(
            SonosTransportState.STOPPED,
            SonosXml.transportState("<CurrentTransportState>NO_MEDIA_PRESENT</CurrentTransportState>"),
        )
        assertEquals(SonosTransportState.UNKNOWN, SonosXml.transportState("<CurrentTransportState>WAT</CurrentTransportState>"))
        assertEquals(SonosTransportState.UNKNOWN, SonosXml.transportState(""))

        val positionInfo = """
            |<u:GetPositionInfoResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
            |<Track>3</Track><TrackDuration>1:02:03</TrackDuration>
            |<TrackMetaData>&lt;DIDL-Lite&gt;&lt;item&gt;&lt;dc:title&gt;Get Lucky&lt;/dc:title&gt;&lt;/item&gt;&lt;/DIDL-Lite&gt;</TrackMetaData>
            |<TrackURI>http://192.168.1.10:4533/rest/stream.view?id=tr-42&amp;u=leon</TrackURI>
            |<RelTime>0:00:42</RelTime><AbsTime>NOT_IMPLEMENTED</AbsTime>
            |</u:GetPositionInfoResponse>
        """.trimMargin()
        val position = SonosXml.position(positionInfo)
        assertEquals(42_000L, position.elapsedMs)
        assertEquals(3_723_000L, position.durationMs)
        assertEquals("http://192.168.1.10:4533/rest/stream.view?id=tr-42&u=leon", position.trackUri)

        assertEquals(0L, SonosXml.position("<RelTime>NOT_IMPLEMENTED</RelTime>").durationMs)
        assertEquals(27, SonosXml.volume("<CurrentVolume>27</CurrentVolume>"))
    }
}
