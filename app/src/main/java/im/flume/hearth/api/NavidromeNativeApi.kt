package im.flume.hearth.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** Navidrome turned the password down (wrong, or changed since it was saved). */
class WrongPasswordException : IOException("Navidrome didn't accept the password")

/** No password saved yet; the UI should ask for it once. */
class PasswordNeededException : IOException("Password needed")

/**
 * Navidrome's own (non-Subsonic) API, only for what Subsonic can't do: playlist photos. It needs a
 * session token from a real password login, so the password comes from [password] (kept encrypted
 * on the phone). The token lives in memory only and is never logged.
 */
class NavidromeNativeApi(
    private val http: OkHttpClient,
    private val credentialsProvider: () -> Credentials?,
    private val password: () -> String?,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    @Volatile private var token: String? = null

    /** Logs in and returns the session token; throws [WrongPasswordException] on a bad password. */
    suspend fun login(serverUrl: String, username: String, password: String): String = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("username", username)
            put("password", password)
        }.toString().toRequestBody(JSON)
        val req = Request.Builder().url("$serverUrl/auth/login").post(body).build()
        http.newCall(req).execute().use { resp ->
            if (resp.code == 401 || resp.code == 403) throw WrongPasswordException()
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} from server")
            val text = resp.body?.string() ?: throw IOException("Empty response")
            json.parseToJsonElement(text).jsonObject["token"]?.jsonPrimitive?.content
                ?: throw IOException("Server didn't return a session")
        }
    }

    /** Sets [jpeg] as the playlist's photo. */
    suspend fun uploadPlaylistImage(playlistId: String, jpeg: ByteArray) {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("image", "cover.jpg", jpeg.toRequestBody("image/jpeg".toMediaType()))
            .build()
        authed { base -> Request.Builder().url("$base/api/playlist/$playlistId/image").post(body) }
    }

    /** Goes back to the automatic cover made from the playlist's albums. */
    suspend fun removePlaylistImage(playlistId: String) {
        authed { base -> Request.Builder().url("$base/api/playlist/$playlistId/image").delete() }
    }

    fun forgetSession() { token = null }

    /** Runs a request with the session token, logging in first (or again after a 401). */
    private suspend fun authed(build: (serverUrl: String) -> Request.Builder) = withContext(Dispatchers.IO) {
        val creds = credentialsProvider() ?: throw IOException("Not logged in")
        for (attempt in 0..1) {
            val current = mutex.withLock {
                token ?: run {
                    val pw = password() ?: throw PasswordNeededException()
                    login(creds.serverUrl, creds.username, pw).also { token = it }
                }
            }
            val req = build(creds.serverUrl).header(AUTH_HEADER, "Bearer $current").build()
            http.newCall(req).execute().use { resp ->
                // Navidrome hands back a refreshed token on each call; keep it so the session doesn't lapse.
                resp.header(AUTH_HEADER)?.removePrefix("Bearer ")?.takeIf { it.isNotBlank() }?.let { token = it }
                when {
                    resp.isSuccessful -> return@withContext
                    resp.code == 401 && attempt == 0 -> token = null
                    resp.code == 413 -> throw IOException("That photo is too big")
                    else -> throw IOException("HTTP ${resp.code} from server")
                }
            }
        }
        throw IOException("Navidrome wouldn't accept the session")
    }

    companion object {
        private const val AUTH_HEADER = "x-nd-authorization"
        private val JSON = "application/json".toMediaType()
    }
}
