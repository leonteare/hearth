// Helpers shared by the album and artist pages: loading remote pages and matching tracks to owned songs.
package im.flume.hearth.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import im.flume.hearth.api.TidalTrack
import im.flume.hearth.container
import im.flume.hearth.data.SongEntity
import im.flume.hearth.data.SongItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * One album page and one artist page, whether or not the server has the music. Pages for music on the
 * server show it straight away; what it doesn't have is looked up in the background and slides in.
 * Pages for music it doesn't have ("tidal:" ids) render the same layout from the remote catalogue.
 */

internal sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data object Failed : Load<Nothing>
    data class Ready<T>(val value: T) : Load<T>
}

@Composable
internal fun <T> rememberLoad(key: String, load: suspend () -> T): Load<T> {
    var state by remember(key) { mutableStateOf<Load<T>>(Load.Loading) }
    LaunchedEffect(key) {
        state = try {
            Load.Ready(withContext(Dispatchers.IO) { load() })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Load.Failed
        }
    }
    return state
}

/** True when pages should look up music the server doesn't have. */
@Composable
internal fun lookupsOn(): Boolean {
    val c = LocalContext.current.container
    val settings by c.session.settings.collectAsStateWithLifecycle()
    val online by c.network.isOnline.collectAsStateWithLifecycle()
    return settings.tidalSearchEnabled && online
}

/** Songs the server has for each track, one song per track at most (so list keys stay unique). */
internal fun ownedItems(tracks: List<TidalTrack>, owned: Map<String, SongEntity>): List<SongItem> {
    val used = HashSet<String>()
    return tracks.map { t ->
        val song = owned[t.id]?.takeIf { used.add(it.id) }
        if (song != null) SongItem.Owned(song) else SongItem.Missing(t)
    }
}
