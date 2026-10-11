package im.flume.hearth.ui.screens.visualiser

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import im.flume.hearth.container
import im.flume.hearth.playback.AudioAnalyzer
import im.flume.hearth.playback.EXTRA_COVER_ART
import im.flume.hearth.playback.PlayerUiState
import im.flume.hearth.ui.components.LocalActions
import im.flume.hearth.ui.components.coverPalette
import im.flume.hearth.ui.theme.SurfaceHigh
import im.flume.hearth.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max

/**
 * Full-screen music visualiser over Now Playing. Tap for controls (they hide after 3 s), swipe
 * sideways to change style. Audio analysis and drawing run only while this is on screen and the
 * app is in the foreground; the system bars are hidden and the screen kept on meanwhile.
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

    ImmersiveMode()

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
    var toast by remember { mutableStateOf("") }
    var toastKey by remember { mutableIntStateOf(0) }
    var toastVisible by remember { mutableStateOf(false) }
    var controls by remember { mutableStateOf(true) }
    var interaction by remember { mutableIntStateOf(0) }
    var picker by remember { mutableStateOf(false) }

    fun switchTo(next: VisualiserStyle) {
        if (next == style) return
        outgoing = style
        style = next
        c.session.updateSettings { it.copy(visualiserStyle = next.id) }
        toast = next.label
        toastKey++
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
            try {
                var last = 0L
                var slowFor = 0f
                while (isActive) {
                    withFrameNanos { now ->
                        val dt = if (last == 0L) 1f / 60f else ((now - last) / 1e9f).coerceIn(0.001f, 0.1f)
                        last = now
                        frame.dt = dt
                        frame.time += dt
                        frame.isPlaying = playing
                        frame.audio = AudioAnalyzer.analyze(playing)
                        frame.positionMs = c.player.positionMs
                        frame.palette.step(dt)
                        // Running late for a couple of seconds (outside a crossfade): draw less.
                        slowFor = if (dt > 1f / 42f && outgoing == null) slowFor + dt else max(0f, slowFor - dt * 0.5f)
                        if (slowFor > 2f) {
                            slowFor = 0f
                            val r = renderer(style)
                            if (r.quality > 0.5f) r.quality = max(0.5f, r.quality - 0.25f)
                        }
                        tick = now
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
        val url = coverArt?.let { actions.coverUrl(it, 600) }
        val bitmap = url?.let {
            val request = ImageRequest.Builder(context).data(it).size(600).allowHardware(false).build()
            (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toBitmap()
        }
        val swatches = if (bitmap != null && coverArt != null) withContext(Dispatchers.Default) { coverPalette(coverArt, bitmap) } else emptyList()
        val colours = VisPalette.vivid(swatches, accent)
        if (paletteSet) frame.palette.setTarget(colours) else frame.palette.snap(colours)
        paletteSet = true
    }

    LaunchedEffect(controls, interaction, picker) {
        if (controls && !picker) {
            delay(3000)
            controls = false
        }
    }
    LaunchedEffect(toastKey) {
        if (toastKey == 0) return@LaunchedEffect
        toastVisible = true
        delay(1300)
        toastVisible = false
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

        AnimatedVisibility(
            visible = toastVisible,
            enter = fadeIn(tween(150)) + scaleIn(tween(150), initialScale = 0.9f),
            exit = fadeOut(tween(300)) + scaleOut(tween(300), targetScale = 1.05f),
            modifier = Modifier.align(Alignment.Center),
        ) {
            Text(
                toast,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(50))
                    .padding(horizontal = 24.dp, vertical = 12.dp),
            )
        }

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
        ModalBottomSheet(onDismissRequest = { picker = false; interaction++ }, containerColor = SurfaceHigh) {
            Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
                for ((group, retro) in listOf("Modern" to false, "Retro" to true)) {
                    Text(
                        group, style = MaterialTheme.typography.labelLarge, color = TextSecondary,
                        modifier = Modifier.padding(start = 24.dp, top = 12.dp, bottom = 4.dp),
                    )
                    VisualiserStyle.entries.filter { it.retro == retro }.forEach { s ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 52.dp)
                                .clickable(role = Role.RadioButton) { picker = false; interaction++; switchTo(s) }
                                .padding(horizontal = 24.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(s.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                            if (s == style) Icon(Icons.Default.Check, "Selected", tint = accent)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Controls(
    state: PlayerUiState,
    style: VisualiserStyle,
    onClose: () -> Unit,
    onPick: () -> Unit,
    onAction: () -> Unit,
) {
    val player = LocalContext.current.container.player
    val meta = state.current?.mediaMetadata
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(180.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.65f), Color.Transparent))))
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(280.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))))
        )
        Row(Modifier.safeDrawingPadding().padding(8.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close visualiser", tint = Color.White) }
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(meta?.title?.toString().orEmpty(), color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(meta?.artist?.toString().orEmpty(), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Column(
            Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(bottom = 24.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                Modifier
                    .background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(50))
                    .clickable(onClickLabel = "Choose a style", onClick = onPick)
                    .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.GraphicEq, null, tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(style.label, color = Color.White, style = MaterialTheme.typography.labelLarge)
                Icon(Icons.Default.ArrowDropUp, null, tint = Color.White)
            }
            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                IconButton(onClick = { onAction(); player.previous() }, Modifier.size(56.dp)) {
                    Icon(Icons.Default.SkipPrevious, "Previous", tint = Color.White, modifier = Modifier.size(36.dp))
                }
                FilledIconButton(
                    onClick = { onAction(); player.playPause() },
                    modifier = Modifier.size(68.dp),
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White, contentColor = Color.Black),
                ) {
                    Icon(
                        if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        if (state.isPlaying) "Pause" else "Play",
                        modifier = Modifier.size(38.dp),
                    )
                }
                IconButton(onClick = { onAction(); player.next() }, Modifier.size(56.dp)) {
                    Icon(Icons.Default.SkipNext, "Next", tint = Color.White, modifier = Modifier.size(36.dp))
                }
            }
            Spacer(Modifier.height(10.dp))
            Text("Swipe sideways for another style", color = Color.White.copy(alpha = 0.5f), style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** One style's drawing surface, faded by [alpha] during a crossfade. */
@Composable
private fun RenderLayer(renderer: VisualiserRenderer, frame: VisFrame, tick: () -> Long, alpha: () -> Float) {
    Canvas(
        Modifier
            .fillMaxSize()
            .graphicsLayer { this.alpha = alpha() }
    ) {
        if (tick() >= 0L) renderer.draw(this, frame)
    }
}

/** Hides the status and navigation bars (swipe to peek) and keeps the screen on, until disposed. */
@Composable
private fun ImmersiveMode() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        view.keepScreenOn = true
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            view.keepScreenOn = false
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
