package im.flume.hearth.core.ui.components

import androidx.compose.foundation.layout.Column
import im.flume.hearth.core.data.DownloadState
import im.flume.hearth.core.testing.Samples
import im.flume.hearth.core.testing.ScreenshotTest
import im.flume.hearth.core.testing.liveRows
import org.junit.Test

class SongRowScreenshots : ScreenshotTest() {
    private val song = Samples.song()

    @Test
    fun `a song row with its cover, title and artist`() = snap("normal") {
        SongRow(song, onClick = {})
    }

    @Test
    fun `the playing song is drawn in the accent colour`() = snap("playing", live = liveRows(playingId = song.id)) {
        SongRow(song, onClick = {})
    }

    @Test
    fun `liked and downloaded songs show a heart and a tick`() =
        snap("liked_downloaded", live = liveRows(downloads = mapOf(song.id to DownloadState.DONE))) {
            SongRow(song.copy(starred = true), onClick = {})
        }

    @Test
    fun `offline, a song that isn't downloaded is dimmed with a cloud`() = snap("unplayable_offline", live = liveRows(online = false)) {
        SongRow(song, onClick = {})
    }

    @Test
    fun `a downloading song shows the downloading icon`() =
        snap("downloading", live = liveRows(downloads = mapOf(song.id to DownloadState.DOWNLOADING))) {
            SongRow(song, onClick = {})
        }

    @Test
    fun `while selecting, rows show a selected and an unselected circle`() = snap("selecting") {
        Column {
            SongRow(song, onClick = {}, selected = true)
            SongRow(Samples.song(id = "s2", title = "Paper Boats"), onClick = {}, selected = false)
        }
    }

    @Test
    fun `album track lists show the track number instead of a cover`() = snap("track_numbers") {
        Column {
            Samples.songs(3).forEach { SongRow(it, onClick = {}, showCover = false, leading = it.track.toString()) }
        }
    }
}
