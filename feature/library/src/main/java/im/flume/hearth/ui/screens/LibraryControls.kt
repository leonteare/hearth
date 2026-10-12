// Your Library's second row (Music's list chips, Shuffle, "+" and Sort) and the "Add podcasts" sheet.
package im.flume.hearth.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import im.flume.hearth.data.PlaySource
import im.flume.hearth.data.SearchFilter
import im.flume.hearth.ui.components.ActionSheet
import im.flume.hearth.ui.components.LocalActions
import im.flume.hearth.ui.components.MenuItem
import im.flume.hearth.ui.theme.HearthTheme

/**
 * Second row: Music's lists on the left, then the sort (and Podcasts' "+") on the right.
 * The chips are measured first and the sort shortens itself, so nothing ever covers a chip.
 * Same height on every section, so the list below doesn't jump when you switch.
 */
@Composable
internal fun LibraryControls(
    place: LibraryPlace,
    showAddPodcasts: Boolean,
    onGoTo: (LibraryPlace) -> Unit,
    onAddPodcasts: () -> Unit,
    sortOptions: List<String>,
    sort: Int,
    onSort: (Int) -> Unit,
) {
    val actions = LocalActions.current
    val tab = place.tab
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = HearthTheme.spacing.gutter, end = HearthTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (place.section == LibrarySection.MUSIC) {
            Row(horizontalArrangement = Arrangement.spacedBy(HearthTheme.spacing.s)) {
                LibraryTabs.musicTabs.forEach { t ->
                    FilterChip(
                        selected = tab == t,
                        onClick = { onGoTo(LibraryTabs.goTo(place, t)) },
                        label = { Text(t.label) },
                        shape = HearthTheme.shapes.chip,
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    )
                }
            }
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            // Shuffle sits with Music only: it plays your music (liked songs and saved albums), never shows or books.
            if (place.section == LibrarySection.MUSIC) {
                IconButton(onClick = { actions.play(PlaySource.MyLibrary, shuffle = true) }) {
                    Icon(HearthTheme.icons.shuffle, "Shuffle your music", tint = MaterialTheme.colorScheme.primary)
                }
            }
            if (showAddPodcasts) {
                IconButton(onClick = onAddPodcasts) {
                    Icon(HearthTheme.icons.add, "Add podcasts", tint = MaterialTheme.colorScheme.primary)
                }
            }
            SortMenu(sortOptions, sort, onSort = onSort)
        }
    }
}

/**
 * Finding and importing shows live here rather than at the top of the list, so the shows
 * you follow come first. With nothing followed yet, the list offers both inline instead.
 */
@Composable
internal fun AddPodcastsSheet(open: Boolean, onDismiss: () -> Unit, onImport: () -> Unit) {
    val actions = LocalActions.current
    ActionSheet(open, onDismiss = onDismiss, title = "Add podcasts", showCover = false) {
        // The Search tab itself (not a page on top of this one), so Your Library still comes back here.
        MenuItem("Find a podcast", HearthTheme.icons.search) {
            onDismiss()
            actions.openSearchTab(SearchFilter.PODCASTS)
        }
        MenuItem("Import podcasts (OPML)", HearthTheme.icons.importFile) {
            onDismiss()
            onImport()
        }
    }
}
