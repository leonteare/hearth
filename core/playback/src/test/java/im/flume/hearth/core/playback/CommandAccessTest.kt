package im.flume.hearth.core.playback

import im.flume.hearth.core.playback.PlaybackService.Companion.CMD_LIKE
import im.flume.hearth.core.playback.PlaybackService.Companion.CMD_PLAY_SOURCE
import im.flume.hearth.core.playback.PlaybackService.Companion.CMD_REMOVE
import im.flume.hearth.core.playback.PlaybackService.Companion.CMD_SKIP_BACK
import im.flume.hearth.core.playback.PlaybackService.Companion.CMD_SKIP_FORWARD
import im.flume.hearth.core.playback.PlaybackService.Companion.CMD_SLEEP
import im.flume.hearth.core.playback.PlaybackService.Companion.CMD_TOGGLE_SHUFFLE
import im.flume.hearth.core.playback.PlaybackService.Companion.CUSTOM_COMMANDS
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandAccessTest {
    @Test
    fun `the app itself may send every command`() {
        CUSTOM_COMMANDS.forEach { assertTrue(it, CommandAccess.allowed(fromThisApp = true, it)) }
    }

    @Test
    fun `the lock screen and other apps may press the extra buttons`() {
        listOf(CMD_LIKE, CMD_TOGGLE_SHUFFLE, CMD_SKIP_BACK, CMD_SKIP_FORWARD).forEach {
            assertTrue(it, CommandAccess.allowed(fromThisApp = false, it))
        }
    }

    @Test
    fun `other apps can't change the queue or set the sleep timer`() {
        listOf(CMD_PLAY_SOURCE, CMD_REMOVE, CMD_SLEEP, "hearth.SOMETHING_NEW").forEach {
            assertFalse(it, CommandAccess.allowed(fromThisApp = false, it))
        }
    }

    @Test
    fun `every button other apps may press is one of the app's commands`() {
        assertTrue(CUSTOM_COMMANDS.containsAll(PlaybackService.BUTTON_COMMANDS))
    }
}
