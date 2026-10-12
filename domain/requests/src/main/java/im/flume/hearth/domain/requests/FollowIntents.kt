// Artists followed before the server has any of their music, kept until their music arrives.
package im.flume.hearth.domain.requests

import android.content.Context
import im.flume.hearth.core.data.SearchIndex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Normalised artist names, saved in the "get-music" preferences so they outlive the app. */
internal class FollowIntents(context: Context) {
    private val prefs = context.getSharedPreferences("get-music", Context.MODE_PRIVATE)
    private val _names = MutableStateFlow(prefs.getStringSet("follow", emptySet()).orEmpty().toSet())
    val names: StateFlow<Set<String>> = _names

    fun set(name: String, follow: Boolean) {
        val n = SearchIndex.norm(name)
        val next = if (follow) _names.value + n else _names.value - n
        _names.value = next
        prefs.edit().putStringSet("follow", next).apply()
    }

    fun contains(name: String): Boolean = SearchIndex.norm(name) in _names.value
}
