package im.flume.hearth.ui

// What rows, cards and menus can do anywhere in the app (the Actions behind LocalActions): play, queue, like,
// download, open pages, show messages with Undo, plus the playlist dialogs those actions open.

import android.net.Uri
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.navigation.NavHostController
import im.flume.hearth.core.playback.AppContainer
import im.flume.hearth.domain.music.PlaySource
import im.flume.hearth.domain.music.PlaylistRules
import im.flume.hearth.core.domain.SearchFilter
import im.flume.hearth.core.data.SongEntity
import im.flume.hearth.domain.music.Usage
import im.flume.hearth.core.ui.components.Actions
import im.flume.hearth.core.ui.components.NewPlaylistDialog
import im.flume.hearth.core.ui.components.PlaylistPickerDialog
import im.flume.hearth.core.ui.components.TabRequests
import im.flume.hearth.core.common.couldNot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class AppActions(
    private val c: AppContainer,
    private val nav: NavHostController,
    private val scope: CoroutineScope,
    private val snackbar: SnackbarHostState,
) : Actions {
    override fun play(source: PlaySource, startSongId: String?, shuffle: Boolean) {
        if (shuffle) c.usage.track(Usage.SHUFFLE)
        if (!c.network.isOnline.value) {
            scope.launch { snackbar.showSnackbar("Offline: playing downloaded songs only") }
        }
        c.player.play(source, startSongId, shuffle)
    }

    override fun showUndo(message: String, long: Boolean, undo: suspend () -> Unit) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar(message, actionLabel = "Undo", duration = if (long) SnackbarDuration.Long else SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) c.appScope.launch { runCatching { undo() } }
        }
    }

    override fun showMessage(message: String) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(message, duration = SnackbarDuration.Short)
        }
    }

    override fun playNext(songIds: List<String>) {
        haptic(HapticFeedbackType.Confirm)
        c.player.playNext(songIds)
        if (c.player.state.value.spoken != null) return showMessage(SPOKEN_QUEUE_MESSAGE)
        showUndo(if (songIds.size == 1) "Playing next" else "${songIds.size} songs will play next", long = false) { c.player.removeQueued(songIds) }
    }

    override fun addToQueue(songIds: List<String>) {
        haptic(HapticFeedbackType.Confirm)
        c.player.addToQueue(songIds)
        if (c.player.state.value.spoken != null) return showMessage(SPOKEN_QUEUE_MESSAGE)
        showUndo(if (songIds.size == 1) "Added to the queue" else "Added ${songIds.size} songs to the queue", long = false) { c.player.removeQueued(songIds) }
    }

    // Likes stay on the phone and reach the server when they can, so there is nothing to report when it's away.
    override fun setStarred(songId: String, starred: Boolean) {
        haptic(if (starred) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
        scope.launch {
            c.library.setStarred(songId, starred)
            if (!starred) showUndo("Removed from Liked Songs") { c.library.setStarred(songId, true) }
        }
    }

    override fun setAlbumSaved(albumId: String, saved: Boolean) {
        haptic(if (saved) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
        c.appScope.launch {
            c.library.setAlbumSaved(albumId, saved)
            if (saved) showMessage("Saved to Your Library")
            else showUndo("Removed from Your Library") { c.library.setAlbumSaved(albumId, true) }
        }
    }

    override fun setArtistFollowed(artistId: String, followed: Boolean) {
        haptic(if (followed) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
        c.appScope.launch {
            c.library.setArtistFollowed(artistId, followed)
            if (!followed) showUndo("Unfollowed") { c.library.setArtistFollowed(artistId, true) }
        }
    }

    /** Why [what] failed, in plain words: the connection when the phone is offline, otherwise what [e] says. */
    private fun failed(what: String, e: Throwable?) = couldNot(what, e, offline = !c.network.isOnline.value)

    var haptics: HapticFeedback? = null
    private fun haptic(type: HapticFeedbackType) { haptics?.performHapticFeedback(type) }

    override fun download(songs: List<SongEntity>) {
        c.usage.track(Usage.DOWNLOAD)
        c.downloads.download(songs)
        scope.launch {
            val wifi = if (c.session.settings.value.wifiOnlyDownloads && !c.network.wifi.value) " (waits for Wi-Fi)" else ""
            snackbar.showSnackbar("Downloading ${songs.size} song${if (songs.size == 1) "" else "s"}$wifi")
        }
    }

    override fun removeDownload(songIds: List<String>) {
        c.downloads.remove(songIds)
        showUndo(if (songIds.size == 1) "Download removed" else "${songIds.size} downloads removed") {
            c.downloads.download(c.library.songsByIds(songIds))
        }
    }
    override fun openAlbum(id: String) { closeOverlays(); nav.navigate("album/${Uri.encode(id)}") }
    override fun openArtist(id: String) { closeOverlays(); nav.navigate("artist/${Uri.encode(id)}") }
    override fun openPlaylist(id: String) { closeOverlays(); nav.navigate("playlist/${Uri.encode(id)}") }
    override fun openGenre(name: String) { closeOverlays(); nav.navigate("genre/${Uri.encode(name)}") }
    override fun open(route: String) {
        when {
            route.startsWith("mix/") -> c.usage.track(Usage.MIX)
            route.startsWith("stats/") -> c.usage.track(Usage.STATS)
        }
        closeOverlays()
        nav.navigate(route)
    }
    // A saved cover wins: downloaded music keeps its picture with no server and an emptied image cache.
    override fun coverUrl(coverArt: String?, size: Int): String? =
        c.covers.fileFor(coverArt)?.let { "file://${it.path}" } ?: c.api.coverArtUrl(coverArt, size)

    /** True while the "New playlist" name dialog (from Your Library) is open. */
    val creatingPlaylist = mutableStateOf(false)
    override fun newPlaylist() { creatingPlaylist.value = true }

    /** Closes the player and queue overlays, so pages opened from them aren't hidden underneath. */
    var closeOverlays: () -> Unit = {}

    /** Goes to a bottom tab the same way tapping it does (set by [AppRoot]). */
    var switchTab: (String) -> Unit = {}
    val tabRequests = TabRequests()

    override fun openLibraryTab(tab: String) {
        closeOverlays()
        tabRequests.libraryTab = tab
        switchTab("library")
    }

    override fun openSearchTab(filter: SearchFilter) {
        closeOverlays()
        tabRequests.searchFilter = filter
        switchTab("search")
    }

    fun createEmptyPlaylist(name: String) {
        creatingPlaylist.value = false
        scope.launch {
            val result = runCatching { c.library.createPlaylist(name, emptyList()) }
            val id = result.getOrNull()
            if (id != null) openPlaylist(id) else showMessage(failed("Couldn't create the playlist", result.exceptionOrNull()))
        }
    }

    /** Songs waiting for the "Add to playlist" picker, or null when it's closed. */
    val playlistPicker = mutableStateOf<List<String>?>(null)

    override fun addToPlaylist(songIds: List<String>) {
        c.usage.track(Usage.PLAYLIST_ADD)
        playlistPicker.value = songIds
    }

    override fun startRadio(song: SongEntity) = radio("Radio · ${song.title}") { c.library.radio(song, null, it) }

    override fun startArtistRadio(artistId: String, name: String) = radio("Radio · $name") { c.library.radio(null, artistId, it) }

    private fun radio(label: String, ids: suspend (online: Boolean) -> List<String>) {
        c.usage.track(Usage.RADIO)
        scope.launch {
            val list = ids(c.network.isOnline.value)
            if (list.isEmpty()) {
                snackbar.showSnackbar("Couldn't find anything for this radio")
            } else {
                c.player.play(PlaySource(PlaySource.Kind.SONGS, label = label, songIds = list))
            }
        }
    }

    fun confirmAddToPlaylist(playlistId: String?, name: String, songIds: List<String>) {
        playlistPicker.value = null
        c.appScope.launch {
            val result = runCatching {
                if (playlistId == null) c.library.createPlaylist(name, songIds) else c.library.addToPlaylist(playlistId, songIds)
            }
            val error = result.exceptionOrNull()
            snackbar.showSnackbar(
                when {
                    error == null -> if (playlistId == null) "Created \"$name\"" else "Added to \"$name\""
                    else -> {
                        val owner = playlistId?.let { c.library.dao.playlistOnce(it) }
                            ?.let { PlaylistRules.access(it, c.session.credentials.value?.username).owner }
                        c.library.editError(error, owner, failed("Couldn't update the playlist", error))
                    }
                }
            )
        }
    }
}

/** Songs queued while a podcast or audiobook plays wait in the music queue it set aside. */
private const val SPOKEN_QUEUE_MESSAGE = "Added to your music queue. It'll play when you go back to music"

/** The "New playlist" name dialog and the "Add to playlist" picker, while [actions] has one open. */
@Composable
internal fun PlaylistDialogs(actions: AppActions) {
    if (actions.creatingPlaylist.value) {
        NewPlaylistDialog(onDismiss = { actions.creatingPlaylist.value = false }, onCreate = actions::createEmptyPlaylist)
    }

    actions.playlistPicker.value?.let { ids ->
        PlaylistPickerDialog(
            songIds = ids,
            onDismiss = { actions.playlistPicker.value = null },
            onAdd = { id, name -> actions.confirmAddToPlaylist(id, name, ids) },
            onCreate = { name -> actions.confirmAddToPlaylist(null, name, ids) },
        )
    }
}
