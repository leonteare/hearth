package im.flume.hearth.ui

// The library feature's pages. Lives in the feature's module; :app's Routes.kt calls libraryRoutes().

import androidx.navigation.NavGraphBuilder
import im.flume.hearth.ui.components.arg
import im.flume.hearth.ui.components.screen
import im.flume.hearth.ui.screens.AlbumScreen
import im.flume.hearth.ui.screens.ArtistScreen
import im.flume.hearth.ui.screens.BrowseAlbumsScreen
import im.flume.hearth.ui.screens.BrowseArtistsScreen
import im.flume.hearth.ui.screens.BrowseSongsScreen
import im.flume.hearth.ui.screens.GenreScreen
import im.flume.hearth.ui.screens.LibraryScreen
import im.flume.hearth.ui.screens.LikedScreen
import im.flume.hearth.ui.screens.MixScreen
import im.flume.hearth.ui.screens.PlaylistEditScreen
import im.flume.hearth.ui.screens.PlaylistScreen

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
