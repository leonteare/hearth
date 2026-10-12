// The play and shuffle buttons on album, playlist and artist headers.
package im.flume.hearth.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import im.flume.hearth.ui.theme.Dimens

/** Big round play button + shuffle button used on album / playlist / artist headers. */
@Composable
fun PlayShuffleButtons(
    onPlay: () -> Unit,
    onShuffle: (() -> Unit)?,
    modifier: Modifier = Modifier,
    /** Spoken label of the play button, e.g. "Play preview" for music not on the server. */
    playLabel: String = "Play",
    extra: @Composable () -> Unit = {},
) {
    val accent = MaterialTheme.colorScheme.primary
    Row(modifier.fillMaxWidth().padding(horizontal = Dimens.Gutter), verticalAlignment = Alignment.CenterVertically) {
        extra()
        Spacer(Modifier.weight(1f))
        if (onShuffle != null) {
            IconButton(onClick = onShuffle, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.Shuffle, "Shuffle", tint = accent, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.width(8.dp))
        }
        FilledIconButton(
            onClick = onPlay,
            modifier = Modifier.size(56.dp),
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = accent, contentColor = MaterialTheme.colorScheme.onPrimary),
        ) {
            Icon(Icons.Default.PlayArrow, playLabel, modifier = Modifier.size(32.dp))
        }
    }
}
