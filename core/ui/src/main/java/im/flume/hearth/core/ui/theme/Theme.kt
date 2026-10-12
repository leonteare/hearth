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

/* Colours, spacing, shapes and icons are read by role through HearthTheme (Tokens.kt), so a skin changes them in one place. */

/**
 * Alpha for dimmed text: music that isn't on the server yet, or can't play offline. Cover art is never
 * dimmed. 0.7 is the lowest that keeps the secondary text colour readable on the page background (4.5:1, checked in
 * ContrastTest); the cloud icons next to dimmed rows carry the rest of the meaning.
 */
const val DimmedTextAlpha = 0.7f

/** Text alpha for music that isn't on the server yet (rows, cards, headers). */
const val NotOnServerAlpha = DimmedTextAlpha

/**
 * Corner radii shared by covers and cards ([HearthShapes] is built from them). Spacing lives in
 * HearthTheme.spacing.
 */
object Dimens {
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
