// Where the sound comes out: the speaker picker sheet (phone, speakers on the network, volume), the
// player's button that opens it, and the quiet "Playing on …" line. Plain values in, callbacks out.
package im.flume.hearth.core.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import im.flume.hearth.core.ui.theme.HearthTheme
import kotlin.math.roundToInt

/**
 * Somewhere the music can play: this phone, or a speaker found on the network. [id] is whatever the player
 * calls it, handed straight back to [SpeakerSheet]'s `onChoose`; the UI never looks inside it.
 */
data class PlaybackOutputUi(
    val id: String,
    val name: String,
    val isPhone: Boolean = false,
    val isActive: Boolean = false,
)

/**
 * The speaker picker: this phone first, then the speakers found on the network, with a tick on the one
 * playing. [searching] keeps a quiet "Looking for speakers" line in the same place at the bottom, so the
 * list doesn't jump about while more arrive. [volume] (0..100) shows a volume slider, but only while a
 * speaker is playing: on the phone that is the hardware keys' business, so pass null for it.
 */
@Composable
fun SpeakerSheet(
    open: Boolean,
    outputs: List<PlaybackOutputUi>,
    searching: Boolean,
    volume: Int?,
    onVolume: (Int) -> Unit,
    onChoose: (String) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = HearthTheme.colors
    val icons = HearthTheme.icons
    val accent = MaterialTheme.colorScheme.primary
    // The phone is always the first row, however the list arrives.
    val rows = outputs.sortedByDescending { it.isPhone }
    val speakers = rows.filterNot { it.isPhone }
    val playingOnSpeaker = speakers.any { it.isActive }

    ActionSheet(open, onDismiss, title = "Play on", showCover = false) {
        rows.forEach { out ->
            MenuItem(
                out.name,
                if (out.isPhone) icons.phone else icons.speaker,
                tint = if (out.isActive) accent else colors.text,
                checked = out.isActive,
            ) { onChoose(out.id); onDismiss() }
        }
        if (playingOnSpeaker && volume != null) {
            HorizontalDivider(Modifier.padding(vertical = HearthTheme.spacing.xs), color = colors.divider)
            VolumeRow(volume, onVolume)
        }
        when {
            searching -> SearchingRow()
            speakers.isEmpty() -> NoSpeakersFound(onRefresh)
            else -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                TextButton(onClick = onRefresh) { Text("Look again") }
            }
        }
    }
}

/** The speaker's own volume, on the same thin bar as the player's seek line. Told once the finger lifts. */
@Composable
private fun VolumeRow(volume: Int, onVolume: (Int) -> Unit) {
    val spacing = HearthTheme.spacing
    val seek = rememberSeekState()
    val shown = if (seek.dragging) (seek.value * VOLUME_MAX).roundToInt() else volume.coerceIn(0, VOLUME_MAX)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = spacing.xl),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(HearthTheme.icons.speaker, "Speaker volume", tint = HearthTheme.colors.textSecondary)
        Spacer(Modifier.width(spacing.l))
        PlayerSlider(
            fraction = volume.coerceIn(0, VOLUME_MAX) / VOLUME_MAX.toFloat(),
            seek = seek,
            onSeek = { onVolume((it * VOLUME_MAX).roundToInt()) },
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(spacing.l))
        Text(
            "$shown",
            style = MaterialTheme.typography.bodyMedium,
            color = HearthTheme.colors.textSecondary,
            textAlign = TextAlign.End,
            // Room for three digits, so the bar doesn't shuffle as the number grows.
            modifier = Modifier.widthIn(min = spacing.xl),
        )
    }
}

/** Still looking. Sits where the "Look again" button will be, so nothing shifts when the search ends. */
@Composable
private fun SearchingRow() {
    val spacing = HearthTheme.spacing
    Row(
        Modifier.fillMaxWidth().padding(spacing.m),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.size(spacing.l), strokeWidth = spacing.xxs, color = HearthTheme.colors.textSecondary)
        Spacer(Modifier.width(spacing.m))
        Text("Looking for speakers…", color = HearthTheme.colors.textSecondary, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Nothing on the network. Nine times in ten the phone is on a different Wi-Fi, so say that first. */
@Composable
private fun NoSpeakersFound(onRefresh: () -> Unit) {
    EmptyState(
        HearthTheme.icons.speaker,
        "No speakers found on this network. Your phone and the speaker have to be on the same Wi-Fi.",
        action = "Look again",
        onAction = onRefresh,
    )
}

/**
 * The player's output button. Plain while the phone is playing; while [activeName] is playing it takes the
 * accent and says which speaker, so you can see where the sound is going without opening anything. The icon
 * and the name share one tap target rather than taking one each.
 */
@Composable
fun SpeakerButton(activeName: String?, onClick: () -> Unit) {
    val spacing = HearthTheme.spacing
    val icons = HearthTheme.icons
    if (activeName == null) {
        IconButton(onClick = onClick) { Icon(icons.speaker, "Play on a speaker", tint = HearthTheme.colors.text) }
        return
    }
    val accent = MaterialTheme.colorScheme.primary
    Row(
        Modifier
            .heightIn(min = spacing.touchTarget)
            .clip(HearthTheme.shapes.chip)
            .clickable(role = Role.Button, onClickLabel = "Change speaker", onClick = onClick)
            .padding(horizontal = spacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icons.speaker, "Playing on $activeName", tint = accent)
        Spacer(Modifier.width(spacing.s))
        Text(
            activeName,
            color = accent,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // A long name is cut short rather than crowding the buttons beside it.
            modifier = Modifier.widthIn(max = spacing.cardWidth),
        )
    }
}

/** The quiet line in the full player saying the sound is leaving the phone. Nothing at all on the phone. */
@Composable
fun PlayingOnLine(activeName: String?, modifier: Modifier = Modifier) {
    if (activeName == null) return
    Text(
        "Playing on $activeName",
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** Volume is a percentage in the UI; the player turns it into whatever the speaker wants. */
private const val VOLUME_MAX = 100
