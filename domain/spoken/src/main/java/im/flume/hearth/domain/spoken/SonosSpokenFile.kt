// The URL a Sonos speaker fetches an Audiobookshelf file from: the plain-HTTP host, Audiobookshelf's own
// path on it, and the access token in the query string.
package im.flume.hearth.domain.spoken

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Audiobookshelf normally gets the token in an Authorization header, which a speaker cannot send: it is
 * handed a URL and fetches it on its own. So the token goes in the query string, the way Audiobookshelf's
 * own web player does it, on the LAN-only host the speaker can reach (`http://sonos.home/abs`).
 *
 * Nothing here touches the network: given a base address, an item and a token it is just text.
 */
object SonosSpokenFile {
    /** Audiobookshelf sits under this path on the Sonos host; Navidrome has the root. */
    const val PATH_PREFIX = "abs"

    /**
     * Where the speaker fetches one audio file ([ino] is Audiobookshelf's id for it), or null when the
     * base address isn't a URL or nobody is signed in to Audiobookshelf.
     */
    fun fileUrl(baseUrl: String, libraryItemId: String, ino: String, accessToken: String?): String? {
        val token = accessToken?.takeIf { it.isNotBlank() } ?: return null
        if (libraryItemId.isBlank() || ino.isBlank()) return null
        val base = baseUrl.trim().trimEnd('/')
        if (base.isEmpty()) return null
        val url = base.toHttpUrlOrNull() ?: return null
        return url.newBuilder()
            .addPathSegments("$PATH_PREFIX/api/items/$libraryItemId/file/$ino")
            .addQueryParameter("token", token)
            .build().toString()
    }

    /** The cover the speaker shows. Audiobookshelf serves covers without a login, so this needs no token. */
    fun coverUrl(baseUrl: String, libraryItemId: String, width: Int = 600): String? {
        if (libraryItemId.isBlank()) return null
        val base = baseUrl.trim().trimEnd('/')
        if (base.isEmpty()) return null
        val url = base.toHttpUrlOrNull() ?: return null
        return url.newBuilder()
            .addPathSegments("$PATH_PREFIX/api/items/$libraryItemId/cover")
            .addQueryParameter("width", width.toString())
            .addQueryParameter("format", "jpeg")
            .build().toString()
    }
}
