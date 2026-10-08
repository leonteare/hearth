package im.flume.hearth.data

import android.content.Context
import androidx.core.content.edit
import im.flume.hearth.api.Credentials
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Settings(
    /** kbps, 0 = original quality */
    val wifiBitrate: Int = 0,
    val mobileBitrate: Int = 0,
    val downloadBitrate: Int = 0,
    val wifiOnlyDownloads: Boolean = true,
    /** Songs downloaded side by side; the server converts each on its own processor core. */
    val parallelDownloads: Int = 1,
    /** Downloads stopped by the user; the queue is kept until they resume. */
    val downloadsPaused: Boolean = false,
    val cacheSizeMb: Int = 2048,
    val offlineMode: Boolean = false,
    /** ARGB of the app's accent colour. */
    val accent: Int = 0xFFFF8A3D.toInt(),
    /** 0 = small, 1 = medium, 2 = large, 3 = extra large */
    val lyricsSize: Int = 1,
    /** 0 = compact, 1 = normal, 2 = relaxed */
    val lyricsSpacing: Int = 1,
    /** 0 = off, 1 = per track, 2 = per album */
    val volumeLevelling: Int = 1,
    /** Spread out songs by the same artist when shuffling. */
    val smartShuffle: Boolean = true,
)

/**
 * Login and preferences. Lives in private app storage; login uses the Subsonic token. The password is
 * kept only encrypted, in [vault] (saved at sign-in), for playlist photos.
 */
class SessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)
    val vault = PasswordVault(context)

    private val _credentials = MutableStateFlow(readCredentials())
    val credentials: StateFlow<Credentials?> = _credentials.asStateFlow()

    private val _settings = MutableStateFlow(readSettings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    var lastSyncAt: Long
        get() = prefs.getLong("lastSyncAt", 0)
        set(v) = prefs.edit { putLong("lastSyncAt", v) }

    /** Bumped when a release needs fields that only a full sync fills in. */
    var syncedSchema: Int
        get() = prefs.getInt("syncedSchema", 0)
        set(v) = prefs.edit { putInt("syncedSchema", v) }

    /** Set once downloaded albums have been saved to the (now personal) Your Library. */
    var savedDownloadedAlbums: Boolean
        get() = prefs.getBoolean("savedDownloadedAlbums", false)
        set(v) = prefs.edit { putBoolean("savedDownloadedAlbums", v) }

    var lastScan: String?
        get() = prefs.getString("lastScan", null)
        set(v) = prefs.edit { putString("lastScan", v) }

    private val _playlistDecisions = MutableStateFlow(readDecisions())

    /**
     * Playlist invites answered on this phone (id to true = accepted, false = declined or left). Only
     * needed when the answer couldn't be written to the server, but always applied so the UI is instant.
     */
    val playlistDecisions: StateFlow<Map<String, Boolean>> = _playlistDecisions.asStateFlow()

    private fun readDecisions(): Map<String, Boolean> =
        prefs.getStringSet("acceptedPlaylists", emptySet()).orEmpty().associateWith { true } +
            prefs.getStringSet("declinedPlaylists", emptySet()).orEmpty().associateWith { false }

    fun setPlaylistDecision(playlistId: String, accepted: Boolean?) {
        val next = _playlistDecisions.value.toMutableMap().apply {
            if (accepted == null) remove(playlistId) else put(playlistId, accepted)
        }
        prefs.edit {
            putStringSet("acceptedPlaylists", next.filterValues { it }.keys)
            putStringSet("declinedPlaylists", next.filterValues { !it }.keys)
        }
        _playlistDecisions.value = next
    }

    private fun readCredentials(): Credentials? {
        val url = prefs.getString("serverUrl", null) ?: return null
        val user = prefs.getString("username", null) ?: return null
        val salt = prefs.getString("salt", null) ?: return null
        val token = prefs.getString("token", null) ?: return null
        return Credentials(url, user, salt, token)
    }

    fun saveCredentials(c: Credentials) {
        prefs.edit {
            putString("serverUrl", c.serverUrl)
            putString("username", c.username)
            putString("salt", c.salt)
            putString("token", c.token)
        }
        _credentials.value = c
    }

    fun logout() {
        prefs.edit { clear() }
        vault.clear()
        _playlistDecisions.value = emptyMap()
        _credentials.value = null
        _settings.value = Settings()
    }

    private fun readSettings() = Settings(
        wifiBitrate = prefs.getInt("wifiBitrate", 0),
        mobileBitrate = prefs.getInt("mobileBitrate", 0),
        downloadBitrate = prefs.getInt("downloadBitrate", 0),
        wifiOnlyDownloads = prefs.getBoolean("wifiOnlyDownloads", true),
        parallelDownloads = prefs.getInt("parallelDownloads", 1),
        downloadsPaused = prefs.getBoolean("downloadsPaused", false),
        cacheSizeMb = prefs.getInt("cacheSizeMb", 2048),
        offlineMode = prefs.getBoolean("offlineMode", false),
        accent = prefs.getInt("accent", 0xFFFF8A3D.toInt()),
        lyricsSize = prefs.getInt("lyricsSize", 1),
        lyricsSpacing = prefs.getInt("lyricsSpacing", 1),
        volumeLevelling = prefs.getInt("volumeLevelling", 1),
        smartShuffle = prefs.getBoolean("smartShuffle", true),
    )

    fun updateSettings(transform: (Settings) -> Settings) {
        val s = transform(_settings.value)
        prefs.edit {
            putInt("wifiBitrate", s.wifiBitrate)
            putInt("mobileBitrate", s.mobileBitrate)
            putInt("downloadBitrate", s.downloadBitrate)
            putBoolean("wifiOnlyDownloads", s.wifiOnlyDownloads)
            putInt("parallelDownloads", s.parallelDownloads)
            putBoolean("downloadsPaused", s.downloadsPaused)
            putInt("cacheSizeMb", s.cacheSizeMb)
            putBoolean("offlineMode", s.offlineMode)
            putInt("accent", s.accent)
            putInt("lyricsSize", s.lyricsSize)
            putInt("lyricsSpacing", s.lyricsSpacing)
            putInt("volumeLevelling", s.volumeLevelling)
            putBoolean("smartShuffle", s.smartShuffle)
        }
        _settings.value = s
    }
}
