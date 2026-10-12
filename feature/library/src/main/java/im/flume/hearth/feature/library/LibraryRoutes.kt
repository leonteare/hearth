package im.flume.hearth.feature.library

// The library feature's pages. Lives in the feature's module; :app's Routes.kt calls libraryRoutes().

import androidx.navigation.NavGraphBuilder
import im.flume.hearth.core.ui.components.arg
import im.flume.hearth.core.ui.components.screen

fun NavGraphBuilder.libraryRoutes() {
    screen("library") { LibraryScreen() }
    screen("browse/artists") { BrowseArtistsScreen() }
    screen("browse/albums") { BrowseAlbumsScreen() }
    screen("browse/songs") { BrowseSongsScreen() }
    screen("liked") { LikedScreen() }
    screen("album/{id}") { AlbumScreen(it.arg("id")) }
    screen("artist/{id}") { ArtistScreen(it.arg("id")) }
    screen("playlist/{id}") { PlaylistScreen(it.arg("id")) }
    screen("genre/{name}") { GenreScreen(it.arg("name")) }
    screen("mix/{key}") { MixScreen(it.arg("key")) }
    screen("playlist-edit/{id}") { PlaylistEditScreen(it.arg("id")) }
}
