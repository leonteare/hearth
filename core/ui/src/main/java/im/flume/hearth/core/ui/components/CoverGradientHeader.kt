package im.flume.hearth.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * The top of a page tinted by its cover: [color] fading to transparent down the header. Used by album,
 * playlist, artist, show, book and stats pages (pass rememberCoverColor(...) or the accent tint).
 */
@Composable
fun CoverGradientHeader(color: Color, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(color, Color.Transparent))), content = content)
}
