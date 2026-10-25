// Kiosk profiles: the Navidrome logins the family uses on a kiosk phone, so each child keeps their own
// playlists and likes, plus each one's Audiobookshelf login so audiobooks follow the profile. Saved in the
// app's own storage (passwords in a PasswordVault); the regular app never reads them.
package im.flume.hearth.ui

import android.content.Context
import androidx.core.content.edit
import im.flume.hearth.core.data.PasswordVault
import im.flume.hearth.core.network.Credentials
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The rules for the saved list, kept pure so they can be tested: one entry per server and person. */
object Profiles {
    private const val SEP = '\t'

    fun encode(c: Credentials): String = listOf(c.serverUrl, c.username, c.salt, c.token).joinToString(SEP.toString())

    /** The reverse of [encode]; null for anything that isn't a complete saved login. */
    fun decode(s: String): Credentials? {
        val p = s.split(SEP)
        if (p.size != 4 || p.any { it.isEmpty() }) return null
        return Credentials(p[0], p[1], p[2], p[3])
    }

    /** What identifies a person across sign-ins: the server and the name, whatever the capitals. */
    fun key(c: Credentials): String = c.serverUrl + "|" + c.username.lowercase()

    /** The same person: same server, same name whatever the capitals. */
    fun same(a: Credentials, b: Credentials): Boolean = key(a) == key(b)

    /** Adds [c], or replaces that person's entry (the token changes on every sign-in). Order is kept. */
    fun upsert(list: List<Credentials>, c: Credentials): List<Credentials> =
        if (list.any { same(it, c) }) list.map { if (same(it, c)) c else it } else list + c

    fun remove(list: List<Credentials>, c: Credentials): List<Credentials> = list.filterNot { same(it, c) }

    /** "alice" reads as "Alice" on a card. */
    fun displayName(username: String): String = username.replaceFirstChar { it.uppercase() }
}

/** An Audiobookshelf login kept for one profile. The password is needed because switching signs in afresh. */
data class AbsLogin(val serverUrl: String, val username: String, val password: String)

/** Where the kiosk phone keeps the family's logins. Every Navidrome sign-in is recorded (see MainActivity). */
class KioskProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences("kiosk-profiles", Context.MODE_PRIVATE)
    private val absVault = PasswordVault(context, prefsName = "kiosk-abs-vault", alias = "hearth-kiosk-abs")

    private val _profiles = MutableStateFlow(read())
    val profiles: StateFlow<List<Credentials>> = _profiles.asStateFlow()

    /** Profile key ([Profiles.key]) to the Audiobookshelf username kept for it. */
    private val _absUsers = MutableStateFlow(readAbsUsers())
    val absUsers: StateFlow<Map<String, String>> = _absUsers.asStateFlow()

    private fun read(): List<Credentials> =
        prefs.getString("list", null)?.lines()?.mapNotNull(Profiles::decode).orEmpty()

    private fun write(list: List<Credentials>) {
        prefs.edit { putString("list", list.joinToString("\n", transform = Profiles::encode)) }
        _profiles.value = list
    }

    fun record(c: Credentials) = write(Profiles.upsert(_profiles.value, c))

    fun forget(c: Credentials) {
        write(Profiles.remove(_profiles.value, c))
        clearAbsLogin(c)
    }

    private fun readAbsUsers(): Map<String, String> =
        prefs.all.entries
            .filter { it.key.startsWith(ABS_USER) && it.value is String }
            .associate { it.key.removePrefix(ABS_USER) to it.value as String }

    fun absLogin(c: Credentials): AbsLogin? {
        val k = Profiles.key(c)
        val url = prefs.getString(ABS_URL + k, null) ?: return null
        val user = prefs.getString(ABS_USER + k, null) ?: return null
        val password = passwords()[k] ?: return null
        return AbsLogin(url, user, password)
    }

    fun saveAbsLogin(c: Credentials, login: AbsLogin) {
        val k = Profiles.key(c)
        prefs.edit {
            putString(ABS_URL + k, login.serverUrl)
            putString(ABS_USER + k, login.username)
        }
        writePasswords(passwords() + (k to login.password))
        _absUsers.value = readAbsUsers()
    }

    fun clearAbsLogin(c: Credentials) {
        val k = Profiles.key(c)
        prefs.edit {
            remove(ABS_URL + k)
            remove(ABS_USER + k)
        }
        writePasswords(passwords() - k)
        _absUsers.value = readAbsUsers()
    }

    /** All profiles' Audiobookshelf passwords, one encrypted blob: "key<TAB>password" per line. */
    private fun passwords(): Map<String, String> =
        absVault.read()?.lines().orEmpty()
            .mapNotNull { line -> line.split('\t').takeIf { it.size == 2 }?.let { it[0] to it[1] } }
            .toMap()

    private fun writePasswords(all: Map<String, String>) {
        if (all.isEmpty()) absVault.clear()
        else absVault.save(all.entries.joinToString("\n") { "${it.key}\t${it.value}" })
    }

    private companion object {
        const val ABS_URL = "abs-url:"
        const val ABS_USER = "abs-user:"
    }
}
