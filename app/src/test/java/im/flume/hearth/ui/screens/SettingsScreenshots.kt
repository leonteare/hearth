package im.flume.hearth.ui.screens

import im.flume.hearth.data.DownloadEntity
import im.flume.hearth.data.DownloadState
import im.flume.hearth.testing.Samples
import im.flume.hearth.testing.ScreenshotTest
import im.flume.hearth.testing.liveRows
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** Settings rows and the Downloads page. Moves to :feature:settings with those screens. */
class SettingsScreenshots : ScreenshotTest() {
    @Test
    fun `settings rows, a section of each kind`() = snapScreen("settings_rows") {
        SettingsPage("Settings") {
            Section("Playback")
            Toggle("Gapless playback", true, subtitle = "No silence between songs on an album") {}
            Toggle("Normalise volume", false) {}
            Choice("Streaming quality", 320, BITRATES) {}
            Section("About")
            Info("Version", "2.19.1")
            Clickable("Sign out", "Downloads stay on this phone") {}
        }
    }

    @Test
    fun `the downloads page with nothing downloading`() = snapScreen("downloads_empty") {
        DownloadsScreen()
    }

    @Test
    fun `the downloads page with songs downloading and waiting`() {
        val songs = Samples.songs(4)
        val states = mapOf(songs[0].id to DownloadState.DOWNLOADING, songs[1].id to DownloadState.QUEUED, songs[2].id to DownloadState.QUEUED, songs[3].id to DownloadState.QUEUED)
        runBlocking {
            container.db.library().insertSongs(songs)
            songs.forEachIndexed { i, s -> container.db.downloads().upsert(DownloadEntity(s.id, states.getValue(s.id), null, 0, addedAt = Samples.NOW_MS + i)) }
        }
        snapScreen("downloads_in_progress", live = liveRows(downloads = states)) { DownloadsScreen() }
    }
}
