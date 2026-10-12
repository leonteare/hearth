// A mix's cover: four of its album covers in a square. Home's mix cards and the mix page header both draw it.
package im.flume.hearth.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import im.flume.hearth.data.Mix
import im.flume.hearth.ui.components.CoverArt
import im.flume.hearth.ui.theme.CoverSize
import im.flume.hearth.ui.theme.HearthShapes

@Composable
fun MixCover(mix: Mix, size: Dp) {
    val covers = (mix.covers + List(4) { null }).take(4)
    Column(Modifier.size(size).clip(HearthShapes.Cover)) {
        for (row in 0..1) Row(Modifier.weight(1f)) {
            for (col in 0..1) CoverArt(covers[row * 2 + col], null, Modifier.weight(1f).fillMaxSize(), corner = 0.dp, requestSize = CoverSize.MEDIUM)
        }
    }
}
