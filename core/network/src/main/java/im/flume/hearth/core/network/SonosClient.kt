// Controls one Sonos speaker: fill its queue, play, pause, seek, skip, and read back what it's doing and how loud.
package im.flume.hearth.core.network

import im.flume.hearth.core.common.SonosException
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * One speaker, controlled over the local network. Nothing here goes near the internet or a Sonos account.
 *
 * The queue is the point: tracks are pushed to the speaker with [addToQueue] and then handed over with
 * [playQueue], so the speaker keeps going when the phone is locked, out of range or switched off. A single
 * track can be loaded with [setUri] instead, which always needs [SonosXml.didl] metadata with it.
 *
 * Anything that fails throws [SonosException]; a speaker that refuses a request throws
 * `SonosUpnpException` carrying the reason it gave.
 */
class SonosClient(http: OkHttpClient, host: String) {
    constructor(http: OkHttpClient, speaker: SonosSpeaker) : this(http, speaker.host)

    // A speaker on the same network answers in milliseconds: a long wait here just freezes a button.
    private val http = http.newBuilder().callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS).build()

    private val base: HttpUrl = baseUrl(host)

    /** Where the calls are going, port filled in: for telling one speaker from another. */
    val address: String get() = base.toString()

    /**
     * Loads one thing to play. [metadata] is a DIDL-Lite document from [SonosXml.didl] and is not optional:
     * a stream URL on its own tells the speaker nothing about what it is, and it answers UPnP error 714.
     */
    suspend fun setUri(uri: String, metadata: String) {
        transport("SetAVTransportURI", "CurrentURI" to uri, "CurrentURIMetaData" to metadata)
    }

    suspend fun play() {
        transport("Play", "Speed" to "1")
    }

    suspend fun pause() {
        transport("Pause")
    }

    suspend fun stop() {
        transport("Stop")
    }

    /** Jumps to [ms] into the current track. */
    suspend fun seekTo(ms: Long) {
        transport("Seek", "Unit" to "REL_TIME", "Target" to SonosXml.formatTime(ms))
    }

    suspend fun next() {
        transport("Next")
    }

    suspend fun previous() {
        transport("Previous")
    }

    suspend fun transportState(): SonosTransportState = SonosXml.transportState(transport("GetTransportInfo"))

    suspend fun position(): SonosPosition = SonosXml.position(transport("GetPositionInfo"))

    /** The speaker's volume, 0..100. */
    suspend fun volume(): Int = SonosXml.volume(rendering("GetVolume", "Channel" to "Master"))

    /** Sets the volume; anything outside 0..100 is pulled back into it rather than refused. */
    suspend fun setVolume(level: Int) {
        rendering("SetVolume", "Channel" to "Master", "DesiredVolume" to level.coerceIn(0, 100).toString())
    }

    /**
     * Adds one track to the end of the speaker's queue, or at [index] (the first track is 1). [metadata]
     * is a DIDL-Lite document from [SonosXml.didl], for the same reason as [setUri]. Returns how many
     * tracks the queue holds now.
     */
    suspend fun addToQueue(uri: String, metadata: String, index: Int = 0): Int {
        val reply = transport(
            "AddURIToQueue",
            "EnqueuedURI" to uri,
            "EnqueuedURIMetaData" to metadata,
            "DesiredFirstTrackNumberEnqueued" to index.coerceAtLeast(0).toString(),
            "EnqueueAsNext" to "0",
        )
        return SonosXml.tagText(reply, "NewQueueLength")?.trim()?.toIntOrNull() ?: 0
    }

    /** Empties the speaker's queue. */
    suspend fun clearQueue() {
        transport("RemoveAllTracksFromQueue")
    }

    /**
     * Points the speaker at its own queue and starts it from the first track. [speakerUuid] is the uuid of
     * the speaker that owns the queue, which is this one unless it is in a group, where it is the
     * coordinator's. The queue URI needs no metadata: the speaker already knows what is in its queue.
     */
    suspend fun playQueue(speakerUuid: String) {
        transport("SetAVTransportURI", "CurrentURI" to "x-rincon-queue:$speakerUuid#0", "CurrentURIMetaData" to "")
        play()
    }

    private suspend fun transport(action: String, vararg args: Pair<String, String>): String =
        send(SonosService.AV_TRANSPORT, action, args)

    private suspend fun rendering(action: String, vararg args: Pair<String, String>): String =
        send(SonosService.RENDERING_CONTROL, action, args)

    /** Posts one SOAP action. Both services take `InstanceID`, which is always 0 on a Sonos speaker. */
    private suspend fun send(
        service: SonosService,
        action: String,
        args: Array<out Pair<String, String>>,
    ): String = withContext(Dispatchers.IO) {
        val envelope = SonosXml.envelope(service, action, listOf("InstanceID" to "0") + args)
        val request = Request.Builder()
            .url(base.newBuilder().encodedPath(service.path).build())
            .header("SOAPACTION", SonosXml.soapAction(service, action))
            .post(envelope.toRequestBody(XML))
            .build()
        try {
            http.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    // A refusal arrives as HTTP 500 with the real reason inside, so unwrap it first.
                    throw SonosXml.faultOrNull(body) ?: SonosException("The speaker answered HTTP ${resp.code}")
                }
                body
            }
        } catch (e: SonosException) {
            throw e
        } catch (e: IOException) {
            throw SonosException("Couldn't reach the speaker", e)
        }
    }

    private companion object {
        const val CALL_TIMEOUT_SECONDS = 8L
        val XML = "text/xml; charset=\"utf-8\"".toMediaType()

        /** The port every speaker serves its control endpoints on. */
        const val CONTROL_PORT = 1400

        private val explicitPort = Regex(""":\d+$""")

        /**
         * The host may be given as a whole URL (what discovery hands back), an address and port, or just an
         * address, in which case the speakers' own port is assumed.
         */
        fun baseUrl(host: String): HttpUrl {
            val text = host.trim().ifEmpty { throw SonosException("No speaker address") }
            val withScheme = if (text.startsWith("http://") || text.startsWith("https://")) text else "http://$text"
            val url = withScheme.toHttpUrlOrNull() ?: throw SonosException("That speaker address isn't valid")
            val authority = withScheme.substringAfter("//").substringBefore('/').substringBefore('?')
            val port = if (explicitPort.containsMatchIn(authority)) url.port else CONTROL_PORT
            return url.newBuilder().port(port).encodedPath("/").build()
        }
    }
}
