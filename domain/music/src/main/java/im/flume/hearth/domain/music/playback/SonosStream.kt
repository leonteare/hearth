// The URLs a Sonos speaker fetches music from: the song itself and its cover art, on the plain-HTTP host
// a speaker can reach, carrying the Subsonic token the same way every other Hearth URL does.
package im.flume.hearth.domain.music.playback

import im.flume.hearth.core.network.Credentials
import im.flume.hearth.core.network.SubsonicClient
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * A speaker cannot be taught to trust the home server's own certificate authority, so it fetches from
 * `http://sonos.home` (Navidrome at the root) rather than the https address the phone uses. The token and
 * salt ride in the URL exactly as they do for the phone's own streams; the speaker's queue then holds
 * them, which is the price of letting the speaker pull (see DECISIONS.md).
 *
 * Nothing here touches the network: given a base address, a song and a login it is just text.
 */
object SonosStream {
    /**
     * Where the speaker fetches [songId], or null when the base address isn't a URL or nobody is signed
     * in. Always mp3: a speaker refuses a stream whose type it can't work out, and the DIDL metadata sent
     * with the URL has to promise the same type. [maxBitRate] 0 leaves the server's own mp3 setting alone.
     */
    fun songUrl(baseUrl: String, songId: String, creds: Credentials?, maxBitRate: Int = 0): String? =
        endpoint(baseUrl, "stream", creds)
            ?.addQueryParameter("id", songId)
            ?.addQueryParameter("format", "mp3")
            ?.apply { if (maxBitRate > 0) addQueryParameter("maxBitRate", maxBitRate.toString()) }
            // A converted stream has no real length, and without a guessed one the speaker can't show how
            // long the song is or seek inside it - which is how a hand-over lands where the phone got to.
            ?.addQueryParameter("estimateContentLength", "true")
            ?.build()?.toString()

    /** The album art the speaker shows, or null when there is none. Served with the same credentials. */
    fun coverUrl(baseUrl: String, coverArtId: String?, creds: Credentials?, size: Int = 600): String? {
        if (coverArtId.isNullOrEmpty()) return null
        return endpoint(baseUrl, "getCoverArt", creds)
            ?.addQueryParameter("id", coverArtId)
            ?.addQueryParameter("size", size.toString())
            ?.build()?.toString()
    }

    /**
     * `<base>/rest/<method>.view` with the login on it. The `.view` suffix is what the real speaker was
     * tested against, and Navidrome answers it either way.
     */
    private fun endpoint(baseUrl: String, method: String, creds: Credentials?): HttpUrl.Builder? {
        val c = creds ?: return null
        val base = baseUrl.trim().trimEnd('/')
        if (base.isEmpty()) return null
        val url = "$base/rest/$method.view".toHttpUrlOrNull() ?: return null
        return url.newBuilder()
            .addQueryParameter("u", c.username)
            .addQueryParameter("t", c.token)
            .addQueryParameter("s", c.salt)
            .addQueryParameter("v", SubsonicClient.API_VERSION)
            .addQueryParameter("c", SubsonicClient.CLIENT_NAME)
    }
}
