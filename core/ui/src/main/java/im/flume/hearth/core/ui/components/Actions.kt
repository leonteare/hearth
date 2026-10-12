// The Actions every row and card can trigger, and the tab requests, both provided once at the root.
package im.flume.hearth.core.ui.components

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import im.flume.hearth.domain.music.PlaySource
import im.flume.hearth.core.domain.SearchFilter
import im.flume.hearth.core.data.SongEntity

/** Playing, queueing, liking and downloading songs, from any row, card or menu. */
interface SongActions {
    fun play(source: PlaySource, startSongId: String? = null, shuffle: Boolean = false)
    fun playNext(songIds: List<String>)
    fun addToQueue(songIds: List<String>)
    fun setStarred(songId: String, starred: Boolean)
    fun download(songs: List<SongEntity>)
    fun removeDownload(songIds: List<String>)
    fun startRadio(song: SongEntity)
    fun startArtistRadio(artistId: String, name: String)
}

/** Adding songs to playlists and making new ones. */
interface PlaylistActions {
    /** Opens the "Add to playlist" picker for these songs. */
    fun addToPlaylist(songIds: List<String>)
    /** Opens the "New playlist" dialog. */
    fun newPlaylist() {}
}

/** Saving albums and following artists, which puts them in Your Library. */
interface LibraryActions {
    /** Saves an album to Your Library or removes it. */
    fun setAlbumSaved(albumId: String, saved: Boolean) {}
    /** Follows or unfollows an artist, which puts them in Your Library. */
    fun setArtistFollowed(artistId: String, followed: Boolean) {}
}

/** Going to another page or tab. Pages in other features are reached by route, never called directly. */
interface NavActions {
    fun openAlbum(id: String)
    fun openArtist(id: String)
    fun openPlaylist(id: String)
    fun openGenre(name: String)
    fun open(route: String)
    /**
     * Switches to the Your Library tab showing [tab] (a library tab name, e.g. "PODCASTS", or a Music
     * list such as "ARTISTS", which opens Music on that list), like tapping the tab itself, so
     * tapping Your Library again doesn't land somewhere unexpected.
     */
    fun openLibraryTab(tab: String) {}
    /** Switches to the Search tab with [filter] chosen and the keyboard up, ready to type. */
    fun openSearchTab(filter: SearchFilter) {}
}

/** Short messages at the bottom of the screen. */
interface MessageActions {
    /**
     * Shows [message] with an Undo button that runs [undo]. Undo stays up for longer by default ([long]),
     * because it usually follows something destructive and people need time to notice and reach it;
     * pass false for harmless things like "Playing next".
     */
    fun showUndo(message: String, long: Boolean = true, undo: suspend () -> Unit)
    /** Shows a short [message], e.g. when something couldn't be saved. */
    fun showMessage(message: String) {}
}

/** Cover images by server id. */
interface CoverActions {
    fun coverUrl(coverArt: String?, size: Int = 300): String?
}

/**
 * Everything a song row or card can trigger. Provided once at the root. Code that needs only one
 * area can take the narrower interface (e.g. [NavActions]).
 */
interface Actions : SongActions, PlaylistActions, LibraryActions, NavActions, MessageActions, CoverActions

val LocalActions = compositionLocalOf<Actions> { error("Actions not provided") }

/** Shown when someone taps a song that can't play because it isn't on the phone and there's no connection. */
const val NOT_DOWNLOADED_OFFLINE = "This song isn't downloaded, and you're offline"

/**
 * What a tab page should show when it was opened by [Actions.openLibraryTab] or
 * [Actions.openSearchTab]. The page takes the request once and clears it, so its own choices
 * stick afterwards.
 */
class TabRequests {
    var libraryTab by mutableStateOf<String?>(null)
    var searchFilter by mutableStateOf<SearchFilter?>(null)
}

val LocalTabRequests = compositionLocalOf { TabRequests() }
