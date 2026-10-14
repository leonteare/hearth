// The full-screen visualiser: picks a style, feeds it audio, and handles taps, swipes and closing.
package im.flume.hearth.feature.visualiser

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import im.flume.hearth.core.playback.container
import im.flume.hearth.core.playback.AudioAnalyzer
import im.flume.hearth.core.playback.EXTRA_COVER_ART
import im.flume.hearth.core.playback.PlayerUiState
import im.flume.hearth.core.ui.components.LocalActions
import im.flume.hearth.core.ui.components.coverPalette
import im.flume.hearth.core.ui.theme.CoverSize
import im.flume.hearth.core.ui.theme.HearthTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * Full-screen music visualiser over Now Playing. Tap for controls (they hide after 3 s), swipe
 * sideways to change style. Audio analysis and drawing run only while this is on screen and the
 * app is in the foreground; the system bars are hidden meanwhile, and the screen kept on while
 * music plays. Paused, it stops drawing once the picture has settled, and the screen may sleep —
 * unless the style keeps moving with no music (Sphere's slow turn), which carries on at a gentle
 * idle frame rate.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VisualiserScreen(state: PlayerUiState, onClose: () -> Unit) {
    val context = LocalContext.current
    val c = context.container
    val actions = LocalActions.current
    val accent = MaterialTheme.colorScheme.primary
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val swipeAt = with(LocalDensity.current) { 72.dp.toPx() }

    ImmersiveMode(keepScreenOn = state.isPlaying)

    var style by remember { mutableStateOf(VisualiserStyle.byId(c.session.settings.value.visualiserStyle)) }
    var outgoing by remember { mutableStateOf<VisualiserStyle?>(null) }
    val fade = remember { Animatable(1f) }
    val renderers = remember { HashMap<VisualiserStyle, VisualiserRenderer>() }
    fun renderer(s: VisualiserStyle) = renderers.getOrPut(s) { s.create() }
    DisposableEffect(Unit) {
        onDispose {
            renderers.values.forEach { it.release() }
            renderers.clear()
        }
    }
    var controls by remember { mutableStateOf(true) }
    var interaction by remember { mutableIntStateOf(0) }
    var picker by remember { mutableStateOf(false) }

    fun switchTo(next: VisualiserStyle) {
        if (next == style) return
        outgoing = style
        style = next
        c.session.updateSettings { it.copy(visualiserStyle = next.id) }
        scope.launch {
            fade.snapTo(0f)
            fade.animateTo(1f, tween(400))
            outgoing = null
            renderers.keys.filter { it != style }.forEach { renderers.remove(it)?.release() }
        }
    }

    fun neighbour(step: Int): VisualiserStyle {
        val all = VisualiserStyle.entries
        return all[(all.indexOf(style) + step + all.size) % all.size]
    }

    // ---------------------------------------------------------------- frame loop

    val frame = remember { VisFrame() }
    var tick by remember { mutableLongStateOf(0L) }
    /** Goes up when new cover colours arrive, to wake a paused, sleeping visualiser to blend them in. */
    var paletteChanges by remember { mutableIntStateOf(0) }
    val playing by rememberUpdatedState(state.isPlaying)
    val meta = state.current?.mediaMetadata
    SideEffect {
        frame.title = meta?.title?.toString().orEmpty()
        frame.artist = meta?.artist?.toString().orEmpty()
        frame.durationMs = state.durationMs
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        // Stops when the app goes to the background, and with it the audio tap.
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            AudioAnalyzer.start()
            val pacer = FramePacer()
            try {
                var last = 0L
                var slowFor = 0f
                while (isActive) {
                    var asleep = false
                    var sleptStyle = style
                    var sleptPalette = paletteChanges
                    withFrameNanos { now ->
                        if (!pacer.shouldDraw(now)) return@withFrameNanos
                        val dt = if (last == 0L) 1f / 60f else ((now - last) / 1e9f).coerceIn(0.001f, 0.1f)
                        last = now
                        frame.dt = dt
                        frame.time += dt
                        frame.isPlaying = playing
                        frame.audio = AudioAnalyzer.analyze(playing)
                        if (renderer(style).usesStereo || outgoing?.let { renderer(it).usesStereo } == true) {
                            AudioAnalyzer.readStereo(frame.stereo)
                        }
                        frame.positionMs = c.player.positionMs
                        frame.palette.step(dt)
                        // Running late for a couple of seconds (outside a crossfade, and not the
                        // deliberately slow paused idle): draw less.
                        val late = dt > 1f / 42f && outgoing == null && !pacer.isIdling
                        slowFor = if (late) slowFor + dt else max(0f, slowFor - dt * 0.5f)
                        if (slowFor > 2f) {
                            slowFor = 0f
                            val r = renderer(style)
                            if (r.quality > 0.5f) r.quality = max(0.5f, r.quality - 0.25f)
                        }
                        tick = now
                        val still = outgoing == null && frame.palette.settled && FramePacer.isQuiet(frame.audio)
                        // A style that moves on its own (Sphere) idles slowly instead of stopping.
                        if (pacer.sleep(dt, playing, still, renderer(style).movesWhenPaused)) {
                            asleep = true
                            sleptStyle = style
                            sleptPalette = paletteChanges
                        }
                    }
                    if (asleep) {
                        // The last frame stays on screen; draw again once anything would change it.
                        snapshotFlow { playing || style != sleptStyle || paletteChanges != sleptPalette }.first { it }
                        pacer.wake()
                        last = 0L
                    }
                }
            } finally {
                AudioAnalyzer.stop()
            }
        }
    }

    // Cover art: the colours of every style.
    val coverArt = state.current?.mediaMetadata?.extras?.getString(EXTRA_COVER_ART)
    var paletteSet by remember { mutableStateOf(false) }
    LaunchedEffect(coverArt) {
        // Now Playing's size, so it's already cached; decoded small, as the palette only needs a few colours.
        val url = coverArt?.let { actions.coverUrl(it, CoverSize.LARGE) }
        val bitmap = url?.let {
            val request = ImageRequest.Builder(context).data(it).size(CoverSize.MEDIUM).allowHardware(false).build()
            (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toBitmap()
        }
        val swatches = if (bitmap != null && coverArt != null) withContext(Dispatchers.Default) { coverPalette(coverArt, bitmap) } else emptyList()
        val colours = VisPalette.vivid(swatches, accent)
        if (paletteSet) frame.palette.setTarget(colours) else frame.palette.snap(colours)
        paletteSet = true
        paletteChanges++
    }

    LaunchedEffect(controls, interaction, picker) {
        if (controls && !picker) {
            delay(3000)
            controls = false
        }
    }

    // ---------------------------------------------------------------- layout

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) { detectTapGestures { controls = !controls; interaction++ } }
            .pointerInput(Unit) {
                var dx = 0f
                detectHorizontalDragGestures(
                    onDragStart = { dx = 0f },
                    onDragEnd = {
                        if (abs(dx) > swipeAt) {
                            haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                            switchTo(neighbour(if (dx < 0) 1 else -1))
                        }
                    },
                ) { change, drag -> change.consume(); dx += drag }
            }
    ) {
        outgoing?.let { RenderLayer(renderer(it), frame, { tick }, { 1f }) }
        RenderLayer(renderer(style), frame, { tick }, { if (outgoing != null) fade.value else 1f })


        AnimatedVisibility(visible = controls, enter = fadeIn(tween(200)), exit = fadeOut(tween(400))) {
            Controls(
                state = state,
                style = style,
                onClose = onClose,
                onPick = { picker = true; interaction++ },
                onAction = { interaction++ },
            )
        }
    }

    if (picker) {
        val spacing = HearthTheme.spacing
        ModalBottomSheet(onDismissRequest = { picker = false; interaction++ }, containerColor = HearthTheme.colors.surfaceHigh) {
            Column(Modifier.navigationBarsPadding().padding(bottom = spacing.l)) {
                for ((group, retro) in listOf("Modern" to false, "Retro" to true)) {
                    Text(
                        group, style = MaterialTheme.typography.labelLarge, color = HearthTheme.colors.textSecondary,
                        modifier = Modifier.padding(start = spacing.xl, top = spacing.m, bottom = spacing.xs),
                    )
                    VisualiserStyle.entries.filter { it.retro == retro }.forEach { s ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 52.dp)
                                .clickable(role = Role.RadioButton) { picker = false; interaction++; switchTo(s) }
                                .padding(horizontal = spacing.xl),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(s.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                            if (s == style) Icon(HearthTheme.icons.check, "Selected", tint = accent)
                        }
                    }
                }
            }
        }
    }
}
