package im.flume.hearth.ui

// The three tabs (Home, Search, Your Library): the bottom bar under the mini player in portrait, the side rail in landscape.

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import im.flume.hearth.core.playback.PlayerUiState
import im.flume.hearth.core.ui.components.PreviewBar
import im.flume.hearth.feature.player.MiniPlayer
import im.flume.hearth.core.ui.theme.HearthIcons
import im.flume.hearth.core.ui.theme.HearthTheme

/** Route, label and the tab's icon slot (read from the theme when drawn, so a skin can swap it). */
internal typealias Tab = Triple<String, String, (HearthIcons) -> ImageVector>

internal val TABS: List<Tab> = listOf(
    Triple("home", "Home", HearthIcons::home),
    Triple("search", "Search", HearthIcons::search),
    Triple("library", "Your Library", HearthIcons::library),
)

/** The fourth tab on a kiosk phone: the profile picker (ProfilesScreen). */
private val PROFILES_TAB: Tab = Triple("profiles", "Who's listening", HearthIcons::profiles)

/** The tabs this phone shows: the usual three, plus profiles in kids mode. */
internal fun tabs(kids: Boolean): List<Tab> = if (kids) TABS + PROFILES_TAB else TABS

internal val TAB_ROUTES = (TABS + PROFILES_TAB).map { it.first }

/**
 * Everything pinned to the bottom of the page: the preview bar, the mini player and (in portrait) the tabs.
 * Reports its height through [onHeight] so messages can sit just above it.
 */
@Composable
internal fun AppBottomBar(
    playerState: PlayerUiState,
    landscape: Boolean,
    tabs: List<Tab>,
    currentTab: String,
    badged: String?,
    onTabClick: (String) -> Unit,
    onOpenPlayer: () -> Unit,
    onHeight: (Int) -> Unit,
) {
    Column(
        (if (landscape) Modifier.navigationBarsPadding().padding(bottom = HearthTheme.spacing.s) else Modifier)
            .onSizeChanged { onHeight(it.height) }
    ) {
        PreviewBar()
        if (playerState.current != null) MiniPlayer(playerState, onOpen = onOpenPlayer)
        if (!landscape) BottomBar(tabs, currentTab, badged, onTabClick)
    }
}

@Composable
private fun TabIcon(icon: ImageVector, dot: Boolean) {
    if (!dot) return Icon(icon, contentDescription = null)
    BadgedBox(badge = { Badge(containerColor = HearthTheme.colors.noticeDot, modifier = Modifier.semantics { contentDescription = "New playlist invite" }) }) {
        Icon(icon, contentDescription = null)
    }
}

@Composable
private fun BottomBar(tabs: List<Tab>, currentTab: String, badged: String?, onTabClick: (String) -> Unit) {
    NavigationBar(containerColor = HearthTheme.colors.navBar) {
        tabs.forEach { (dest, label, icon) ->
            NavigationBarItem(
                selected = currentTab == dest,
                onClick = { onTabClick(dest) },
                icon = { TabIcon(icon(HearthTheme.icons), dest == badged) },
                label = { Text(label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = HearthTheme.colors.text,
                    selectedTextColor = HearthTheme.colors.text,
                    unselectedIconColor = HearthTheme.colors.textSecondary,
                    unselectedTextColor = HearthTheme.colors.textSecondary,
                    indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                ),
            )
        }
    }
}

@Composable
internal fun SideRail(tabs: List<Tab>, currentTab: String, badged: String?, onTabClick: (String) -> Unit) {
    NavigationRail(containerColor = HearthTheme.colors.navBar) {
        Spacer(Modifier.weight(1f))
        tabs.forEach { (dest, label, icon) ->
            NavigationRailItem(
                selected = currentTab == dest,
                onClick = { onTabClick(dest) },
                icon = { TabIcon(icon(HearthTheme.icons), dest == badged) },
                label = { Text(label) },
                colors = NavigationRailItemDefaults.colors(
                    selectedIconColor = HearthTheme.colors.text,
                    selectedTextColor = HearthTheme.colors.text,
                    unselectedIconColor = HearthTheme.colors.textSecondary,
                    unselectedTextColor = HearthTheme.colors.textSecondary,
                    indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                ),
                modifier = Modifier.padding(vertical = HearthTheme.spacing.xs),
            )
        }
        Spacer(Modifier.weight(1f))
    }
}
