package im.flume.hearth.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** The user's chosen accent colour. Reading it inside composition recomposes when it changes. */
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
val Background = Color(0xFF121212)
val Surface = Color(0xFF1C1C1C)
val SurfaceHigh = Color(0xFF282828)
val TextSecondary = Color(0xFFB3B3B3)

private fun colors(accent: Color) = darkColorScheme(
    primary = accent,
    onPrimary = Color.Black,
    secondary = accent,
    background = Background,
    onBackground = Color.White,
    surface = Background,
    onSurface = Color.White,
    surfaceVariant = Surface,
    onSurfaceVariant = TextSecondary,
    surfaceContainer = Surface,
    surfaceContainerHigh = SurfaceHigh,
    surfaceContainerHighest = SurfaceHigh,
    surfaceContainerLow = Surface,
    outline = Color(0xFF3E3E3E),
)

private val Type = Typography(
    headlineLarge = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun HearthTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors(Accent), typography = Type, content = content)
}
