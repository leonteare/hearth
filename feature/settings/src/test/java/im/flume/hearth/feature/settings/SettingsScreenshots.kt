package im.flume.hearth.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import im.flume.hearth.core.data.DownloadEntity
import im.flume.hearth.core.data.DownloadState
import im.flume.hearth.core.testing.Samples
import im.flume.hearth.core.testing.ScreenshotTest
import im.flume.hearth.core.testing.liveRows
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** Settings rows and the Downloads page. */
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

    @Test
    fun `the downloads page with songs that couldn't download`() {
        val songs = Samples.songs(2)
        val states = songs.associate { it.id to DownloadState.FAILED }
        runBlocking {
            container.db.library().insertSongs(songs)
            songs.forEachIndexed { i, s -> container.db.downloads().upsert(DownloadEntity(s.id, DownloadState.FAILED, null, 0, addedAt = Samples.NOW_MS + i)) }
        }
        snapScreen("downloads_failed", live = liveRows(downloads = states)) { DownloadsScreen() }
    }

    @Test
    fun `the settings home page`() = snapScreen("settings_home") { SettingsScreen() }

    @Test
    fun `the appearance page with the accent picker`() = snapScreen("appearance") { AppearanceSettings() }

    // Not the whole General page: it shows the app's version, which changes with every release.
    @Test
    fun `the podcasts and audiobooks sign-in form`() = snap("spoken_sign_in") { SpokenSignInForm {} }

    @Test
    fun `the sign-in screen`() = snapScreen("login") { LoginScreen() }

    // The sections below sit inside Storage and General; each is shot on its own, since those pages
    // also show the app's version and whatever is signed in.
    @Test
    fun `getting music you don't have`() = snap("get_music") {
        Column(Modifier.fillMaxWidth()) { GetMusicSettings() }
    }

    @Test
    fun `the podcasts and audiobooks account section, signed out`() = snap("spoken_account") {
        Column(Modifier.fillMaxWidth()) { PodcastsAndAudiobooksAccount() }
    }

    @Test
    fun `audiobook folders on this phone, with none picked`() = snap("phone_audiobooks") {
        Column(Modifier.fillMaxWidth()) { PhoneAudiobookFolders() }
    }
}
