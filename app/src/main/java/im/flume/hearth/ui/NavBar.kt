package im.flume.hearth.ui

// The three tabs (Home, Search, Your Library): the bottom bar under the mini player in portrait, the side rail in landscape.

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import im.flume.hearth.playback.PlayerUiState
import im.flume.hearth.ui.components.PreviewBar
import im.flume.hearth.ui.screens.MiniPlayer
import im.flume.hearth.ui.theme.NavBarColor
import im.flume.hearth.ui.theme.NoticeDot
import im.flume.hearth.ui.theme.TextSecondary

internal val TABS = listOf(
    Triple("home", "Home", Icons.Default.Home),
    Triple("search", "Search", Icons.Default.Search),
    Triple("library", "Your Library", Icons.Default.LibraryMusic),
)
internal val TAB_ROUTES = TABS.map { it.first }

/**
 * Everything pinned to the bottom of the page: the preview bar, the mini player and (in portrait) the tabs.
 * Reports its height through [onHeight] so messages can sit just above it.
 */
@Composable
internal fun AppBottomBar(
    playerState: PlayerUiState,
    landscape: Boolean,
    currentTab: String,
    badged: String?,
    onTabClick: (String) -> Unit,
    onOpenPlayer: () -> Unit,
    onHeight: (Int) -> Unit,
) {
    Column(
        (if (landscape) Modifier.navigationBarsPadding().padding(bottom = 8.dp) else Modifier)
            .onSizeChanged { onHeight(it.height) }
    ) {
        PreviewBar()
        if (playerState.current != null) MiniPlayer(playerState, onOpen = onOpenPlayer)
        if (!landscape) BottomBar(currentTab, badged, onTabClick)
    }
}

@Composable
private fun TabIcon(icon: ImageVector, dot: Boolean) {
    if (!dot) return Icon(icon, contentDescription = null)
    BadgedBox(badge = { Badge(containerColor = NoticeDot, modifier = Modifier.semantics { contentDescription = "New playlist invite" }) }) {
        Icon(icon, contentDescription = null)
    }
}

@Composable
private fun BottomBar(currentTab: String, badged: String?, onTabClick: (String) -> Unit) {
    NavigationBar(containerColor = NavBarColor) {
        TABS.forEach { (dest, label, icon) ->
            NavigationBarItem(
                selected = currentTab == dest,
                onClick = { onTabClick(dest) },
                icon = { TabIcon(icon, dest == badged) },
                label = { Text(label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Color.White,
                    selectedTextColor = Color.White,
                    unselectedIconColor = TextSecondary,
                    unselectedTextColor = TextSecondary,
                    indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                ),
            )
        }
    }
}

@Composable
internal fun SideRail(currentTab: String, badged: String?, onTabClick: (String) -> Unit) {
    NavigationRail(containerColor = NavBarColor) {
        Spacer(Modifier.weight(1f))
        TABS.forEach { (dest, label, icon) ->
            NavigationRailItem(
                selected = currentTab == dest,
                onClick = { onTabClick(dest) },
                icon = { TabIcon(icon, dest == badged) },
                label = { Text(label) },
                colors = NavigationRailItemDefaults.colors(
                    selectedIconColor = Color.White,
                    selectedTextColor = Color.White,
                    unselectedIconColor = TextSecondary,
                    unselectedTextColor = TextSecondary,
                    indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                ),
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
        Spacer(Modifier.weight(1f))
    }
}
