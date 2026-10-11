package im.flume.hearth.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Locale
import java.util.TimeZone

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

open class AbsException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** The server answered with an error status. [body] is a short, token-free excerpt. */
class AbsHttpException(val code: Int, val body: String) : AbsException("The podcast server answered HTTP $code${if (body.isNotBlank()) ": $body" else ""}")

/** Not signed in, or the login expired and couldn't be renewed. */
class AbsAuthException(message: String) : AbsException(message)

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

/**
 * Talks to Audiobookshelf. Every request goes through [http], which carries [AuthInterceptor]: the
 * access token is added as a header (never to URLs, so it can't leak into logs, media metadata or
 * notifications), renewed shortly before it expires, and once more if the server says 401.
 * Use [http] for streaming and downloads too.
 */
class AbsClient(
    baseHttp: OkHttpClient,
    private val store: AbsAuthStore,
    val clock: ServerClock = ServerClock(),
) {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true; explicitNulls = false }
    private val plain: OkHttpClient = baseHttp.newBuilder().addInterceptor(ClockInterceptor()).build()
    val http: OkHttpClient = baseHttp.newBuilder().addInterceptor(ClockInterceptor()).addInterceptor(AuthInterceptor()).build()
    private val refreshLock = Any()

    private fun base(): HttpUrl = store.serverUrl()?.toHttpUrlOrNull() ?: throw AbsAuthException("Not signed in to podcasts & audiobooks")

    fun url(path: String, query: Map<String, String> = emptyMap()): HttpUrl {
        val b = base().newBuilder()
        path.trimStart('/').split('/').filter { it.isNotEmpty() }.forEach { b.addPathSegment(it) }
        query.forEach { (k, v) -> b.addQueryParameter(k, v) }
        return b.build()
    }

    /** Cover image. Audiobookshelf serves covers without a login, so this URL is safe to hand to Coil. */
    fun coverUrl(libraryItemId: String, width: Int = 600, version: Long? = null): String? = runCatching {
        // [version] (the item's updatedAt) changes the URL when the artwork does, so a cover cached before
        // the server had fetched it (or an old one) is never shown again.
        val q = buildMap {
            put("width", width.toString()); put("format", "jpeg")
            version?.let { put("ts", it.toString()) }
        }
        url("api/items/$libraryItemId/cover", q).toString()
    }.getOrNull()

    /** An audio file of an item. Needs the Authorization header: fetch it with [http]. */
    fun fileUrl(libraryItemId: String, ino: String): String = url("api/items/$libraryItemId/file/$ino").toString()

    // ------------------------------------------------------------------ auth

    /** Signs in and returns the user with fresh tokens. Doesn't store anything. */
    suspend fun login(serverUrl: String, username: String, password: String): AbsLoginResponse = withContext(Dispatchers.IO) {
        val url = "${normalizeUrl(serverUrl)}/login".toHttpUrlOrNull() ?: throw AbsException("That server address isn't valid")
        val body = buildJsonObject { put("username", username.trim()); put("password", password) }.toString()
        val req = Request.Builder().url(url).header("x-return-tokens", "true").post(body.toRequestBody(JSON)).build()
        plain.newCall(req).execute().use { resp ->
            when {
                resp.code == 401 -> throw AbsAuthException("Wrong username or password")
                resp.code == 429 -> throw AbsException("Too many sign-in attempts. Wait ten minutes and try again")
                !resp.isSuccessful -> throw AbsHttpException(resp.code, excerpt(resp))
            }
            json.decodeFromString(AbsLoginResponse.serializer(), resp.body!!.string())
        }
    }

    /** Ends this phone's server session (the refresh token stops working). Best effort. */
    suspend fun logout() = withContext(Dispatchers.IO) {
        val t = store.tokens() ?: return@withContext
        runCatching {
            plain.newCall(Request.Builder().url(url("logout")).header("x-refresh-token", t.refreshToken).post(EMPTY).build()).execute().close()
        }
    }

    /**
     * Swaps the refresh token for new tokens. Synchronized: the token rotates on every use, so two
     * callers must not refresh at once. [stale] is the access token the caller saw fail; if another
     * thread already replaced it, that newer token is returned without asking the server again.
     */
    fun refreshBlocking(stale: String?): AbsTokens = synchronized(refreshLock) {
        val current = store.tokens() ?: throw AbsAuthException("Not signed in to podcasts & audiobooks")
        if (stale != null && current.accessToken != stale && !expiresSoon(current.accessToken)) return current
        val req = Request.Builder().url(url("auth/refresh")).header("x-refresh-token", current.refreshToken).post(EMPTY).build()
        plain.newCall(req).execute().use { resp ->
            if (resp.code == 401 || resp.code == 403) {
                store.onAuthLost()
                throw AbsAuthException("Your podcasts & audiobooks sign-in expired. Sign in again in Settings")
            }
            if (!resp.isSuccessful) throw AbsHttpException(resp.code, excerpt(resp))
            val user = json.decodeFromString(AbsLoginResponse.serializer(), resp.body!!.string()).user
            val fresh = AbsTokens(user.accessToken ?: throw AbsException("No access token in refresh response"), user.refreshToken ?: current.refreshToken)
            store.saveTokens(fresh)
            fresh
        }
    }

    private fun expiresSoon(token: String): Boolean {
        val exp = Jwt.expiresAt(token) ?: return false // tokens without expiry (old style) never need renewing
        return exp * 1000 - System.currentTimeMillis() - clock.offsetMs < 60_000
    }

    private inner class AuthInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val serverBase = store.serverUrl()?.toHttpUrlOrNull()
            // Only our own server ever sees the token.
            if (serverBase == null || request.url.host != serverBase.host || request.url.port != serverBase.port) return chain.proceed(request)
            var tokens = store.tokens() ?: throw AbsAuthException("Not signed in to podcasts & audiobooks")
            if (expiresSoon(tokens.accessToken)) tokens = refreshBlocking(tokens.accessToken)
            val response = chain.proceed(request.withToken(tokens.accessToken))
            if (response.code != 401) return response
            response.close()
            val renewed = refreshBlocking(tokens.accessToken)
            return chain.proceed(request.withToken(renewed.accessToken))
        }

        private fun Request.withToken(token: String) = newBuilder().header("Authorization", "Bearer $token").build()
    }

    private inner class ClockInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val sent = System.currentTimeMillis()
            val response = chain.proceed(chain.request())
            ClockSkew.parseHttpDate(response.header("Date"))?.let { clock.onSample(it, sent, System.currentTimeMillis()) }
            return response
        }
    }

    // ------------------------------------------------------------------ plumbing

    private suspend fun <T> call(request: Request, parse: ((String) -> T)?): T? = withContext(Dispatchers.IO) {
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw AbsHttpException(resp.code, excerpt(resp))
            parse?.invoke(resp.body?.string().orEmpty())
        }
    }

    private suspend fun <T> get(path: String, de: DeserializationStrategy<T>, query: Map<String, String> = emptyMap()): T =
        call(Request.Builder().url(url(path, query)).build()) { json.decodeFromString(de, it) }!!

    private suspend fun <T> send(method: String, path: String, body: JsonElement, de: DeserializationStrategy<T>?): T? =
        call(Request.Builder().url(url(path)).method(method, body.toString().toRequestBody(JSON)).build(), de?.let { d -> { s: String -> json.decodeFromString(d, s) } })

    private fun excerpt(resp: Response): String =
        im.flume.hearth.util.scrubSecrets(runCatching { resp.body?.string().orEmpty() }.getOrDefault("").take(160))

    // ------------------------------------------------------------------ API

    suspend fun me(): AbsUser = get("api/me", AbsUser.serializer())

    suspend fun libraries(): List<AbsLibrary> = get("api/libraries", AbsLibrariesResponse.serializer()).libraries

    /** One page of a library's items (not minified: podcasts include tags and metadata). */
    suspend fun libraryItems(libraryId: String, page: Int, limit: Int = 100): AbsItemsPage =
        get("api/libraries/$libraryId/items", AbsItemsPage.serializer(), mapOf("limit" to "$limit", "page" to "$page", "sort" to "media.metadata.title"))

    /** Every item of a library, page by page. */
    suspend fun allLibraryItems(libraryId: String): List<AbsLibraryItem> {
        val all = ArrayList<AbsLibraryItem>()
        var page = 0
        while (true) {
            val p = libraryItems(libraryId, page)
            all += p.results
            if (p.results.isEmpty() || all.size >= p.total || page > 200) break
            page++
        }
        return all
    }

    /** Episodes (podcast) or tracks and chapters (book). Null when the item no longer exists. */
    suspend fun item(libraryItemId: String): AbsLibraryItem? = try {
        get("api/items/$libraryItemId", AbsLibraryItem.serializer(), mapOf("expanded" to "1"))
    } catch (e: AbsHttpException) {
        if (e.code == 404) null else throw e
    }

    /** This user's progress on one item / episode, or null if they never started it. */
    suspend fun progress(libraryItemId: String, episodeId: String?): AbsMediaProgress? = try {
        get(listOfNotNull("api/me/progress", libraryItemId, episodeId).joinToString("/"), AbsMediaProgress.serializer())
    } catch (e: AbsHttpException) {
        if (e.code == 404) null else throw e
    }

    /** Creates or updates progress. [body] uses the server's field names (currentTime, duration, isFinished…). */
    suspend fun patchProgress(libraryItemId: String, episodeId: String?, body: JsonObject) {
        send<Unit>("PATCH", listOfNotNull("api/me/progress", libraryItemId, episodeId).joinToString("/"), body, null)
    }

    /** Uploads listening sessions recorded on this phone, in order. The server keeps the newest progress. */
    suspend fun syncLocalSessions(sessions: List<JsonObject>, deviceInfo: JsonObject): List<AbsLocalSyncResult> =
        send("POST", "api/session/local-all", buildJsonObject {
            put("sessions", JsonArray(sessions))
            put("deviceInfo", deviceInfo)
        }, AbsLocalSyncResponse.serializer())?.results.orEmpty()

    /** Replaces a podcast's or book's tags (read-modify-write: see FollowTags). */
    suspend fun setTags(libraryItemId: String, tags: List<String>) {
        send<Unit>("PATCH", "api/items/$libraryItemId/media", buildJsonObject { putJsonArray("tags") { tags.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } } }, null)
    }

    suspend fun setAutoDownload(libraryItemId: String, enabled: Boolean) {
        send<Unit>("PATCH", "api/items/$libraryItemId/media", buildJsonObject { put("autoDownloadEpisodes", enabled) }, null)
    }

    suspend fun searchPodcasts(term: String): List<AbsPodcastSearchResult> =
        get("api/search/podcast", ListSerializer(AbsPodcastSearchResult.serializer()), mapOf("term" to term))

    suspend fun podcastFeed(feedUrl: String): AbsFeed =
        send("POST", "api/podcasts/feed", buildJsonObject { put("rssFeed", feedUrl) }, AbsFeedResponse.serializer())!!.podcast

    /**
     * Adds a podcast to the library (admins only), as Audiobookshelf's own "add podcast" does: the
     * folder is [folderPath]/[sanitized title]. Returns the new item.
     */
    suspend fun createPodcast(
        libraryId: String,
        folderId: String,
        folderPath: String,
        feed: AbsFeedMetadata,
        feedUrl: String,
        itunes: AbsPodcastSearchResult?,
        tags: List<String>,
        autoDownload: Boolean,
    ): AbsLibraryItem {
        val title = feed.title ?: itunes?.title ?: throw AbsException("The feed has no title")
        val body = buildJsonObject {
            put("libraryId", libraryId)
            put("folderId", folderId)
            put("path", "${folderPath.trimEnd('/')}/${PodcastPaths.sanitizeFilename(title)}")
            putJsonObject("media") {
                putJsonObject("metadata") {
                    put("title", title)
                    (feed.author ?: itunes?.artistName)?.let { put("author", it) }
                    feed.description?.let { put("description", it) }
                    put("feedUrl", feedUrl)
                    (feed.image ?: itunes?.cover)?.let { put("imageUrl", it) }
                    itunes?.pageUrl?.let { put("itunesPageUrl", it) }
                    itunes?.id?.let { put("itunesId", it.toString()) }
                    feed.language?.let { put("language", it) }
                    put("explicit", feed.explicit == "yes" || feed.explicit == "true" || itunes?.explicit == true)
                    feed.type?.let { put("type", it) }
                    putJsonArray("genres") { (itunes?.genres ?: feed.categories).filter { it.isNotBlank() }.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
                }
                putJsonArray("tags") { tags.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
                put("autoDownloadEpisodes", autoDownload)
            }
        }
        return send("POST", "api/podcasts", body, AbsLibraryItem.serializer())!!
    }

    /** Reads the podcast feeds out of an OPML file (admins only). */
    suspend fun parseOpml(opmlText: String): List<AbsOpmlFeed> =
        send("POST", "api/podcasts/opml/parse", buildJsonObject { put("opmlText", opmlText) }, AbsOpmlParseResponse.serializer())!!.feeds

    /** Everyone with an account on the server (admins only). */
    suspend fun users(): List<AbsUser> = get("api/users", AbsUsersResponse.serializer()).users

    /** One user, with their listening progress (admins only). */
    suspend fun user(userId: String): AbsUser = get("api/users/$userId", AbsUser.serializer())

    /** Deletes an episode and its audio file from the server (admins only). */
    suspend fun deleteEpisode(libraryItemId: String, episodeId: String) {
        call<Unit>(Request.Builder().url(url("api/podcasts/$libraryItemId/episode/$episodeId", mapOf("hard" to "1"))).delete().build(), null)
    }

    /** Has the server download these feed episodes (objects as returned by [podcastFeed]). */
    suspend fun downloadEpisodesOnServer(libraryItemId: String, episodes: JsonArray) {
        send<Unit>("POST", "api/podcasts/$libraryItemId/download-episodes", episodes, null)
    }

    /** Episodes waiting in the server's download queue for this podcast (the one downloading right now isn't listed). */
    suspend fun episodeDownloadQueue(libraryItemId: String): List<AbsEpisodeDownload> =
        get("api/podcasts/$libraryItemId/downloads", AbsEpisodeDownloads.serializer()).downloads

    /** Asks the server to look for new episodes of a podcast now (admins only). */
    suspend fun checkNewEpisodes(libraryItemId: String) {
        call<Unit>(Request.Builder().url(url("api/podcasts/$libraryItemId/checknew")).build(), null)
    }

    /** Asks the server to look through a library for new or changed books (admins only). Returns before the scan ends. */
    suspend fun scanLibrary(libraryId: String) {
        send<Unit>("POST", "api/libraries/$libraryId/scan", JsonObject(emptyMap()), null)
    }

    /**
     * Uploads a book's audio files into a library folder, as Audiobookshelf's own upload page does: the
     * server saves them in "<folder>/<author>/<title>" and finds the book at its next scan. [onProgress]
     * gets the bytes sent so far (it can go back if the request is sent again after a token refresh).
     */
    suspend fun uploadBook(
        libraryId: String,
        folderId: String,
        title: String,
        author: String?,
        files: List<AbsUploadFile>,
        onProgress: (Long) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        val sent = LongArray(files.size)
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("title", title)
            .apply { author?.takeIf { it.isNotBlank() }?.let { addFormDataPart("author", it) } }
            .addFormDataPart("library", libraryId)
            .addFormDataPart("folder", folderId)
        files.forEachIndexed { i, f ->
            form.addFormDataPart(i.toString(), f.name, object : RequestBody() {
                override fun contentType() = (f.mimeType ?: "application/octet-stream").toMediaType()
                override fun contentLength() = f.size.takeIf { it > 0 } ?: -1L
                override fun writeTo(sink: okio.BufferedSink) {
                    sent[i] = 0
                    f.open().use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            sink.write(buf, 0, n)
                            sent[i] += n
                            onProgress(sent.sum())
                        }
                    }
                }
            })
        }
        // A big book takes a while to send, and the server answers only once every file is saved.
        val client = http.newBuilder().writeTimeout(5, java.util.concurrent.TimeUnit.MINUTES).readTimeout(10, java.util.concurrent.TimeUnit.MINUTES).build()
        client.newCall(Request.Builder().url(url("api/upload")).post(form.build()).build()).execute().use { resp ->
            if (resp.code == 403) throw AbsException("Your account isn't allowed to upload to the server")
            if (!resp.isSuccessful) throw AbsHttpException(resp.code, excerpt(resp))
        }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val EMPTY = ByteArray(0).toRequestBody(null)

        fun normalizeUrl(raw: String): String {
            var url = raw.trim().trimEnd('/')
            if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
            return url
        }
    }
}

/** One file to upload: its name on the server, size in bytes (0 if unknown) and how to read it. */
class AbsUploadFile(val name: String, val size: Long, val mimeType: String?, val open: () -> java.io.InputStream)

/** Mirrors Audiobookshelf's sanitizeFilename, so a podcast lands in the folder its own app would pick. */
object PodcastPaths {
    private val illegal = Regex("""[/?<>\\:*|"]""")
    private val control = Regex("[\\x00-\\x1f\\x80-\\x9f]")
    private val reserved = Regex("^\\.+$")
    private val windowsReserved = Regex("^(con|prn|aux|nul|com[0-9]|lpt[0-9])(\\..*)?$", RegexOption.IGNORE_CASE)
    private val windowsTrailing = Regex("[. ]+$")

    fun sanitizeFilename(name: String): String {
        val s = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFC)
            .replaceFirst(":", " - ")
            .replace(illegal, "")
            .replace(control, "")
            .replace(reserved, "")
            .replace(Regex("[\\n\\r]"), "")
            .replace(windowsReserved, "")
            .replace(windowsTrailing, "")
            .replace(Regex("\\s+"), " ")
        // 255 bytes of UTF-16 = 127 chars; trim like the server does for very long titles.
        return if (s.length > 127) s.take(127).trimEnd() else s
    }
}
