package im.flume.hearth.spoken

import android.content.Context
import androidx.core.content.edit
import im.flume.hearth.api.AbsAuthStore
import im.flume.hearth.api.AbsTokens
import im.flume.hearth.data.PasswordVault
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Who is signed in to Audiobookshelf on this phone. */
data class AbsAccount(
    val serverUrl: String,
    val username: String,
    val userId: String,
    /** The refresh token was refused: playback of downloads still works, but syncing needs a new sign-in. */
    val needsSignIn: Boolean = false,
)

data class SpokenSettings(
    /** Download new episodes of followed shows automatically, on Wi-Fi only. */
    val autoDownloadOnWifi: Boolean = true,
)

/**
 * The Audiobookshelf login. Server, username and user id live in private prefs; the access and refresh
 * tokens only encrypted, in their own [PasswordVault]. The password itself is never stored.
 */
class AbsAccountStore(context: Context) : AbsAuthStore {
    private val prefs = context.getSharedPreferences("abs", Context.MODE_PRIVATE)
    private val vault = PasswordVault(context, prefsName = "abs-vault", alias = "hearth-abs-tokens")
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class StoredTokens(val access: String, val refresh: String)

    @Volatile private var cached: AbsTokens? = null
    @Volatile private var loaded = false

    private val _account = MutableStateFlow(readAccount())
    val account: StateFlow<AbsAccount?> = _account.asStateFlow()

    private val _settings = MutableStateFlow(SpokenSettings(autoDownloadOnWifi = prefs.getBoolean("autoDownloadOnWifi", true)))
    val settings: StateFlow<SpokenSettings> = _settings.asStateFlow()

    /** The last server address used (kept after sign-out, to prefill the form). */
    val lastServerUrl: String get() = prefs.getString("lastServerUrl", null) ?: DEFAULT_SERVER_URL
    val lastUsername: String get() = prefs.getString("lastUsername", null).orEmpty()

    /** Phone clock vs server clock, remembered so offline timestamps are right before the first request. */
    var clockOffsetMs: Long
        get() = prefs.getLong("clockOffsetMs", 0)
        set(v) = prefs.edit { putLong("clockOffsetMs", v) }

    /** A random id for this phone, so the server's listening stats show which device it was. */
    val deviceId: String
        get() = prefs.getString("deviceId", null) ?: UUID.randomUUID().toString().also { id -> prefs.edit { putString("deviceId", id) } }

    var lastFullSyncAt: Long
        get() = prefs.getLong("lastFullSyncAt", 0)
        set(v) = prefs.edit { putLong("lastFullSyncAt", v) }

    /** Recent follow / unfollow choices made on this phone (see FollowTags.heal), by podcast id. */
    fun followIntents(): Map<String, FollowIntent> = runCatching {
        json.decodeFromString(ListSerializer(FollowIntent.serializer()), prefs.getString("followIntents", null) ?: "[]")
    }.getOrDefault(emptyList()).associateBy { it.podcastId }

    @Synchronized
    fun updateFollowIntents(transform: (Map<String, FollowIntent>) -> Map<String, FollowIntent>) {
        val next = transform(followIntents()).values.toList()
        prefs.edit { putString("followIntents", json.encodeToString(ListSerializer(FollowIntent.serializer()), next)) }
    }

    fun updateSettings(transform: (SpokenSettings) -> SpokenSettings) {
        val s = transform(_settings.value)
        prefs.edit { putBoolean("autoDownloadOnWifi", s.autoDownloadOnWifi) }
        _settings.value = s
    }

    private fun readAccount(): AbsAccount? {
        val url = prefs.getString("serverUrl", null) ?: return null
        val user = prefs.getString("username", null) ?: return null
        val id = prefs.getString("userId", null) ?: return null
        return AbsAccount(url, user, id, needsSignIn = prefs.getBoolean("needsSignIn", false))
    }

    fun signedIn(serverUrl: String, username: String, userId: String, tokens: AbsTokens) {
        saveTokens(tokens)
        prefs.edit {
            putString("serverUrl", serverUrl)
            putString("username", username)
            putString("userId", userId)
            putString("lastServerUrl", serverUrl)
            putString("lastUsername", username)
            putBoolean("needsSignIn", false)
        }
        _account.value = AbsAccount(serverUrl, username, userId)
    }

    fun signOut() {
        prefs.edit { listOf("serverUrl", "username", "userId", "needsSignIn", "lastFullSyncAt", "followIntents").forEach(::remove) }
        vault.clear()
        cached = null
        _account.value = null
    }

    override fun serverUrl(): String? = _account.value?.serverUrl

    override fun tokens(): AbsTokens? {
        if (!loaded) synchronized(this) {
            if (!loaded) {
                cached = vault.read()?.let { runCatching { json.decodeFromString(StoredTokens.serializer(), it) }.getOrNull() }
                    ?.let { AbsTokens(it.access, it.refresh) }
                loaded = true
            }
        }
        return cached
    }

    override fun saveTokens(tokens: AbsTokens) = synchronized(this) {
        vault.save(json.encodeToString(StoredTokens.serializer(), StoredTokens(tokens.accessToken, tokens.refreshToken)))
        cached = tokens
        loaded = true
        if (_account.value?.needsSignIn == true) {
            prefs.edit { putBoolean("needsSignIn", false) }
            _account.value = _account.value?.copy(needsSignIn = false)
        }
    }

    override fun onAuthLost() {
        prefs.edit { putBoolean("needsSignIn", true) }
        _account.value = _account.value?.copy(needsSignIn = true)
    }

    companion object {
        const val DEFAULT_SERVER_URL = "https://listen.home"
    }
}

/** A follow / unfollow this user made on this phone, kept for [FollowTags.heal]. */
@Serializable
data class FollowIntent(val podcastId: String, val username: String, val follow: Boolean, val at: Long)
