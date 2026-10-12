// Semantic design tokens: what each colour, gap, shape and icon is FOR, so a skin can swap them in one place.
// The defaults are today's look exactly; the older globals in Theme.kt (TextSecondary, Dimens, HearthShapes...)
// stay and mean the same thing. Read them in composables through HearthTheme.colors / spacing / shapes / icons.
package im.flume.hearth.ui.theme

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Downloading
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.HideImage
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.ArrowCircleDown
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.TimerOff
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Whatshot
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Colours by role. The accent isn't here: it's the user's choice and lives in
 * `MaterialTheme.colorScheme.primary` (with [onAccent] for content on it).
 */
@Immutable
data class HearthColors(
    val background: Color = Background,
    val surface: Color = Surface,
    val surfaceHigh: Color = SurfaceHigh,
    /** Main text and icons on the background. */
    val text: Color = Color.White,
    val textSecondary: Color = TextSecondary,
    /** Secondary text for something that can't be used right now (see [DimmedText]). */
    val textDim: Color = DimmedText,
    val divider: Color = DividerColor,
    /** Outlines of fields and outlined buttons. */
    val outline: Color = Color(0xFF3E3E3E),
    val navBar: Color = NavBarColor,
    val searchField: Color = SearchFieldColor,
    val onSearchField: Color = OnSearchField,
    val searchFieldHint: Color = SearchFieldHint,
    val liked: Color = LikedColor,
    val downloads: Color = DownloadsColor,
    val podcasts: Color = PodcastsColor,
    val audiobooks: Color = AudiobooksColor,
    val destructive: Color = Destructive,
    val destructiveFill: Color = DestructiveFill,
    val noticeDot: Color = NoticeDot,
    /** Text and icons over cover art and cover-tinted gradients. */
    val onCover: Color = Color.White,
    /** Text and icons on an accent fill (buttons, the selected accent swatch). */
    val onAccent: Color = Color.Black,
    /** Icons on a tile filled with a fixed colour (Home's Liked Songs, Podcasts and Audiobooks tiles). */
    val onColorFill: Color = Color.White,
    /** Darkens what's under it, used with an alpha: stat tiles on the stats header, the fade under a progress bar on a cover. */
    val shade: Color = Color.Black,
    /** Search's genre tiles; each genre always gets the same one (picked by its name). */
    val genrePalette: List<Color> = listOf(
        Color(0xFF8C1932), Color(0xFF1E3264), Color(0xFF477D95), Color(0xFF8D67AB), Color(0xFFE8115B),
        Color(0xFF148A08), Color(0xFFBA5D07), Color(0xFF503750), Color(0xFF0D73EC), Color(0xFF777777),
    ),
) {
    /** Same as [text], under Material's name. */
    val onBackground: Color get() = text
}

/** Gaps and sizes. The scale (xxs…xxl) covers the common paddings; the named ones are [Dimens]. */
@Immutable
data class HearthSpacing(
    val xxs: Dp = 2.dp,
    val xs: Dp = 4.dp,
    val s: Dp = 8.dp,
    val m: Dp = 12.dp,
    val l: Dp = 16.dp,
    val xl: Dp = 24.dp,
    val xxl: Dp = 32.dp,
    /** Page side margin. */
    val gutter: Dp = Dimens.Gutter,
    /** Cover size in list rows. */
    val rowCover: Dp = Dimens.RowCover,
    /** Vertical padding of a list row. */
    val rowPaddingV: Dp = Dimens.RowPaddingV,
    /** Gap between a row's cover and its text. */
    val coverGap: Dp = Dimens.CoverGap,
    /** Gap between cards in a horizontal carousel. */
    val carouselSpacing: Dp = Dimens.CarouselSpacing,
    /** Inner padding of banners and cards. */
    val cardPadding: Dp = Dimens.CardPadding,
    /** Width of a card in a carousel. */
    val cardWidth: Dp = Dimens.CardWidth,
    /** Top padding of a tab page's title (below the status bar). */
    val pageTitleTop: Dp = Dimens.PageTitleTop,
)

/** Corner shapes by role; defaults are [HearthShapes]. */
@Immutable
data class HearthShapeSet(
    val cover: CornerBasedShape = HearthShapes.Cover,
    val card: CornerBasedShape = HearthShapes.Card,
    val chip: CornerBasedShape = HearthShapes.Chip,
    val round: CornerBasedShape = HearthShapes.Round,
)

/** Icons by meaning, so a skin can swap the glyph set. Defaults are the Material icons used today. */
@Immutable
data class HearthIcons(
    // Playback
    val play: ImageVector = Icons.Filled.PlayArrow,
    val pause: ImageVector = Icons.Filled.Pause,
    val next: ImageVector = Icons.Filled.SkipNext,
    val previous: ImageVector = Icons.Filled.SkipPrevious,
    val stop: ImageVector = Icons.Filled.Stop,
    val shuffle: ImageVector = Icons.Filled.Shuffle,
    val repeat: ImageVector = Icons.Filled.Repeat,
    val repeatOne: ImageVector = Icons.Filled.RepeatOne,
    val replay: ImageVector = Icons.Filled.Replay,
    val radio: ImageVector = Icons.Filled.Radio,
    val queue: ImageVector = Icons.AutoMirrored.Filled.QueueMusic,
    val playNext: ImageVector = Icons.AutoMirrored.Filled.PlaylistPlay,
    val addToPlaylist: ImageVector = Icons.AutoMirrored.Filled.PlaylistAdd,
    val lyrics: ImageVector = Icons.Outlined.Mic,
    val lyricsOn: ImageVector = Icons.Filled.Mic,
    val visualiser: ImageVector = Icons.Filled.GraphicEq,
    val chapters: ImageVector = Icons.AutoMirrored.Filled.FormatListBulleted,
    val sleepTimer: ImageVector = Icons.Outlined.Bedtime,
    val timer: ImageVector = Icons.Outlined.Timer,
    val timerOff: ImageVector = Icons.Outlined.TimerOff,
    val slower: ImageVector = Icons.Filled.Remove,

    // Likes and downloads
    val like: ImageVector = Icons.Filled.FavoriteBorder,
    val likeOn: ImageVector = Icons.Filled.Favorite,
    val download: ImageVector = Icons.Outlined.ArrowCircleDown,
    val downloaded: ImageVector = Icons.Filled.CheckCircle,
    val downloading: ImageVector = Icons.Filled.Downloading,
    val downloadArrow: ImageVector = Icons.Filled.ArrowDownward,
    val failed: ImageVector = Icons.Filled.ErrorOutline,
    val failedOutline: ImageVector = Icons.Outlined.ErrorOutline,

    // Navigation and page chrome
    val back: ImageVector = Icons.AutoMirrored.Filled.ArrowBack,
    val collapse: ImageVector = Icons.Filled.KeyboardArrowDown,
    val chevron: ImageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
    val expandUp: ImageVector = Icons.Filled.ArrowDropUp,
    val more: ImageVector = Icons.Filled.MoreVert,
    val moreHorizontal: ImageVector = Icons.Filled.MoreHoriz,
    val search: ImageVector = Icons.Filled.Search,
    val close: ImageVector = Icons.Filled.Close,
    val add: ImageVector = Icons.Filled.Add,
    val check: ImageVector = Icons.Filled.Check,
    val sort: ImageVector = Icons.AutoMirrored.Filled.Sort,
    val history: ImageVector = Icons.Filled.History,
    val refresh: ImageVector = Icons.Filled.Refresh,
    val info: ImageVector = Icons.Outlined.Info,
    val home: ImageVector = Icons.Filled.Home,
    val library: ImageVector = Icons.Filled.LibraryMusic,
    val settings: ImageVector = Icons.Filled.Settings,
    val update: ImageVector = Icons.Filled.SystemUpdate,

    // Selection and editing
    val selected: ImageVector = Icons.Filled.CheckCircle,
    val unselected: ImageVector = Icons.Outlined.Circle,
    val selectAll: ImageVector = Icons.Filled.SelectAll,
    val dragHandle: ImageVector = Icons.Filled.DragHandle,
    val reorder: ImageVector = Icons.Filled.SwapVert,
    val edit: ImageVector = Icons.Filled.Edit,
    val delete: ImageVector = Icons.Filled.Delete,
    val removeFromPlaylist: ImageVector = Icons.Filled.RemoveCircleOutline,
    val removeFromLibrary: ImageVector = Icons.Outlined.RemoveCircleOutline,
    val share: ImageVector = Icons.Filled.GroupAdd,
    val leave: ImageVector = Icons.AutoMirrored.Filled.ExitToApp,
    val changePhoto: ImageVector = Icons.Filled.AddPhotoAlternate,
    val removePhoto: ImageVector = Icons.Filled.HideImage,
    val importFile: ImageVector = Icons.Filled.UploadFile,

    // Kinds of things
    val song: ImageVector = Icons.Filled.MusicNote,
    val album: ImageVector = Icons.Filled.Album,
    val albumOutline: ImageVector = Icons.Outlined.Album,
    val artist: ImageVector = Icons.Filled.Person,
    val everything: ImageVector = Icons.Outlined.LibraryMusic,
    val popular: ImageVector = Icons.Outlined.Whatshot,
    val podcast: ImageVector = Icons.Filled.Podcasts,
    val audiobook: ImageVector = Icons.AutoMirrored.Filled.MenuBook,
    val phone: ImageVector = Icons.Outlined.PhoneAndroid,
    val folder: ImageVector = Icons.Outlined.Folder,
    val brand: ImageVector = Icons.Filled.LocalFireDepartment,

    // Spoken word progress
    val markPlayed: ImageVector = Icons.Outlined.CheckCircle,
    val markUnplayed: ImageVector = Icons.Outlined.RadioButtonUnchecked,
    val hidden: ImageVector = Icons.Outlined.VisibilityOff,

    // Server and connection state
    val offline: ImageVector = Icons.Filled.CloudOff,
    val notOnServer: ImageVector = Icons.Outlined.Cloud,
    val addToServer: ImageVector = Icons.Outlined.CloudUpload,
    val fetchToServer: ImageVector = Icons.Outlined.CloudDownload,

    // Settings sections
    val settingsGeneral: ImageVector = Icons.Outlined.Settings,
    val storage: ImageVector = Icons.Outlined.Storage,
    val appearance: ImageVector = Icons.Outlined.Palette,
)

val LocalHearthColors = staticCompositionLocalOf { HearthColors() }
val LocalHearthSpacing = staticCompositionLocalOf { HearthSpacing() }
val LocalHearthShapes = staticCompositionLocalOf { HearthShapeSet() }
val LocalHearthIcons = staticCompositionLocalOf { HearthIcons() }

/** The current skin's tokens, provided by the HearthTheme composable (Theme.kt). */
object HearthTheme {
    val colors: HearthColors
        @Composable @ReadOnlyComposable get() = LocalHearthColors.current
    val spacing: HearthSpacing
        @Composable @ReadOnlyComposable get() = LocalHearthSpacing.current
    val shapes: HearthShapeSet
        @Composable @ReadOnlyComposable get() = LocalHearthShapes.current
    val icons: HearthIcons
        @Composable @ReadOnlyComposable get() = LocalHearthIcons.current
    val typography: Typography
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography
}
