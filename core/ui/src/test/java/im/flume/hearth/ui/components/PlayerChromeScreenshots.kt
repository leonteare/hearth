package im.flume.hearth.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import im.flume.hearth.testing.ScreenshotTest
import im.flume.hearth.ui.theme.Dimens
import org.junit.Test

class PlayerChromeScreenshots : ScreenshotTest() {
    private val coverColor = Color(0xFF2F5D62)

    @Test
    fun `the big play button, playing and paused`() = snap("big_play_button") {
        Row(Modifier.padding(Dimens.Gutter), horizontalArrangement = Arrangement.spacedBy(Dimens.Gutter)) {
            BigPlayButton(isPlaying = true, onClick = {})
            BigPlayButton(isPlaying = false, onClick = {})
        }
    }

    @Test
    fun `the seek bar, plain and with chapter gaps`() = snap("player_slider") {
        Column(Modifier.padding(Dimens.Gutter)) {
            PlayerSlider(0.35f, rememberSeekState(), onSeek = {}, modifier = Modifier.fillMaxWidth())
            PlayerSlider(0.6f, rememberSeekState(), onSeek = {}, modifier = Modifier.fillMaxWidth(), ticks = listOf(0.2f, 0.45f, 0.8f))
        }
    }

    @Test
    fun `a cover gradient header fades from the cover colour`() = snap("cover_gradient_header") {
        CoverGradientHeader(coverColor, Modifier.height(240.dp)) {
            Text("Long Winter", Modifier.align(Alignment.BottomStart).padding(Dimens.Gutter), style = MaterialTheme.typography.headlineMedium)
        }
    }

    @Test
    fun `the player backdrop fades from the cover colour into the page`() = snapScreen("player_backdrop") {
        Box(Modifier.fillMaxWidth().height(600.dp).playerBackdrop(coverColor))
    }
}
