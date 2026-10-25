// The pure half of talking to a Sonos speaker: SOAP envelopes, the DIDL-Lite metadata a stream needs, and
// reading transport state, position, volume and a UPnP refusal back out of the speaker's answer.
package im.flume.hearth.core.network

import im.flume.hearth.core.common.SonosException
import im.flume.hearth.core.common.SonosUpnpException

/** The two UPnP services Hearth uses, each with the path its calls are posted to. */
enum class SonosService(val urn: String, val path: String) {
    AV_TRANSPORT("urn:schemas-upnp-org:service:AVTransport:1", "/MediaRenderer/AVTransport/Control"),
    RENDERING_CONTROL("urn:schemas-upnp-org:service:RenderingControl:1", "/MediaRenderer/RenderingControl/Control"),
}

/**
 * Everything about the Sonos protocol that is just text in and text out, so it can be tested without a
 * speaker or a socket. The escaping in [didl] is the part that matters: a Subsonic stream URL says nothing
 * about what it is, so a speaker sent one without metadata refuses it outright.
 */
object SonosXml {
    private const val DIDL_NS = "urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/"
    private const val DC_NS = "http://purl.org/dc/elements/1.1/"
    private const val UPNP_NS = "urn:schemas-upnp-org:metadata-1-0/upnp/"

    private val tagPatterns = HashMap<String, Regex>()

    /** `H:MM:SS`, optionally with the fraction of a second some firmwares add. */
    private val timePattern = Regex("""^(\d+):([0-5]?\d):([0-5]?\d)(?:\.\d+)?$""")

    /** The five entities XML needs, so a title with an ampersand or a quote can't break the document. */
    fun escape(text: String): String = buildString(text.length) {
        for (c in text) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> append(c)
        }
    }

    /** Turns those five entities back into characters; metadata comes back from the speaker escaped. */
    fun unescape(text: String): String = text
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")

    /** The `SOAPACTION` header value, quotes included: the speaker ignores the call without them. */
    fun soapAction(service: SonosService, action: String): String = "\"${service.urn}#$action\""

    /**
     * A SOAP request for [action]. Every argument's value is escaped, which is what double-escapes the
     * DIDL document from [didl] when it goes in as `CurrentURIMetaData`.
     */
    fun envelope(service: SonosService, action: String, args: List<Pair<String, String>>): String {
        val body = args.joinToString("") { (name, value) -> "<$name>${escape(value)}</$name>" }
        return """<?xml version="1.0" encoding="utf-8"?>""" +
            """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" """ +
            """s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">""" +
            """<s:Body><u:$action xmlns:u="${service.urn}">$body</u:$action></s:Body>""" +
            """</s:Envelope>"""
    }

    /**
     * The DIDL-Lite description of one track. [mime] is what the speaker will be fetching (mp3 for music,
     * mp4 for most audiobooks) and it has to be right: with no metadata, or metadata the speaker can't
     * type, `SetAVTransportURI` comes back as UPnP error 714. The URL is escaped here, and the whole
     * document is escaped again by [envelope] on the way into the SOAP body.
     */
    fun didl(
        url: String,
        mime: String,
        title: String,
        artist: String = "",
        album: String? = null,
        artUrl: String? = null,
    ): String = buildString {
        append("""<DIDL-Lite xmlns="$DIDL_NS" xmlns:dc="$DC_NS" xmlns:upnp="$UPNP_NS">""")
        append("""<item id="hearth" parentID="-1" restricted="1">""")
        append("<dc:title>${escape(title)}</dc:title>")
        append("<dc:creator>${escape(artist)}</dc:creator>")
        album?.takeIf { it.isNotBlank() }?.let { append("<upnp:album>${escape(it)}</upnp:album>") }
        artUrl?.takeIf { it.isNotBlank() }?.let { append("<upnp:albumArtURI>${escape(it)}</upnp:albumArtURI>") }
        append("<upnp:class>object.item.audioItem.musicTrack</upnp:class>")
        append("""<res protocolInfo="http-get:*:$mime:*">${escape(url)}</res>""")
        append("</item></DIDL-Lite>")
    }

    /** The text inside the first `<[name]>` element, whatever namespace prefix it carries, still escaped. */
    fun tagText(xml: String, name: String): String? {
        val pattern = tagPatterns.getOrPut(name) {
            Regex("<(?:[^<>:\\s]+:)?$name\\b[^>]*>(.*?)</(?:[^<>:\\s]+:)?$name>", RegexOption.DOT_MATCHES_ALL)
        }
        return pattern.find(xml)?.groupValues?.get(1)
    }

    /** What `GetTransportInfo` answered. */
    fun transportState(xml: String): SonosTransportState =
        SonosTransportState.from(tagText(xml, "CurrentTransportState"))

    /** What `GetPositionInfo` answered; a speaker with nothing loaded reports zeroes. */
    fun position(xml: String): SonosPosition = SonosPosition(
        elapsedMs = parseTime(tagText(xml, "RelTime")) ?: 0L,
        durationMs = parseTime(tagText(xml, "TrackDuration")) ?: 0L,
        trackUri = unescape(tagText(xml, "TrackURI").orEmpty()).trim(),
    )

    /** What `GetVolume` answered, 0..100. */
    fun volume(xml: String): Int = tagText(xml, "CurrentVolume")?.trim()?.toIntOrNull()
        ?: throw SonosException("The speaker didn't say what its volume is")

    /**
     * `H:MM:SS` as milliseconds, or null when the speaker has no answer (a live stream's duration comes
     * back as `NOT_IMPLEMENTED`).
     */
    fun parseTime(text: String?): Long? {
        val m = timePattern.matchEntire(text?.trim().orEmpty()) ?: return null
        val (h, min, sec) = m.destructured
        return ((h.toLong() * 60 + min.toLong()) * 60 + sec.toLong()) * 1000L
    }

    /** Milliseconds as the `H:MM:SS` a `Seek` target has to be in; hours are not padded. */
    fun formatTime(ms: Long): String {
        val total = (ms.coerceAtLeast(0L) + 500L) / 1000L
        return "${total / 3600}:${pad(total / 60 % 60)}:${pad(total % 60)}"
    }

    private fun pad(value: Long): String = if (value < 10) "0$value" else "$value"

    /**
     * The refusal inside a failed reply, if that is what it is. UPnP sends a plain HTTP 500 with the real
     * reason in the body, so this turns "500" into something a caller (and a listener) can act on.
     */
    fun faultOrNull(body: String): SonosUpnpException? {
        val code = tagText(body, "errorCode")?.trim()?.toIntOrNull() ?: return null
        return SonosUpnpException(code, unescape(tagText(body, "errorDescription").orEmpty()).trim())
    }
}
