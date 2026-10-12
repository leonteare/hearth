@file:Suppress("DEPRECATION") // The deprecated globals are defined and used for each other here.

package im.flume.hearth.core.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import android.provider.Settings
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The user's chosen accent colour. Composables read it as `MaterialTheme.colorScheme.primary`;
 * this global is only for code outside composition (and for building the colour scheme).
 */
val accentState = mutableStateOf(Color(0xFFFF8A3D))
val Accent: Color get() = accentState.value

/** Deep tint of the accent, used for the mini player and default page backgrounds. */
val AccentDeep: Color get() = lerp(Accent, Color.Black, 0.72f)

val AccentChoices = listOf(
    "Ember" to 0xFFFF8A3D,
    "Green" to 0xFF1ED760,
    "Blue" to 0xFF4A9DFF,
    "Purple" to 0xFFB283FF,
    "Pink" to 0xFFFF6FA5,
    "Red" to 0xFFFF5A5A,
    "Teal" to 0xFF2EC4B6,
    "Gold" to 0xFFFFC53D,
)
/*
 * The colour globals below are the default skin's values. New code reads them by role through
 * HearthTheme.colors (Tokens.kt), so a skin can change them in one place; these stay so older code keeps
 * compiling, and they are deprecated (a warning, not an error) to steer callers to the tokens.
 */
@Deprecated("Read it from the theme so a skin can change it.", ReplaceWith("HearthTheme.colors.background", "im.flume.hearth.core.ui.theme.HearthTheme"))
val Background = Color(0xFF121212)
@Deprecated("Read it from the theme so a skin can change it.", ReplaceWith("HearthTheme.colors.surface", "im.flume.hearth.core.ui.theme.HearthTheme"))
val Surface = Color(0xFF1C1C1C)
@Deprecated("Read it from the theme so a skin can change it.", ReplaceWith("HearthTheme.colors.surfaceHigh", "im.flume.hearth.core.ui.theme.HearthTheme"))
val SurfaceHigh = Color(0xFF282828)
@Deprecated("Read it from the theme so a skin can change it.", ReplaceWith("HearthTheme.colors.textSecondary", "im.flume.hearth.core.ui.theme.HearthTheme"))
val TextSecondary = Color(0xFFB3B3B3)

/**
 * Alpha for dimmed text: music that isn't on the server yet, or can't play offline. Cover art is never
 * dimmed. 0.7 is the lowest that keeps [TextSecondary] readable on [Background] (4.5:1, checked in
 * ContrastTest); the cloud icons next to dimmed rows carry the rest of the meaning.
 */
const val DimmedTextAlpha = 0.7f

/** Text alpha for music that isn't on the server yet (rows, cards, headers). */
const val NotOnServerAlpha = DimmedTextAlpha

/** Secondary text dimmed for something that can't be used right now, still readable on [Background]. */
@Deprecated("Read it from the theme so a skin can change it.", ReplaceWith("HearthTheme.colors.textDim", "im.flume.hearth.core.ui.theme.HearthTheme"))
val DimmedText = TextSecondary.copy(alpha = DimmedTextAlpha)

/** Tile colour for Liked Songs (shortcuts, quick tiles, page header). */
@Deprecated("Read it from the theme so a skin can change it.", ReplaceWith("HearthTheme.colors.liked", "im.flume.hearth.core.ui.theme.HearthTheme"))
val LikedColor = Color(0xFF5038A0)

/** Tile colour for Downloads (shortcuts, quick tiles, page header). */
@Deprecated("Read it from the theme so a skin can change it.", ReplaceWith("HearthTheme.colors.downloads", "im.flume.hearth.core.ui.theme.HearthTheme"))
val DownloadsColor = Color(0xFF1E6B52)

/** Tile colour for Podcasts (Home's quick tiles). */
@Deprecated("Read it from the theme so a skin can change it.", ReplaceWith("HearthTheme.colors.podcasts", "im.flume.hearth.core.ui.theme.HearthTheme"))
val PodcastsColor = Color(0xFF1E5A8C)

/** Tile colour for Audiobooks (Home's quick tiles). */
@Deprecated("Read it from the theme so a skin can change it.", ReplaceWith("HearthTheme.colors.audiobooks", "im.flume.hearth.core.ui.theme.HearthTheme"))
val AudiobooksColor = Color(0xFF8C4A1E)

/**
 * Destructive actions. [Destructive] is for text and icons on the dark background (readable pink-red);
 * [DestructiveFill] is for solid fills such as the swipe-to-remove background, with white content on top.
 */
@Deprecated("Read it from the theme so a skin can change it.", ReplaceWith("HearthTheme.colors.destructive", "im.flume.hearth.core.ui.theme.HearthTheme"))
val Destructive = Color(0xFFF2B8B5)
@Deprecated("Read it from the theme so a skin can change it.", ReplaceWith("HearthTheme.colors.destructiveFill", "im.flume.hearth.core.ui.theme.HearthTheme"))
val DestructiveFill = Color(0xFFB3261E)

/** The small red dot for something waiting on you (a playlist invite). */
@Deprecated("Read it from the theme so a skin can change it.", ReplaceWith("HearthTheme.colors.noticeDot", "im.flume.hearth.core.ui.theme.HearthTheme"))
val NoticeDot = Color(0xFFE5484D)

/** Hairline between groups. Translucent so it reads the same on the page and on sheets. */
@Deprecated("Read it from the theme so a skin can change it.", ReplaceWith("HearthTheme.colors.divider", "im.flume.hearth.core.ui.theme.HearthTheme"))
val DividerColor = Color.White.copy(alpha = 0.08f)

/** Bottom navigation bar and the landscape side rail. */
@Deprecated("Read it from the theme so a skin can change it.", ReplaceWith("HearthTheme.colors.navBar", "im.flume.hearth.core.ui.theme.HearthTheme"))
val NavBarColor = Color(0xF0101010)

/** The white, Spotify-style search field and what sits on it. */
@Deprecated("Read it from the theme so a skin can change it.", ReplaceWith("HearthTheme.colors.searchField", "im.flume.hearth.core.ui.theme.HearthTheme"))
val SearchFieldColor = Color.White
@Deprecated("Read it from the theme so a skin can change it.", ReplaceWith("HearthTheme.colors.onSearchField", "im.flume.hearth.core.ui.theme.HearthTheme"))
val OnSearchField = Color.Black
@Deprecated("Read it from the theme so a skin can change it.", ReplaceWith("HearthTheme.colors.searchFieldHint", "im.flume.hearth.core.ui.theme.HearthTheme"))
val SearchFieldHint = Color(0xFF444444)

/**
 * Spacing and sizes shared by rows, cards and carousels. The spacing values are deprecated in favour of
 * HearthTheme.spacing (same values by default); the corner radii stay, since [HearthShapes] is built from them.
 */
object Dimens {
    /** Page side margin. */
    @Deprecated("Read it from the theme so a skin can change it.", ReplaceWith("HearthTheme.spacing.gutter", "im.flume.hearth.core.ui.theme.HearthTheme"))
    val Gutter = 16.dp
    /** Cover size in list rows. */
    @Deprecated("Read it from the theme so a skin can change it.", ReplaceWith("HearthTheme.spacing.rowCover", "im.flume.hearth.core.ui.theme.HearthTheme"))
    val RowCover = 48.dp
    /** Vertical padding of a list row. */
    @Deprecated("Read it from the theme so a skin can change it.", ReplaceWith("HearthTheme.spacing.rowPaddingV", "im.flume.hearth.core.ui.theme.HearthTheme"))
    val RowPaddingV = 8.dp
    /** Gap between a row's cover and its text. */
    @Deprecated("Read it from the theme so a skin can change it.", ReplaceWith("HearthTheme.spacing.coverGap", "im.flume.hearth.core.ui.theme.HearthTheme"))
    val CoverGap = 12.dp
    /** Gap between cards in a horizontal carousel. */
    @Deprecated("Read it from the theme so a skin can change it.", ReplaceWith("HearthTheme.spacing.carouselSpacing", "im.flume.hearth.core.ui.theme.HearthTheme"))
    val CarouselSpacing = 14.dp
    /** Inner padding of banners and cards. */
    @Deprecated("Read it from the theme so a skin can change it.", ReplaceWith("HearthTheme.spacing.cardPadding", "im.flume.hearth.core.ui.theme.HearthTheme"))
    val CardPadding = 14.dp
    /** Width of a card in a carousel. */
    @Deprecated("Read it from the theme so a skin can change it.", ReplaceWith("HearthTheme.spacing.cardWidth", "im.flume.hearth.core.ui.theme.HearthTheme"))
    val CardWidth = 140.dp
    /** Top padding of a tab page's title (below the status bar). */
    @Deprecated("Read it from the theme so a skin can change it.", ReplaceWith("HearthTheme.spacing.pageTitleTop", "im.flume.hearth.core.ui.theme.HearthTheme"))
    val PageTitleTop = 12.dp

    val CoverCorner = 4.dp
    val CardCorner = 8.dp
    val ChipCorner = 6.dp
}

/** Corner shapes: covers 4dp, cards / tiles / banners 8dp, small chips 6dp. */
object HearthShapes {
    val Cover = RoundedCornerShape(Dimens.CoverCorner)
    val Card = RoundedCornerShape(Dimens.CardCorner)
    val Chip = RoundedCornerShape(Dimens.ChipCorner)
    val Round = CircleShape
}

private fun colorScheme(accent: Color, c: HearthColors) = darkColorScheme(
    primary = accent,
    onPrimary = c.onAccent,
    secondary = accent,
    background = c.background,
    onBackground = c.text,
    surface = c.background,
    onSurface = c.text,
    surfaceVariant = c.surface,
    onSurfaceVariant = c.textSecondary,
    surfaceContainer = c.surface,
    surfaceContainerHigh = c.surfaceHigh,
    surfaceContainerHighest = c.surfaceHigh,
    surfaceContainerLow = c.surface,
    outline = c.outline,
    outlineVariant = c.divider,
    error = c.destructive,
)

private val Type = Typography(
    displayLarge = TextStyle(fontSize = 48.sp, fontWeight = FontWeight.Bold),
    displayMedium = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.Bold),
    displaySmall = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold),
    headlineLarge = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold),
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
)

/** Title under a card's cover: body size, semi-bold. */
val CardTitleStyle: TextStyle @Composable get() = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)

/**
 * True when the phone's "Remove animations" setting is on (animator duration scale 0). Slides and
 * crossfades that are only decoration are skipped then, since motion can make some people feel unwell.
 */
val LocalReducedMotion = staticCompositionLocalOf { false }

/** The app theme. The token defaults are today's look; a skin passes its own (see Tokens.kt). */
@Composable
fun HearthTheme(
    colors: HearthColors = HearthColors(),
    spacing: HearthSpacing = HearthSpacing(),
    shapes: HearthShapeSet = HearthShapeSet(),
    icons: HearthIcons = HearthIcons(),
    content: @Composable () -> Unit,
) {
    val resolver = LocalContext.current.contentResolver
    val reducedMotion = remember(resolver) {
        runCatching { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
    val accent = Accent
    // Content on the accent follows the accent the user picked, so a pale or dark custom accent stays readable.
    val themed = remember(colors, accent) { colors.copy(onAccent = readableOn(accent)) }
    CompositionLocalProvider(
        LocalReducedMotion provides reducedMotion,
        LocalHearthColors provides themed,
        LocalHearthSpacing provides spacing,
        LocalHearthShapes provides shapes,
        LocalHearthIcons provides icons,
    ) {
        MaterialTheme(colorScheme = colorScheme(accent, themed), typography = Type, content = content)
    }
}
