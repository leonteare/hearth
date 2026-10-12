// The Audiobookshelf login (AbsTokens, AbsAuthStore), JWT expiry, and the server-clock offset (ServerClock, ClockSkew).
package im.flume.hearth.core.network

import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Locale
import java.util.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** An Audiobookshelf login: server, who, and the JWTs. Tokens only ever travel in the Authorization header. */
data class AbsTokens(val accessToken: String, val refreshToken: String)

/** Where [AbsClient] keeps the login. Implemented by AbsAccountStore (encrypted at rest). */
interface AbsAuthStore {
    /** Base URL without a trailing slash, or null when signed out. */
    fun serverUrl(): String?
    fun tokens(): AbsTokens?
    fun saveTokens(tokens: AbsTokens)
    /** The refresh token was refused: the user has to sign in again. */
    fun onAuthLost()
}

/** The difference between the server's clock and this phone's, measured from response Date headers. */
class ServerClock(initialOffsetMs: Long = 0, private val now: () -> Long = System::currentTimeMillis) {
    @Volatile var offsetMs: Long = initialOffsetMs
        private set
    @Volatile var measured: Boolean = false
        private set

    /** Now, on the server's clock. Progress timestamps are kept on this clock so phones and server compare fairly. */
    fun serverNow(): Long = now() + offsetMs

    fun onSample(serverDateMs: Long, sentAtMs: Long, receivedAtMs: Long) {
        offsetMs = ClockSkew.update(if (measured) offsetMs else null, ClockSkew.sample(serverDateMs, sentAtMs, receivedAtMs))
        measured = true
    }
}

object ClockSkew {
    /**
     * One measurement: server time minus the phone's time halfway through the request. The Date header
     * has whole seconds, so +500 ms centres the estimate on the second it names.
     */
    fun sample(serverDateMs: Long, sentAtMs: Long, receivedAtMs: Long): Long =
        serverDateMs + 500 - (sentAtMs + (receivedAtMs - sentAtMs) / 2)

    /** Jumps to a new sample when it's far off (clock changed), otherwise smooths out the 1 s jitter. */
    fun update(previous: Long?, sample: Long): Long = when {
        previous == null -> sample
        kotlin.math.abs(sample - previous) > 3_000 -> sample
        else -> previous + (sample - previous) / 4
    }

    fun parseHttpDate(value: String?): Long? = value?.let {
        runCatching {
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }.parse(it)?.time
        }.getOrNull()
    }
}

object Jwt {
    /** The token's expiry (epoch seconds), or null if it has none or can't be read. */
    fun expiresAt(token: String): Long? = runCatching {
        val payload = token.split('.').getOrNull(1) ?: return null
        val json = String(Base64.getUrlDecoder().decode(payload.padEnd((payload.length + 3) / 4 * 4, '=')), Charsets.UTF_8)
        Json.parseToJsonElement(json).jsonObject["exp"]?.jsonPrimitive?.longOrNull
    }.getOrNull()
}
