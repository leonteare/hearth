package im.flume.hearth.core.ui.components

import im.flume.hearth.core.testing.ScreenshotTest
import org.junit.Test

/** The speaker picker: on the phone, on a speaker (with its volume), still looking, and nothing found. */
class SpeakerPickerScreenshots : ScreenshotTest() {
    private val phone = PlaybackOutputUi("phone", "This phone", isPhone = true)
    private val living = PlaybackOutputUi("rincon:1", "Living Room")
    private val kitchen = PlaybackOutputUi("rincon:2", "Kitchen")

    @Test
    fun `two speakers found, playing on the phone`() = snapScreen("on_the_phone") {
        SpeakerSheet(
            open = true,
            outputs = listOf(phone.copy(isActive = true), living, kitchen),
            searching = false,
            volume = null,
            onVolume = {}, onChoose = {}, onRefresh = {}, onDismiss = {},
        )
    }

    @Test
    fun `playing on a speaker, with its volume`() = snapScreen("on_a_speaker") {
        SpeakerSheet(
            open = true,
            outputs = listOf(phone, living.copy(isActive = true), kitchen),
            searching = false,
            volume = 35,
            onVolume = {}, onChoose = {}, onRefresh = {}, onDismiss = {},
        )
    }

    @Test
    fun `still looking, nothing found yet`() = snapScreen("searching") {
        SpeakerSheet(
            open = true,
            outputs = listOf(phone.copy(isActive = true)),
            searching = true,
            volume = null,
            onVolume = {}, onChoose = {}, onRefresh = {}, onDismiss = {},
        )
    }

    @Test
    fun `no speakers on this network`() = snapScreen("none_found") {
        SpeakerSheet(
            open = true,
            outputs = listOf(phone.copy(isActive = true)),
            searching = false,
            volume = null,
            onVolume = {}, onChoose = {}, onRefresh = {}, onDismiss = {},
        )
    }
}
