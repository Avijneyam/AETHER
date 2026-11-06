package app.aether.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aether.Container
import app.aether.core.designsystem.art.Pictures
import app.aether.core.designsystem.component.DragSheet
import app.aether.core.designsystem.component.DragState
import app.aether.core.designsystem.component.FolderPose
import app.aether.core.designsystem.glass.BackdropVeil
import app.aether.core.designsystem.component.Glyph
import app.aether.core.designsystem.component.Icon
import app.aether.core.designsystem.component.LightCross
import app.aether.core.designsystem.component.LocalHaptics
import app.aether.core.designsystem.component.Txt
import app.aether.core.designsystem.component.dragsOpen
import app.aether.core.designsystem.component.rememberDragState
import app.aether.core.designsystem.glass.Edge
import app.aether.core.designsystem.glass.EdgeBlur
import app.aether.core.designsystem.glass.GlassHost
import app.aether.core.designsystem.glass.GlassWeight
import app.aether.core.designsystem.glass.LocalReduceMotion
import app.aether.core.designsystem.glass.liquidGlass
import app.aether.core.designsystem.glass.liquidTouch
import app.aether.core.designsystem.glass.rememberLiquidState
import app.aether.core.designsystem.glass.translucent
import app.aether.core.designsystem.theme.Corners
import app.aether.core.designsystem.theme.Ink
import app.aether.core.designsystem.theme.Motion
import app.aether.core.designsystem.theme.Space
import app.aether.core.designsystem.theme.Type
import app.aether.core.model.Album
import app.aether.core.model.Artist
import app.aether.core.model.Destination
import app.aether.core.model.Link
import app.aether.core.model.MediaEntity
import app.aether.core.model.Outcome
import app.aether.core.model.PlaybackState
import app.aether.core.model.Playlist
import app.aether.core.model.Podcast
import app.aether.core.model.Track
import app.aether.ui.common.LocalContainer
import app.aether.ui.menu.TrackMenu
import app.aether.ui.player.MiniPlayer
import app.aether.ui.player.NowPlaying
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import androidx.compose.ui.graphics.TransformOrigin
import app.aether.core.designsystem.component.GlassCircle
import app.aether.core.designsystem.component.Frost
import app.aether.core.model.Artwork
import android.os.Build
import androidx.compose.ui.unit.DpOffset
import app.aether.core.designsystem.glass.GlassQuality
import app.aether.core.designsystem.glass.LocalGlassBackdrop
import app.aether.core.designsystem.glass.LocalGlassQuality
import app.aether.core.designsystem.glass.backdrop.backdrops.LayerBackdrop
import app.aether.core.designsystem.glass.backdrop.backdrops.layerBackdrop
import app.aether.core.designsystem.glass.backdrop.backdrops.rememberCombinedBackdrop
import app.aether.core.designsystem.glass.backdrop.backdrops.rememberLayerBackdrop
import app.aether.core.designsystem.glass.backdrop.drawBackdrop
import app.aether.core.designsystem.glass.backdrop.effects.lens
import app.aether.core.designsystem.glass.backdrop.highlight.Highlight
import app.aether.core.designsystem.glass.backdrop.shadow.Shadow
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.aether.core.designsystem.glass.backdrop.Backdrop
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.offset

/** What any screen can ask of the app: go somewhere, play something, act on a track. */
@Stable
class Actions(
    private val container: Container,
    val nav: Navigator,
    val player: DragState,
    private val menu: DragState,
    val flight: FolderFlight,
    val openMenu: (Track) -> Unit,
) {
    val playback = container.playback

    /** What the opened folder showed, so its page can stand it open at once, before anything loads. */
    var preview by mutableStateOf<FolderPreview?>(null)

    /**
     * Opens a folder's page: the folder flies there from [from] on [fromPage], and stands open while
     * the list loads. With nowhere to fly from, the page simply slides in holding the folder.
     */
    fun openFolder(preview: FolderPreview, route: Route, pose: FolderPose, from: LayoutCoordinates?, fromPage: PageEntry?) {
        this.preview = preview
        go(route)
        if (from != null) flight.depart(preview, pose, from, fromPage?.frame, nav.top)
    }

    fun openNowPlaying() = player.show()

    /**
     * Goes to [route] from anywhere. Asked from Now Playing or a menu, they step aside first: the
     * player folds back into the mini player while the page slides in.
     */
    fun go(route: Route) {
        if (menu.visible) menu.hide()
        if (player.visible) player.hide()
        nav.open(route)
    }

    /** Plays [tracks] from [index]. A single track brings its radio with it, as YouTube Music does. */
    fun play(tracks: List<Track>, index: Int = 0) {
        if (tracks.isEmpty()) return
        container.scope.launch { container.library.remember(tracks) }
        playback.play(tracks, index)
        if (tracks.size == 1) extendWithRadio(tracks[0])
    }

    /**
     * Plays one song and what the catalogue says is like it, whatever list it was chosen from: a
     * song found by searching is followed by its radio, not by other songs with the same name.
     */
    fun playRadio(track: Track) = play(listOf(track.copy(context = null)))

    fun shuffle(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        play(tracks.shuffled(), 0)
    }

    fun extendWithRadio(track: Track) {
        container.scope.launch {
            val source = container.sources.of(track.id) ?: return@launch
            val next = source.upNext(track)
            if (next is Outcome.Ok) playback.extend(next.value.tracks.filter { it.id != track.id })
        }
    }

    /** Opens what an item is: a track plays, everything else is a page. */
    fun open(entity: MediaEntity) {
        when (entity) {
            is Track -> play(listOf(entity))
            is Album -> go(Route.Album(entity.id))
            is Artist -> go(Route.Artist(entity.id))
            is Playlist -> go(Route.Playlist(entity.id))
            is Podcast -> go(Route.Browse(Destination(entity.id.source, entity.id.key), entity.title))
            is Link -> when (entity.destination.key) {
                "FEmusic_charts" -> go(Route.Charts)
                "FEmusic_moods_and_genres" -> go(Route.Moods)
                else -> go(Route.Browse(entity.destination, entity.title))
            }
        }
    }
}

val LocalActions = staticCompositionLocalOf<Actions> { error("No actions") }

/** The rooms, their glass, and Now Playing over them. */
@Composable
fun Shell() {
    val container = LocalContainer.current
    val arrival = LocalArrival.current
    val nav = remember { Navigator() }
    val player = rememberDragState()
    val menu = rememberDragState()
    var menuFor by remember { mutableStateOf<Track?>(null) }
    // A menu asked for from Now Playing opens in Now Playing, over the cover it's about, and its
    // glass refracts that cover; asked for anywhere else, it opens over the room.
    var menuInPlayer by remember { mutableStateOf(false) }
    val flightScope = rememberCoroutineScope()
    val actions = remember { Actions(container, nav, player, menu, FolderFlight(flightScope)) { t -> menuFor = t; menuInPlayer = player.value > 0.5f; menu.show() } }
    val playback by container.playback.state.collectAsStateWithLifecycle()
    val reduce = LocalReduceMotion.current
    val density = LocalDensity.current
    val dock = rememberDock(reduce)

    AutoQueue(actions)
    LaunchedEffect(playback.hasMedia) { if (!playback.hasMedia) player.hide() }
    // Every new page starts with the dock open.
    LaunchedEffect(nav.current) { dock.unfold() }

    BackHandler(enabled = menu.visible || player.visible || nav.canGoBack) {
        when {
            menu.visible -> menu.hide()
            player.visible -> player.hide()
            else -> nav.back()
        }
    }

    CompositionLocalProvider(LocalActions provides actions) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val screen = with(density) { maxHeight.toPx() }
            val screenWidth = with(density) { maxWidth.toPx() }
            SideEffect { player.travelPx = screen }
            // Under a Now Playing that covers the whole screen, the room isn't drawn at all (Android
            // draws what's hidden unless told not to): its pages and its glass cost nothing while the
            // player is up, and are all still there the moment it's pulled down.
            val covered = { player.value >= 0.999f && !player.holding }
            // Read while composing, so it changes only when Now Playing settles fully open or leaves.
            val hidden by remember { derivedStateOf { covered() } }
            // Rising over the room, Now Playing soon has everything out of sight: the page behind the blur,
            // and the frost and the dock behind the blur or behind Now Playing itself. Android draws what's
            // hidden unless told not to, so they aren't placed.
            val veiled = { Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && player.value > 0.13f }
            val frostUnderVeil by remember { derivedStateOf { veiled() } }
            val dockUnderPlayer by remember { derivedStateOf { player.value > 0.24f } }
            val menuSheet: @Composable () -> Unit = {
                DragSheet(menu, onClosed = { menuFor = null; menuInPlayer = false }) {
                    menuFor?.let { track -> TrackMenu(track, close = { menu.hide() }) }
                }
            }
            GlassHost(
                Modifier.fillMaxSize(),
                // The whole plane, its black ground included, isn't drawn under a Now Playing that covers it,
                // or under the blur of it, which is all that can be seen of it once that's fully in.
                hideContent = { covered() || veiled() },
                content = {
                    // What moves by itself on a page rests while anything is over it: the blur under
                    // Now Playing and the frost under a sheet are made from a page that holds still.
                    Pages(nav, actions.flight, dock.watcher, paused = { player.visible || menu.visible })
                },
                overlay = {
                    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
                    // Under a Now Playing that's fully open, the rooms' own glass isn't placed, so it isn't
                    // drawn (Android keeps rendering hidden glass's blurs and lenses, invisible or not).
                    // It stays made: pulling the player down finds it all there, with nothing to build
                    // under the finger.
                    Box(Modifier.fillMaxSize().placedWhile { !hidden }) {
                        Box(Modifier.fillMaxSize().placedWhile { !frostUnderVeil }) {
                            EdgeBlur(Edge.Top, top + 64.dp)
                            DockFrost(dock, playback.hasMedia)
                            // Above the frost, so it stays sharp; it rides with Home's page all the same.
                            DayCorner(nav, arrival, top, screenWidth)
                        }
                        // The page goes out of focus as Now Playing rises over it, and comes back into
                        // focus as it's pulled down. The keys and the dock stay sharp, above it. It runs on
                        // a little under Now Playing's edge, to fill its rounded shoulders.
                        if (player.visible) {
                            val shoulder = with(density) { ShoulderRadius.toPx() }
                            BackdropVeil(amount = { player.value }, coveredFrom = { (1f - player.value.coerceIn(0f, 1f)) * screen + shoulder })
                        }
                        Corner(nav, player)
                        SettingsKey(nav, screenWidth)
                        Box(Modifier.fillMaxSize().placedWhile { !dockUnderPlayer }) {
                            Dock(nav, playback, player, dock)
                        }
                    }
                    if (player.visible) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    val p = player.value.coerceIn(0f, 1f)
                                    translationY = (1f - p) * screen
                                    // A sheet with rounded shoulders while it travels, square once it fills the
                                    // screen. All four corners are round, the lower two off the screen: one plain
                                    // rounded rectangle is the cheap clip, and this moves on every frame.
                                    if (p < 0.999f) {
                                        clip = true
                                        shape = RoundedCornerShape(with(density) { (ShoulderRadius * ((1f - p) * 14f).coerceAtMost(1f)).toPx() })
                                    }
                                }
                                .dragsOpen(player),
                        ) {
                            NowPlaying(player = player, onClose = { player.hide() }, menu = if (menuInPlayer) menuSheet else null)
                        }
                    }
                    if (!menuInPlayer || !player.visible) menuSheet()
                },
            )
        }
    }
}

/**
 * The top corner: on a page deeper than a room's first, the way back, a round key of glass. (The
 * day's capsule belongs to Home's page now, and scrolls away with it.) As Now Playing rises the
 * key's arrow turns to point down; Now Playing's own close key, pointing down, takes its place.
 */
@Composable
private fun BoxScope.Corner(nav: Navigator, player: DragState) {
    val liquid = rememberLiquidState()
    val haptics = LocalHaptics.current
    val reduce = LocalReduceMotion.current
    // Kept made, and placed only while it's there: the key isn't built again each time a page opens.
    val shown by animateFloatAsState(if (nav.depth > 1) 1f else 0f, KeyArrives, label = "back")
    val there by remember { derivedStateOf { shown > 0.004f } }
    Box(
        Modifier
            .align(Alignment.TopStart)
            .statusBarsPadding()
            .padding(start = Space.edge, top = 6.dp)
            .placedWhile { there }
            .graphicsLayer {
                val k = 0.6f + 0.4f * shown
                scaleX = k
                scaleY = k
                alpha = shown.coerceIn(0f, 1f)
                transformOrigin = TransformOrigin(0f, 0.5f)
            }
            .size(44.dp)
            .semantics { role = Role.Button; contentDescription = "Back" }
            .liquidGlass(Corners.Pill, GlassWeight.Key, liquid)
            .liquidTouch(liquid, reduce) { haptics.light(); if (player.visible) player.hide() else nav.back() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Glyph.Back, Modifier.graphicsLayer { rotationZ = -90f * player.value.coerceIn(0f, 1f) }, size = 18.dp)
    }
}

/** How round Now Playing's top corners are while it travels. */
private val ShoulderRadius = 34.dp

/** How a corner key comes and goes: on a spring with a little give. */
private val KeyArrives = Motion.fluid(0.34f, 0.82f, 0.002f)

/**
 * The day's capsule at the top of Home, drawn here, above the status bar's frost so it stays sharp.
 * It reads Home's own scroll ([Arrival.homeScroll]): it rides up exactly as the page does, fading
 * over its first 40 dp so it's gone before it reaches the clock, and it isn't there at all once the
 * masthead has left the page's first row. It shows on Home alone, and rides out with it when you
 * change rooms. The boot film lands on it.
 */
@Composable
private fun BoxScope.DayCorner(nav: Navigator, arrival: Arrival, statusTop: Dp, screenWidth: Float) {
    val density = LocalDensity.current
    val shown by animateFloatAsState(if (nav.depthOf(Tab.Listen) == 1) 1f else 0f, Motion.fluid(0.3f, 1f, 0.002f), label = "day")
    val there by remember { derivedStateOf { shown > 0.004f && nav.rail.showing(Tab.Listen) } }
    DayCapsule(
        Modifier
            .align(Alignment.TopStart)
            .placedWhile { there }
            .offset {
                val scrolled = arrival.homeScroll?.invoke() ?: 0
                IntOffset(Space.edge.roundToPx(), (statusTop + 6.dp).roundToPx() - scrolled)
            }
            .onGloballyPositioned { arrival.corner = it.boundsInRoot() }
            .graphicsLayer {
                val scrolled = arrival.homeScroll?.invoke()
                val travel = with(density) { 40.dp.toPx() }
                // The film lands on the capsule before Home has reported; then it's Home's scroll that decides.
                val fade = when {
                    arrival.homeScroll == null -> if (arrival.cornerAlpha < 1f) 1f else 0f
                    scrolled == null -> 0f
                    else -> (1f - scrolled / travel).coerceIn(0f, 1f)
                }
                alpha = arrival.cornerAlpha * fade * shown.coerceIn(0f, 1f)
                translationX = nav.rail.offset(Tab.Listen) * screenWidth
            },
    )
}

/** The day's capsule itself: a small capsule of grey glass with AETHER's cross of light and the day in small grey capitals. */
@Composable
internal fun DayCapsule(modifier: Modifier = Modifier, backdrop: Backdrop? = LocalGlassBackdrop.current) {
    val day = remember { dayLine(LocalDate.now()) }
    Row(
        modifier
            .height(32.dp)
            .semantics { contentDescription = "AETHER. $day" }
            .liquidGlass(Corners.Pill, GlassWeight.Key, tint = Ash, backdrop = backdrop)
            .padding(start = 5.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LightCross(size = 15.dp, intensity = 0.8f)
        Spacer(Modifier.width(4.dp))
        Txt(day, Type.Label.copy(fontSize = 9.sp, letterSpacing = 0.12.em), color = Ink.Grey, upper = true, maxLines = 1)
    }
}

/** The grey the corner capsule's glass is tinted. */
private val Ash = Color(0xFF3A3A3A)

/**
 * The way to Settings on Library's first page: a key of glass in the top corner, above the frost so
 * it stays sharp. It rides in and out with Library when you change rooms.
 */
@Composable
private fun BoxScope.SettingsKey(nav: Navigator, screenWidth: Float) {
    val shown by animateFloatAsState(if (nav.depthOf(Tab.Library) == 1) 1f else 0f, KeyArrives, label = "settings")
    val there by remember { derivedStateOf { shown > 0.004f && nav.rail.showing(Tab.Library) } }
    GlassCircle(
        Glyph.Gear,
        "Settings",
        { nav.open(Route.Settings) },
        modifier = Modifier
            .align(Alignment.TopEnd)
            .statusBarsPadding()
            .padding(end = Space.edge, top = 6.dp)
            .placedWhile { there }
            .graphicsLayer {
                val k = 0.6f + 0.4f * shown
                scaleX = k
                scaleY = k
                alpha = shown.coerceIn(0f, 1f)
                translationX = nav.rail.offset(Tab.Library) * screenWidth
            },
        live = true,
    )
}

/** "Nº 308 · Wed 4 Nov": the day of the year, as an issue of a magazine is numbered. */
private fun dayLine(today: LocalDate): String {
    val locale = Locale.getDefault()
    val weekday = today.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
    val month = today.month.getDisplayName(TextStyle.SHORT, locale)
    return "Nº ${today.dayOfYear}  ·  $weekday ${today.dayOfMonth} $month"
}

/** How much of the foot of the screen the dock takes with the mini player up, above the navigation bar. */
internal val DockHeight get() = Bar + Gap + Bar + Gap

private val Bar = 64.dp
private val Folded = 56.dp
private val Gap = 10.dp

/**
 * The dock folds away as you read down a page and comes back as you head up it, or when you touch
 * it (iOS 26's tab bar with Music's mini player): the rooms shrink into a circle in the corner and
 * the song slides down beside them. [fold] runs 0 open to 1 folded on one soft spring, so the two
 * move as one, like the Dynamic Island.
 */
@Stable
private class DockState(private val scope: CoroutineScope, private val reduce: Boolean, private val foldAfter: Float, private val unfoldAfter: Float) {
    val fold = Animatable(0f)
    var folded by mutableStateOf(false)
        private set
    private var travel = 0f

    fun set(value: Boolean) {
        if (folded == value) return
        folded = value
        travel = 0f
        scope.launch { fold.animateTo(if (value) 1f else 0f, if (reduce) Motion.fluid(0.3f, 1f) else Motion.fluid(0.5f, 0.78f)) }
    }

    fun unfold() = set(false)

    /** Reads the page's scrolling: a little reading down folds, a little more heading up unfolds. */
    val watcher = object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            val dy = consumed.y
            if (dy < 0f) {
                travel = min(0f, travel) + dy
                if (travel < -foldAfter) set(true)
            } else if (dy > 0f) {
                travel = max(0f, travel) + dy
                if (travel > unfoldAfter) set(false)
            }
            // Pulled at the very top: there's nowhere further up, so the dock comes back.
            if (available.y > 0f && source == NestedScrollSource.UserInput) set(false)
            return Offset.Zero
        }
    }
}

@Composable
private fun rememberDock(reduce: Boolean): DockState {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    return remember(reduce) { with(density) { DockState(scope, reduce, foldAfter = 28.dp.toPx(), unfoldAfter = 56.dp.toPx()) } }
}

/** Where something sits in the dock: [x] from its left, [lift] above its foot, and its size. */
private class Frame(val x: Dp, val lift: Dp, val w: Dp, val h: Dp)

/**
 * Sizes and places this between its [open] and [folded] frames as [fold] goes 0 to 1. It fills the
 * dock and puts what follows it at the frame, all at layout, so folding never recomposes a thing.
 */
private fun Modifier.between(fold: () -> Float, open: Frame, folded: Frame): Modifier = layout { measurable, constraints ->
    val f = fold()
    val w = lerp(open.w, folded.w, f).roundToPx().coerceAtLeast(0)
    val h = lerp(open.h, folded.h, f).roundToPx().coerceAtLeast(0)
    val placeable = measurable.measure(Constraints.fixed(w, h))
    val x = lerp(open.x, folded.x, f).roundToPx()
    val y = constraints.maxHeight - h - lerp(open.lift, folded.lift, f).roundToPx()
    layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(x, y) }
}

/**
 * The mini player over the rooms, at the foot of the screen. When a song starts it rises out of the
 * bar (from behind it, growing as it comes, on the Dynamic Island's spring); with the dock folded it
 * slides out from beside the circle. When the music stops it sinks back in.
 */
@Composable
private fun BoxScope.Dock(nav: Navigator, playback: PlaybackState, player: DragState, dock: DockState) {
    val reduce = LocalReduceMotion.current
    val emerge = remember { Animatable(if (playback.hasMedia) 1f else 0f) }
    LaunchedEffect(playback.hasMedia) {
        val to = if (playback.hasMedia) 1f else 0f
        emerge.animateTo(to, if (reduce) Motion.fluid(0.3f, 1f) else if (to == 1f) Motion.bouncy() else Motion.fluid(0.36f, 1f))
    }
    BoxWithConstraints(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = Space.edge)
            .padding(bottom = Gap)
            .height(Bar + Gap + Bar),
    ) {
        val full = maxWidth
        // Drawn first, so while it rises it comes from behind the bar.
        if (playback.current != null && (playback.hasMedia || emerge.value > 0.001f)) {
            MiniPlayer(
                playback,
                onOpen = { player.show() },
                modifier = Modifier
                    .between({ dock.fold.value }, Frame(0.dp, Bar + Gap, full, Bar), Frame(Folded + Gap, 0.dp, full - Folded - Gap, Folded))
                    .graphicsLayer {
                        val e = emerge.value
                        val folded = dock.fold.value > 0.5f
                        val rise = (Bar + Gap).toPx()
                        val beside = (Folded + Gap).toPx()
                        transformOrigin = if (folded) TransformOrigin(0f, 0.5f) else TransformOrigin(0.5f, 1f)
                        translationY = if (folded) 0f else (1f - e) * rise
                        translationX = if (folded) -(1f - e) * beside else 0f
                        scaleX = if (folded) 0.4f + 0.6f * e else 0.86f + 0.14f * e
                        scaleY = if (folded) 0.8f + 0.2f * e else 0.5f + 0.5f * e
                        alpha = (e * 1.8f).coerceIn(0f, 1f) * (1f - player.value.coerceIn(0f, 1f))
                    }
                    .dragsOpen(player),
            )
        }
        TabBar(nav, dock, full)
    }
}

/** The frost under the dock: as tall as the dock needs, and lower once it has folded. */
@Composable
private fun BoxScope.DockFrost(dock: DockState, hasMedia: Boolean) {
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val open = if (hasMedia) Bar + Gap + Bar else Bar
    EdgeBlur(Edge.Bottom, bottom + Gap + lerp(open, Folded, dock.fold.value.coerceIn(0f, 1f)) + 52.dp)
}

private fun Tab.glyph(): Glyph = when (this) {
    Tab.Listen -> Glyph.Listen
    Tab.Discover -> Glyph.Discover
    Tab.Library -> Glyph.Library
}

/**
 * The three rooms in one capsule of liquid glass. Folded, it's a circle holding the room you're in;
 * touching the circle unfolds the dock.
 */
@Composable
private fun TabBar(nav: Navigator, dock: DockState, full: Dp) {
    val liquid = rememberLiquidState()
    val reduce = LocalReduceMotion.current
    // The bar's own glass, handed to the selection lens to refract.
    val barGlass = rememberLayerBackdrop()
    Box(
        Modifier
            .between({ dock.fold.value }, Frame(0.dp, 0.dp, full, Bar), Frame(0.dp, 0.dp, Folded, Folded))
            .then(if (dock.folded) Modifier.semantics { role = Role.Button; contentDescription = "${nav.tab.label}. Show the rooms" } else Modifier)
            .liquidGlass(Corners.Pill, GlassWeight.Control, liquid, export = barGlass)
            // Clipped only while folding, so the lifted lens can stand past the bar's edges.
            .graphicsLayer {
                clip = dock.fold.value > 0.001f
                shape = Corners.Pill
            }
            .liquidTouch(liquid, reduce) { if (dock.folded) dock.unfold() },
    ) {
        // Open: the rooms, laid out at full width and fading as the glass folds in around them.
        Rooms(
            nav,
            barGlass = barGlass,
            enabled = !dock.folded,
            modifier = Modifier
                .wrapContentWidth(Alignment.Start, unbounded = true)
                .width(full)
                .height(Bar)
                .align(Alignment.CenterStart)
                .graphicsLayer { alpha = (1f - dock.fold.value * 2.2f).coerceIn(0f, 1f) },
        )
        // Folded: the room you're in, as one mark.
        Icon(
            nav.tab.glyph(),
            Modifier.align(Alignment.Center).graphicsLayer { alpha = ((dock.fold.value - 0.45f) / 0.55f).coerceIn(0f, 1f) },
            size = 21.dp,
        )
    }
}

/**
 * The rooms on the bar, and the lit capsule marking the one you're in: a drop of light you can
 * hold. Press and drag it along the bar and it lifts (with liquid glass, into a clear lens standing
 * taller than the bar, bending the bar's glass and the rooms beneath it, as iOS 26's does; after
 * BitChord's), leans with your speed and brightens the rooms it passes, ticking as it crosses each;
 * let go and it snaps, with a little give, to the nearest (a flick carries it on), sheds its glass
 * and lies flat again. A tap just chooses.
 */
@Composable
private fun Rooms(nav: Navigator, barGlass: LayerBackdrop, enabled: Boolean, modifier: Modifier) {
    val haptics = LocalHaptics.current
    val reduce = LocalReduceMotion.current
    val liquidGlassOn = LocalGlassQuality.current == GlassQuality.Liquid
    val page = LocalGlassBackdrop.current
    val scope = rememberCoroutineScope()
    val tabs = Tab.entries
    // The row of rooms, recorded for the lens to refract.
    val row = rememberLayerBackdrop()
    BoxWithConstraints(modifier.padding(5.dp)) {
        val slot = constraints.maxWidth / tabs.size.toFloat()
        val slotDp = maxWidth / tabs.size
        val x = remember { Animatable(nav.tab.ordinal * slot) }
        val lift = remember { Animatable(0f) }
        val lean = remember { Animatable(0f) }
        var holding by remember { mutableStateOf(false) }
        val snap = Motion.snappy<Float>()
        // Choices made elsewhere (back, "See all"): the capsule glides there.
        LaunchedEffect(nav.tab, slot) { if (!holding) x.animateTo(nav.tab.ordinal * slot, snap) }
        val at = if (slot > 0f) x.value / slot else 0f

        val lensOn = liquidGlassOn && page != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        // The flat capsule under the rooms; with the lens it gives way while the lens is up.
        Box(
            Modifier
                .width(slotDp)
                .fillMaxHeight()
                .graphicsLayer {
                    translationX = x.value
                    val l = lift.value
                    val s = lean.value
                    scaleX = 1f + 0.08f * l + abs(s)
                    scaleY = 1f + 0.12f * l - 0.5f * abs(s)
                    alpha = if (lensOn) 1f - l.coerceIn(0f, 1f) else 1f
                }
                .translucent(Corners.Pill, lit = true, strength = 1f + 0.6f * lift.value),
        )
        Row(Modifier.fillMaxSize().layerBackdrop(row)) {
            tabs.forEach { tab ->
                val near = (1f - abs(at - tab.ordinal)).coerceIn(0f, 1f)
                val color = lerp(Ink.Grey, Ink.White, near)
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .semantics {
                            role = Role.Tab
                            selected = nav.tab == tab
                            contentDescription = tab.label
                            onClick(tab.label) { nav.select(tab); true }
                        }
                        .graphicsLayer {
                            // Under the held drop, a room is magnified a little, as through a lens.
                            val k = 1f + 0.14f * lift.value * near
                            scaleX = k
                            scaleY = k
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(tab.glyph(), size = 19.dp, color = color)
                    Spacer(Modifier.height(3.dp))
                    Txt(tab.label, Type.Label.copy(fontSize = Type.Label.fontSize * 0.86f), color = color, upper = true)
                }
            }
        }
        if (lensOn && page != null) {
            val through = rememberCombinedBackdrop(page, barGlass, row)
            val grow = with(LocalDensity.current) { 9.dp.toPx() }
            val lensUp by remember { derivedStateOf { lift.value > 0.001f } }
            // The lens, over the rooms: there only while it's up. Android renders an invisible layer
            // all the same, and this one samples the page, so it was a whole lens on every frame of
            // anything, for nobody.
            Box(
                Modifier
                    .placedWhile { lensUp }
                    .width(slotDp)
                    .fillMaxHeight()
                    .drawBackdrop(
                        backdrop = through,
                        shape = { Corners.Pill },
                        effects = {
                            val l = lift.value.coerceIn(0f, 1f)
                            lens(12.dp.toPx() * l, 14.dp.toPx() * l, chromaticAberration = true)
                        },
                        highlight = { lift.value.coerceIn(0f, 1f).let { l -> if (l > 0.02f) Highlight.Default.copy(alpha = l) else null } },
                        shadow = { lift.value.coerceIn(0f, 1f).let { l -> if (l > 0.02f) LensShadow.copy(alpha = l) else null } },
                        layerBlock = {
                            val l = lift.value
                            val s = lean.value
                            translationX = x.value
                            // Taller than the bar while it's up, wider a little, leaning with the speed.
                            scaleX = 1f + 0.16f * l + abs(s)
                            scaleY = 1f + (grow * 2f / size.height.coerceAtLeast(1f)) * l - 0.5f * abs(s)
                            alpha = if (l > 0.02f) 1f else 0f
                        },
                    ),
            )
        }
        if (enabled) Box(
            Modifier.matchParentSize().pointerInput(slot) {
                val held = spring<Float>(dampingRatio = 0.6f, stiffness = 600f)
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val start = down.position.x
                    val tapped = (start / slot).toInt().coerceIn(0, tabs.lastIndex)
                    val onDrop = abs(start - (x.value + slot / 2f)) < slot / 2f
                    var grab = start - x.value
                    var dragging = false
                    var crossed = nav.tab.ordinal
                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)
                    if (onDrop) scope.launch { lift.animateTo(1f, held) }
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        tracker.addPosition(change.uptimeMillis, change.position)
                        if (!dragging && abs(change.position.x - start) > viewConfiguration.touchSlop) {
                            dragging = true
                            holding = true
                            // Dragged from elsewhere on the bar, the drop comes to the finger.
                            if (!onDrop) { grab = slot / 2f; scope.launch { lift.animateTo(1f, held) } }
                        }
                        if (dragging) {
                            change.consume()
                            val raw = change.position.x - grab
                            val end = slot * tabs.lastIndex
                            // Past either end it resists as iOS does, each pixel moving it less.
                            val pos = when {
                                raw < 0f -> Motion.rubberBand(raw, slot * tabs.size)
                                raw > end -> end + Motion.rubberBand(raw - end, slot * tabs.size)
                                else -> raw
                            }
                            scope.launch { x.snapTo(pos) }
                            if (!reduce) {
                                val v = tracker.calculateVelocity().x
                                scope.launch { lean.animateTo((v / 5200f).coerceIn(-0.2f, 0.2f), spring(dampingRatio = 0.8f, stiffness = 900f)) }
                            }
                            val nearest = ((pos + slot / 2f) / slot).toInt().coerceIn(0, tabs.lastIndex)
                            if (nearest != crossed) { crossed = nearest; haptics.tick() }
                        }
                    }
                    holding = false
                    scope.launch { lift.animateTo(0f, spring(dampingRatio = 0.55f, stiffness = 420f)) }
                    scope.launch { lean.animateTo(0f, spring(dampingRatio = 0.5f, stiffness = 380f)) }
                    val v = if (dragging) tracker.calculateVelocity().x else 0f
                    // It lands on the room nearest where the flick would carry it, keeping the finger's speed.
                    val target = if (dragging) ((Motion.project(x.value, v) + slot / 2f) / slot).toInt().coerceIn(0, tabs.lastIndex) else tapped
                    scope.launch { x.animateTo(target * slot, snap, initialVelocity = v) }
                    if (!dragging || tabs[target] != nav.tab) {
                        haptics.select()
                        nav.select(tabs[target])
                    }
                }
            },
        )
    }
}

/** The lens's contact shadow, shed as it lands. */
private val LensShadow = Shadow(radius = 12.dp, offset = DpOffset(0.dp, 2.dp), color = Color.Black.copy(alpha = 0.3f))

/**
 * Autoplay: when the queue is about to run out, what follows its last track is added, as YouTube
 * Music does, so music never stops by surprise. Once per last track.
 */
@Composable
private fun AutoQueue(actions: Actions) {
    val container = LocalContainer.current
    val state by container.playback.state.collectAsStateWithLifecycle()
    var extendedFor by remember { mutableStateOf<String?>(null) }
    val last = state.queue.lastOrNull()
    val nearEnd = state.hasMedia && state.index >= state.queue.size - 2
    LaunchedEffect(nearEnd, last?.id) {
        if (!nearEnd || last == null || extendedFor == last.id.value) return@LaunchedEffect
        extendedFor = last.id.value
        actions.extendWithRadio(last)
    }
    // The prints either side of the song on are kept ready, so going on or back has its picture in
    // hand the moment it's asked for, and Now Playing opens on its own at once.
    val context = LocalContext.current
    val around = listOfNotNull(state.current, state.queue.getOrNull(state.index + 1), state.queue.getOrNull(state.index - 1))
    LaunchedEffect(around.map { it.id }) {
        around.forEach { Pictures.prefetch(context, it.artwork.url(PrintPx), PrintPx) }
    }
}

/** How large Now Playing's print is asked for. */
const val PrintPx = 1080
