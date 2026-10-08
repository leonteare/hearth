package im.flume.hearth.data

import android.content.Context
import androidx.core.content.edit

/**
 * Counts how often each feature gets used, so unused ones can be spotted and removed. Stays on the
 * phone: nothing is sent anywhere. Shown in Settings → General.
 */
class Usage(context: Context) {
    private val prefs = context.getSharedPreferences("usage", Context.MODE_PRIVATE)

    fun track(feature: String) = prefs.edit { putInt(feature, prefs.getInt(feature, 0) + 1) }

    fun all(): List<Pair<String, Int>> = FEATURES.map { it to prefs.getInt(it, 0) }

    companion object {
        const val RADIO = "Radio"
        const val MIX = "Mixes"
        const val LYRICS = "Lyrics"
        const val SLEEP = "Sleep timer"
        const val QUEUE = "Queue"
        const val SEARCH = "Search"
        const val STATS = "Monthly stats"
        const val DOWNLOAD = "Downloads"
        const val PLAYLIST_ADD = "Add to playlist"
        const val MULTI_SELECT = "Selecting several songs"
        const val SWIPE_SKIP = "Swipe to skip"
        const val SHUFFLE = "Shuffle"
        val FEATURES = listOf(RADIO, MIX, LYRICS, SLEEP, QUEUE, SEARCH, STATS, DOWNLOAD, PLAYLIST_ADD, MULTI_SELECT, SWIPE_SKIP, SHUFFLE)
    }
}
