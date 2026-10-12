// The theme and composition locals every screen expects, for tests.
package im.flume.hearth.core.testing

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import im.flume.hearth.core.data.DownloadState
import im.flume.hearth.domain.music.PlaySource
import im.flume.hearth.core.data.SongEntity
import im.flume.hearth.core.ui.components.Actions
import im.flume.hearth.core.ui.components.LiveRows
import im.flume.hearth.core.ui.components.LocalActions
import im.flume.hearth.core.ui.components.LocalLiveRows
import im.flume.hearth.core.ui.components.LocalRowContext
import im.flume.hearth.core.ui.components.LocalTabRequests
import im.flume.hearth.core.ui.components.RowContext
import im.flume.hearth.core.ui.components.TabRequests
import im.flume.hearth.core.ui.theme.Background
import im.flume.hearth.core.ui.theme.HearthTheme

/**
 * [Actions] that do nothing, except remember what was asked of them so a test can check it. Covers are
 * always null, so rows draw their initials placeholder instead of loading an image.
 */
open class FakeActions : Actions {
    val calls = mutableListOf<String>()
    private fun note(call: String) { calls += call }

    override fun play(source: PlaySource, startSongId: String?, shuffle: Boolean) = note("play")
    override fun playNext(songIds: List<String>) = note("playNext $songIds")
    override fun addToQueue(songIds: List<String>) = note("addToQueue $songIds")
    override fun setStarred(songId: String, starred: Boolean) = note("setStarred $songId $starred")
    override fun download(songs: List<SongEntity>) = note("download ${songs.map { it.id }}")
    override fun removeDownload(songIds: List<String>) = note("removeDownload $songIds")
    override fun startRadio(song: SongEntity) = note("startRadio ${song.id}")
    override fun startArtistRadio(artistId: String, name: String) = note("startArtistRadio $artistId")
    override fun addToPlaylist(songIds: List<String>) = note("addToPlaylist $songIds")
    override fun openAlbum(id: String) = note("openAlbum $id")
    override fun openArtist(id: String) = note("openArtist $id")
    override fun openPlaylist(id: String) = note("openPlaylist $id")
    override fun openGenre(name: String) = note("openGenre $name")
    override fun open(route: String) = note("open $route")
    override fun showUndo(message: String, long: Boolean, undo: suspend () -> Unit) = note("showUndo $message")
    override fun showMessage(message: String) = note("showMessage $message")
    override fun coverUrl(coverArt: String?, size: Int): String? = null
}

/** The [LiveRows] song rows read: what's playing, whether we're online, and download states. */
fun liveRows(playingId: String? = null, online: Boolean = true, downloads: Map<String, DownloadState> = emptyMap()) =
    LiveRows().apply {
        this.playingId = playingId
        this.online = online
        this.downloads = downloads
    }

/**
 * [HearthTheme] (today's dark look, default accent) plus the locals the root normally provides: [actions],
 * the song rows' [live] state and tab requests. Content sits on the app's page background, like a real page.
 */
@Composable
fun HearthTestTheme(
    actions: Actions = FakeActions(),
    live: LiveRows = liveRows(),
    content: @Composable () -> Unit,
) {
    HearthTheme {
        CompositionLocalProvider(
            LocalActions provides actions,
            LocalLiveRows provides live,
            LocalRowContext provides RowContext(live.playingId, live.online, live.downloads),
            LocalTabRequests provides TabRequests(),
        ) {
            // As MainActivity does: the page background, with white as the default text and icon colour.
            Surface(color = Background, contentColor = Color.White) { content() }
        }
    }
}
