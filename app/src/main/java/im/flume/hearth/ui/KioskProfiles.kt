// Kiosk profiles: the Navidrome logins the family uses on a kiosk phone, so each child keeps their own
// playlists and likes. Saved in the app's own storage; the regular app never reads them.
package im.flume.hearth.ui

import android.content.Context
import androidx.core.content.edit
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

    /** The same person: same server, same name whatever the capitals. */
    fun same(a: Credentials, b: Credentials): Boolean =
        a.serverUrl == b.serverUrl && a.username.equals(b.username, ignoreCase = true)

    /** Adds [c], or replaces that person's entry (the token changes on every sign-in). Order is kept. */
    fun upsert(list: List<Credentials>, c: Credentials): List<Credentials> =
        if (list.any { same(it, c) }) list.map { if (same(it, c)) c else it } else list + c

    fun remove(list: List<Credentials>, c: Credentials): List<Credentials> = list.filterNot { same(it, c) }

    /** "alice" reads as "Alice" on a card. */
    fun displayName(username: String): String = username.replaceFirstChar { it.uppercase() }
}

/** Where the kiosk phone keeps the family's logins. Every sign-in is recorded (see MainActivity). */
class KioskProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences("kiosk-profiles", Context.MODE_PRIVATE)
    private val _profiles = MutableStateFlow(read())
    val profiles: StateFlow<List<Credentials>> = _profiles.asStateFlow()

    private fun read(): List<Credentials> =
        prefs.getString("list", null)?.lines()?.mapNotNull(Profiles::decode).orEmpty()

    private fun write(list: List<Credentials>) {
        prefs.edit { putString("list", list.joinToString("\n", transform = Profiles::encode)) }
        _profiles.value = list
    }

    fun record(c: Credentials) = write(Profiles.upsert(_profiles.value, c))

    fun forget(c: Credentials) = write(Profiles.remove(_profiles.value, c))
}
