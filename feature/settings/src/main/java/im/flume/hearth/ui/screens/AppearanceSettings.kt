// Settings > Appearance: accent colour and lyric style, with a live preview.
package im.flume.hearth.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import im.flume.hearth.container
import im.flume.hearth.ui.theme.AccentChoices
import im.flume.hearth.ui.theme.AccentDeep
import im.flume.hearth.ui.theme.HearthTheme

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppearanceSettings() {
    val c = LocalContext.current.container
    val settings by c.session.settings.collectAsStateWithLifecycle()

    SettingsPage("Appearance") {
        Section("Accent colour")
        FlowRow(
            Modifier.padding(horizontal = HearthTheme.spacing.gutter, vertical = HearthTheme.spacing.s),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            AccentChoices.forEach { (name, argb) ->
                val selected = settings.accent == argb.toInt()
                // The whole swatch + label is one 48dp+ radio target; TalkBack reads the colour name and whether it is selected.
                Column(
                    Modifier
                        .clip(HearthTheme.shapes.card)
                        .selectable(selected = selected, role = Role.RadioButton) {
                            c.session.updateSettings { it.copy(accent = argb.toInt()) }
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(Color(argb))
                            .border(if (selected) 3.dp else 0.dp, HearthTheme.colors.text, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (selected) Icon(HearthTheme.icons.check, null, tint = HearthTheme.colors.onAccent)
                    }
                    Text(name, style = MaterialTheme.typography.labelMedium, color = if (selected) HearthTheme.colors.text else HearthTheme.colors.textSecondary)
                }
            }
        }

        Section("Lyrics")
        Choice("Text size", settings.lyricsSize, listOf(0 to "Small", 1 to "Medium", 2 to "Large", 3 to "Extra large")) { v ->
            c.session.updateSettings { it.copy(lyricsSize = v) }
        }
        Choice("Line spacing", settings.lyricsSpacing, listOf(0 to "Compact", 1 to "Normal", 2 to "Relaxed")) { v ->
            c.session.updateSettings { it.copy(lyricsSpacing = v) }
        }
        // Live preview of the lyric style.
        val size = LYRIC_SIZES.getOrElse(settings.lyricsSize) { 22 }.sp
        val gap = LYRIC_GAPS.getOrElse(settings.lyricsSpacing) { 8 }.dp
        Column(
            Modifier.fillMaxWidth().padding(HearthTheme.spacing.l).clip(HearthTheme.shapes.card).background(AccentDeep).padding(horizontal = HearthTheme.spacing.gutter, vertical = HearthTheme.spacing.s)
        ) {
            listOf("Here's how your lyrics" to 0.45f, "will look while a song" to 1f, "is playing" to 0.35f).forEach { (line, alpha) ->
                Text(line, fontSize = size, lineHeight = size * 1.25f, fontWeight = FontWeight.Bold, color = HearthTheme.colors.onCover.copy(alpha = alpha), modifier = Modifier.padding(vertical = gap))
            }
        }
    }
}
