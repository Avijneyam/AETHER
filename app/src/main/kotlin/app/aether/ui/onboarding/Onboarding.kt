package app.aether.ui.onboarding

import android.Manifest
import android.content.pm.PackageManager
import android.view.TextureView
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.max
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aether.R
import app.aether.core.designsystem.component.GlassButton
import app.aether.core.designsystem.component.Glyph
import app.aether.core.designsystem.component.Icon
import app.aether.core.designsystem.component.LocalHaptics
import app.aether.core.designsystem.component.Txt
import app.aether.core.designsystem.component.litDrop
import app.aether.core.designsystem.glass.GlassHost
import app.aether.core.designsystem.glass.GlassWeight
import app.aether.core.designsystem.glass.LocalReduceMotion
import app.aether.core.designsystem.glass.liquidGlass
import app.aether.core.designsystem.glass.liquidTouch
import app.aether.core.designsystem.glass.rememberLiquidState
import app.aether.core.designsystem.theme.Corners
import app.aether.core.designsystem.theme.Ink
import app.aether.core.designsystem.theme.Motion
import app.aether.core.designsystem.theme.Space
import app.aether.core.designsystem.theme.Type
import app.aether.ui.LocalArrival
import app.aether.ui.account.SignInActivity
import app.aether.ui.common.LocalContainer
import kotlinx.coroutines.delay

/**
 * The welcome's wordmark: Aether, signed in the script, the size the boot film's resolves to, with
 * a faint halo of its own light so it stands forward of the dark water.
 */
val MastheadStyle = Type.Inscription.copy(fontSize = 68.sp, lineHeight = 76.sp, shadow = Shadow(Color.White.copy(alpha = 0.24f), blurRadius = 38f))

/**
 * The first visit: dark water at night, moving slowly, light catching its ripples in silver (a
 * filmed sea, in black and white, looping with no seam). The wordmark the boot film
 * handed over sits in the middle, where the film left it, a line of small capitals beneath. At the
 * foot, three keys of the tab bar's glass, refracting the water: YouTube Music (optional), timed
 * lyrics, and offline. Each asks for what it needs only when touched. Then Enter.
 */
@Composable
fun Onboarding(onDone: () -> Unit, modifier: Modifier = Modifier) {
    val arrival = LocalArrival.current
    val reduce = LocalReduceMotion.current
    val container = LocalContainer.current
    val context = LocalContext.current
    val words = remember { Animatable(if (reduce) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!reduce) {
            delay(900)
            words.animateTo(1f, Motion.fluid(1.1f, 1f))
        }
    }
    val session by container.account.cookies.collectAsStateWithLifecycle()
    val lyrics by container.settings.timedLyrics.collectAsStateWithLifecycle()
    val wanted = remember {
        buildList {
            add(if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    fun granted() = ContextCompat.checkSelfPermission(context, wanted.first()) == PackageManager.PERMISSION_GRANTED
    var offline by remember { mutableStateOf(granted()) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        wanted.forEach { container.settings.markAsked(it) }
        offline = granted()
    }

    GlassHost(
        modifier.fillMaxSize(),
        content = { Water(Modifier.fillMaxSize()) },
        overlay = {
            // A pool of shadow on the water where the wordmark sits, so the type reads on the brightest
            // stretch of the sea as it would on a dark one.
            Box(
                Modifier.fillMaxSize().drawBehind {
                    val c = Offset(size.width / 2f, size.height / 2f - 44.dp.toPx())
                    scale(1.9f, 1f, pivot = c) {
                        drawCircle(
                            Brush.radialGradient(0f to Color.Black.copy(alpha = 0.62f), 0.55f to Color.Black.copy(alpha = 0.3f), 1f to Color.Transparent, center = c, radius = 128.dp.toPx()),
                            radius = 128.dp.toPx(),
                            center = c,
                        )
                    }
                },
            )
            Column(
                Modifier.align(Alignment.Center).offset(y = (-44).dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Txt(
                    "Aether",
                    MastheadStyle,
                    modifier = Modifier
                        .onGloballyPositioned { arrival.masthead = it.boundsInRoot() }
                        .graphicsLayer { alpha = arrival.mastheadAlpha },
                )
                Spacer(Modifier.height(10.dp))
                Txt(
                    "A private room for music",
                    Type.Label.copy(fontSize = 9.5.sp, letterSpacing = 0.34.em),
                    color = Ink.Grey,
                    upper = true,
                    modifier = Modifier.graphicsLayer { alpha = words.value },
                )
            }
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = Space.edge)
                    .padding(bottom = 20.dp)
                    .graphicsLayer {
                        alpha = words.value
                        translationY = (1f - words.value) * 24.dp.toPx()
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                WelcomeKey(
                    title = "YouTube Music",
                    line = if (session != null) "Your home, library and mixes" else "Optional: your home, library and mixes",
                    state = if (session != null) "Signed in" else "Sign in",
                    on = session != null,
                    onClick = { if (session == null) context.startActivity(SignInActivity.intent(context)) },
                )
                WelcomeKey(
                    title = "Timed lyrics",
                    line = "Words in time, from LRCLIB's open library",
                    state = if (lyrics) "On" else "Off",
                    on = lyrics,
                    onClick = { container.settings.setTimedLyrics(!lyrics) },
                )
                WelcomeKey(
                    title = "Offline",
                    line = "Your music, and songs kept on the phone",
                    state = if (offline) "Allowed" else "Allow",
                    on = offline,
                    onClick = { if (!offline) ask.launch(wanted.toTypedArray()) },
                )
                Txt(
                    "No tracking. Change any of these in Settings.",
                    Type.Meta,
                    color = Ink.White.copy(alpha = 0.74f),
                    align = TextAlign.Center,
                    modifier = Modifier.padding(top = 6.dp, bottom = 6.dp),
                )
                GlassButton("Enter", onClick = onDone, modifier = Modifier.fillMaxWidth(), lit = true)
            }
        },
    )
}

/**
 * One of the welcome's keys: a capsule of the tab bar's glass with what it is, a line of why, and
 * its state at the right. When it's on, it carries the bar's lit drop and a mark of light.
 */
@Composable
private fun WelcomeKey(title: String, line: String, state: String, on: Boolean, onClick: () -> Unit) {
    val liquid = rememberLiquidState()
    val haptics = LocalHaptics.current
    val reduce = LocalReduceMotion.current
    val glow by animateFloatAsState(if (on) 1f else 0f, Motion.snappy(), label = "on")
    Row(
        Modifier
            .fillMaxWidth()
            .height(62.dp)
            .semantics(mergeDescendants = true) { role = Role.Button; selected = on; stateDescription = state }
            .liquidGlass(Corners.Pill, GlassWeight.Key, liquid)
            .litDrop(Corners.Pill) { glow * 0.7f }
            .liquidTouch(liquid, reduce) { haptics.light(); onClick() }
            .padding(start = 24.dp, end = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Txt(title, Type.TitleSmall, maxLines = 1)
            Txt(line, Type.Meta, color = Ink.Grey, maxLines = 1)
        }
        Spacer(Modifier.width(12.dp))
        if (on) {
            Icon(Glyph.Check, size = 13.dp)
            Spacer(Modifier.width(6.dp))
        }
        Txt(state, Type.Label, color = if (on) Ink.White else Ink.Grey, upper = true, maxLines = 1)
    }
}

/**
 * Water at night (after the reference recording): a calm sea, slowed to half speed, graded to black
 * and white with the light kept as silver glints. A 16-second clip (Pexels, free licence) whose end
 * dissolves into its start, so it loops with no seam; muted, cropped to fill as a photograph is, and
 * faded in on its first frame so nothing else ever shows first. With reduced motion it's a still.
 * The room's darkness gathers at the corners over it.
 */
@Composable
private fun Water(modifier: Modifier) {
    val context = LocalContext.current
    val reduce = LocalReduceMotion.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val shown = remember { Animatable(0f) }
    var firstFrame by remember { mutableStateOf(false) }
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri("android.resource://${context.packageName}/${R.raw.welcome_water}"))
            repeatMode = Player.REPEAT_MODE_ONE
            volume = 0f
            prepare()
        }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() { firstFrame = true }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    DisposableEffect(lifecycle, reduce) {
        player.playWhenReady = !reduce && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> player.playWhenReady = !reduce
                Lifecycle.Event.ON_STOP -> player.playWhenReady = false
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(firstFrame) { if (firstFrame) shown.animateTo(1f, Motion.fluid(1.2f, 1f)) }
    BoxWithConstraints(modifier.background(Ink.Black).clipToBounds(), contentAlignment = Alignment.Center) {
        // Cropped to fill: the clip is 9:16, the screen taller, so it fills the height and loses its sides.
        val scale = max(maxWidth.value / ClipWidth, maxHeight.value / ClipHeight)
        AndroidView(
            factory = { c -> TextureView(c).also { player.setVideoTextureView(it) } },
            modifier = Modifier
                .requiredSize((ClipWidth * scale).dp, (ClipHeight * scale).dp)
                .graphicsLayer { alpha = shown.value },
        )
        // The water held well back: dimmed all over, and darker still toward the corners, so the
        // wordmark and the keys stand forward of it.
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.36f)))
        Box(
            Modifier.fillMaxSize().drawBehind {
                drawRect(
                    Brush.radialGradient(
                        0f to Color.Transparent,
                        0.5f to Color.Black.copy(alpha = 0.26f),
                        1f to Color.Black.copy(alpha = 0.88f),
                        center = Offset(size.width * 0.5f, size.height * 0.44f),
                        radius = size.maxDimension * 0.7f,
                    ),
                )
            },
        )
    }
}

private const val ClipWidth = 720f
private const val ClipHeight = 1280f
