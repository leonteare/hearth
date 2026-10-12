package im.flume.hearth.core.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.structuralEqualityPolicy
import im.flume.hearth.core.data.DownloadState

/**
 * What song rows need to know: what's playing, whether we're online, and every song's download
 * state. Unlike [RowContext] this is one object that never changes, holding snapshot state, and
 * rows read their own slice of it through [rememberSongRowState]. So while an album downloads,
 * each finished song redraws its own row, not every row on screen.
 */
@Stable
class LiveRows {
    var playingId by mutableStateOf<String?>(null)
    var online by mutableStateOf(true)
    /** Replaced whole; the repository only sends a new map when a state actually changed. */
    var downloads by mutableStateOf<Map<String, DownloadState>>(emptyMap(), referentialEqualityPolicy())
}

val LocalLiveRows = staticCompositionLocalOf { LiveRows() }

/** One song row's view of [LiveRows]. */
@Immutable
data class SongRowState(val downloadState: DownloadState?, val isPlaying: Boolean, val online: Boolean) {
    /** Online, or downloaded: something that can be played right now. */
    val playable: Boolean get() = online || downloadState == DownloadState.DONE

    companion object {
        fun of(songId: String, playingId: String?, online: Boolean, downloads: Map<String, DownloadState>) =
            SongRowState(downloads[songId], playingId == songId, online)
    }
}

/** This song's [SongRowState]; whoever reads it only redraws when this song's own state changes. */
@Composable
fun rememberSongRowState(songId: String): State<SongRowState> {
    val live = LocalLiveRows.current
    return remember(live, songId) {
        derivedStateOf(structuralEqualityPolicy()) { SongRowState.of(songId, live.playingId, live.online, live.downloads) }
    }
}
