// The top of the Search tab: the search box, its hint text, and the All · Artists · … filter chips.
package im.flume.hearth.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import im.flume.hearth.data.SearchFilter
import im.flume.hearth.ui.theme.HearthTheme

/** The rounded search box at the top of Search, with a clear button once something is typed. */
@Composable
internal fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    hint: String,
    focus: FocusRequester,
    onSearch: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = HearthTheme.spacing.gutter)
            .heightIn(min = 48.dp)
            .clip(HearthTheme.shapes.card)
            .background(HearthTheme.colors.searchField)
            .padding(start = HearthTheme.spacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(HearthTheme.icons.search, null, tint = HearthTheme.colors.onSearchField, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = HearthTheme.colors.onSearchField),
            cursorBrush = SolidColor(HearthTheme.colors.onSearchField),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            modifier = Modifier.weight(1f).focusRequester(focus),
            decorationBox = { inner ->
                if (query.isEmpty()) {
                    Text(
                        hint,
                        color = HearthTheme.colors.searchFieldHint, style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                inner()
            },
        )
        if (query.isNotEmpty()) {
            IconButton(onClick = { onQueryChange("") }) { Icon(HearthTheme.icons.close, "Clear", tint = HearthTheme.colors.onSearchField) }
        } else {
            Spacer(Modifier.width(HearthTheme.spacing.m))
        }
    }
}

internal fun searchHint(filter: SearchFilter, spoken: Boolean): String = when (filter) {
    SearchFilter.ARTISTS -> "Artists"
    SearchFilter.ALBUMS -> "Albums"
    SearchFilter.SONGS -> "Songs"
    SearchFilter.PODCASTS -> "Podcasts and episodes"
    SearchFilter.BOOKS -> "Audiobooks"
    SearchFilter.ALL -> if (spoken) "Songs, artists, albums or podcasts" else "Songs, artists or albums"
}

/**
 * All · Artists · Albums · Songs · Podcasts · Books, all six side by side at equal width. Small
 * enough to fit a 360dp phone without scrolling, so every filter is always one tap away.
 */
@Composable
internal fun SearchFilterChips(selected: SearchFilter, onSelect: (SearchFilter) -> Unit) {
    EqualWidthChips(SearchFilter.entries, selected, { it.label }, MaterialTheme.typography.labelSmall, minHeight = 32.dp, onSelect)
}
