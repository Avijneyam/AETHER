package app.aether.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import app.aether.core.designsystem.component.FolderCard
import app.aether.core.designsystem.component.FolderFile
import app.aether.core.designsystem.component.FolderPose
import app.aether.core.designsystem.glass.LocalReduceMotion
import app.aether.core.designsystem.theme.Ink
import app.aether.core.designsystem.theme.FluidSpring
import app.aether.core.designsystem.theme.Glide
import app.aether.core.model.Artwork
import app.aether.ui.detail.AlbumScreen
import app.aether.ui.detail.ArtistScreen
import app.aether.ui.detail.BrowseScreen
import app.aether.ui.detail.ChartsScreen
import app.aether.ui.detail.FolderHeadWidth
import app.aether.ui.detail.MoodsScreen
import app.aether.ui.detail.PlaylistScreen
import app.aether.ui.detail.SleeveActions
import app.aether.ui.detail.SleeveHead
import app.aether.ui.home.HomeScreen
import app.aether.ui.library.HistoryScreen
import app.aether.ui.library.LibraryScreen
import app.aether.ui.library.LikedScreen
import app.aether.ui.library.LocalPlaylistScreen
import app.aether.ui.search.SearchScreen
import app.aether.ui.settings.SettingsScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/*
 * The rooms and their pages. Every room stays made, and every page on a room's stack stays made
 * beneath the one on top: changing rooms or coming back is showing what's already there, never
 * building it again.
 *
 * What makes the movement light is what moves: a room or a page is one drawing, moved by a property
 * of its layer, with nothing in it composed or measured again. What's costly inside it (a print's
 * shader, a folder's outlines) is a small texture of its own, kept. What can't be seen isn't placed:
 * it stays made, its lists where they were, but its textures go back to Android, which otherwise held
 * nearly all of its graphics budget for rooms nobody could see.
 */

/** The page being composed: what it's measured from, and whether it has arrived. */
val LocalPage = staticCompositionLocalOf<PageEntry?> { null }

/**
 * Whether what moves by itself on this page (the mood ribbon, the drifting covers) should rest: it
 * isn't the page on show, or something is over it. A page moving where no one can see it had the
 * whole screen drawn again for every step, and a blur over it made again.
 */
val LocalRoomsHidden = staticCompositionLocalOf<() -> Boolean> { { false } }

/** Stays composed and measured, and is placed (so drawn, and touchable) only while [show] says so. */
fun Modifier.placedWhile(show: () -> Boolean): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    layout(placeable.width, placeable.height) { if (show()) placeable.place(0, 0) }
}

/** The rooms, each made once, with the room you're in on show and any folder in flight above it. */
@Composable
fun Pages(nav: Navigator, flight: FolderFlight, watcher: NestedScrollConnection, paused: () -> Boolean, modifier: Modifier = Modifier) {
    val holder = rememberSaveableStateHolder()
    val arrival = LocalArrival.current
    val reduce = LocalReduceMotion.current
    var made by remember { mutableStateOf(setOf(nav.tab)) }
    var warmed by remember { mutableStateOf(false) }
    val lastScroll = remember { longArrayOf(0L) }
    SideEffect { if (nav.tab !in made) made = made + nav.tab }
    LaunchedEffect(nav.tab) { nav.rail.go(nav.tab, animate = !reduce) }
    // The other rooms are made shortly after the first has landed, one at a time and never under a
    // moving finger, so the first time you go to one it's simply there.
    LaunchedEffect(Unit) {
        snapshotFlow { arrival.cornerAlpha >= 1f }.first { it }
        for (tab in Tab.entries) {
            if (tab in made) continue
            delay(RoomGapMs)
            while (SystemClock.uptimeMillis() - lastScroll[0] < StillMs) delay(200)
            made = made + tab
        }
        delay(RoomGapMs)
        while (SystemClock.uptimeMillis() - lastScroll[0] < StillMs) delay(200)
        nav.keepsMade = true
    }
    Box(modifier.fillMaxSize()) {
        Tab.entries.forEach { tab ->
            if (tab in made || tab == nav.tab) key(tab) {
                Room(tab, nav, holder, flight, watcher, paused) { lastScroll[0] = SystemClock.uptimeMillis() }
            }
        }
        flight.trip?.let { FlyingFolder(it, flight) }
        // A folder's page made once, out of sight, so its code is loaded before the first folder is
        // opened (that first opening is what stumbled).
        if (!warmed && made.size == Tab.entries.size) {
            Box(Modifier.placedWhile { false }) {
                SleeveHead("", null, Artwork.None, emptyList()) { SleeveActions(emptyList(), saved = null, onSave = null) }
                FolderCard("", null, Artwork.None, emptyList(), onOpen = {}, width = FolderHeadWidth, opened = true, flying = { 0f })
            }
            LaunchedEffect(Unit) {
                withFrameNanos { }
                withFrameNanos { }
                warmed = true
            }
        }
    }
}

/** How long to wait before making the next room, and how long the page must have been still. */
private const val RoomGapMs = 900L
private const val StillMs = 500L

@Composable
private fun Room(
    tab: Tab,
    nav: Navigator,
    holder: SaveableStateHolder,
    flight: FolderFlight,
    watcher: NestedScrollConnection,
    paused: () -> Boolean,
    onScrolled: () -> Unit,
) {
    val rail = nav.rail
    val onShow = nav.tab == tab
    val showing by remember(tab) { derivedStateOf { rail.showing(tab) } }
    // A room left mid-fling coasts to rest out of sight; it doesn't fold the dock of the one you're in.
    val connection = remember(tab, watcher) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                onScrolled()
                return if (nav.tab == tab) watcher.onPostScroll(consumed, available, source) else Offset.Zero
            }
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .placedWhile { showing }
            .graphicsLayer {
                // Along the rail, in screens. Clipped to itself: a page waiting beside it, off its left
                // edge, mustn't be touchable through another room.
                val offset = rail.offset(tab)
                translationX = offset * size.width
                clip = true
            }
            .then(if (onShow) Modifier else Modifier.clearAndSetSemantics { })
            .nestedScroll(connection),
    ) {
        val pages = nav.pagesOf(tab)
        pages.forEachIndexed { i, page ->
            key(page.id) { Page(page, pages.getOrNull(i + 1), tab, nav, holder, flight, paused) }
        }
    }
}

/**
 * A page: opaque, so one sliding over another covers it, with a soft shadow along its leading edge.
 * Pages move as they do on iOS: one you open slides in from the right over the one you were on,
 * which drifts left and dims; going back slides it away and the one beneath returns. On a spring,
 * so a page caught mid-way by a second touch carries on from where it is, at the speed it had.
 * Beneath a page at rest, it waits off to the left, a picture, ready for the way back.
 */
@Composable
private fun Page(
    page: PageEntry,
    above: PageEntry?,
    tab: Tab,
    nav: Navigator,
    holder: SaveableStateHolder,
    flight: FolderFlight,
    paused: () -> Boolean,
) {
    val reduce = LocalReduceMotion.current
    val root = page.route == tab.root
    val spring = if (reduce) PageFade else PageSlide
    LaunchedEffect(page, page.phase) {
        when (page.phase) {
            PagePhase.Kept -> page.slide.snap(1f)
            PagePhase.Open -> page.slide.to(0f, spring)
            PagePhase.Leaving -> {
                if (!reduce) flight.returning(page)
                page.slide.to(1f, spring)
                nav.finished(page)
            }
        }
    }
    DisposableEffect(page) { onDispose { if (!root) holder.removeState(page.id) } }
    val hidden = remember(page, tab) { { paused() || nav.tab != tab || nav.topOf(tab) !== page } }
    val waitingNow by remember(page, above) { derivedStateOf { page.phase == PagePhase.Kept || above?.landed == true } }
    // The shadow along its leading edge, drawn beside the page rather than in it, so that the page's
    // picture holds only the page. There only while it's on its way.
    if (!root) Spacer(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                val away = page.slide.value
                translationX = away * size.width
                alpha = if (!reduce && away > 0.001f && away < 0.999f) 1f else 0f
            }
            .drawBehind {
                val w = 26.dp.toPx()
                drawRect(
                    Brush.horizontalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)), startX = -w, endX = 0f),
                    topLeft = Offset(-w, 0f),
                    size = Size(w, size.height),
                )
            },
    )
    Box(
        Modifier
            .fillMaxSize()
            .placedWhile { !waitingNow }
            .graphicsLayer {
                val over = above?.let { 1f - it.slide.value } ?: 0f
                if (reduce) alpha = 1f - page.slide.value
                else translationX = (page.slide.value - over / 3f) * size.width
            }
            // Inside the layer: what's measured from here is where things will be once it has arrived.
            .onPlaced { page.frame = it }
            .then(if (above == null && page.phase != PagePhase.Kept) Modifier else Modifier.clearAndSetSemantics { })
            .background(Ink.Black),
    ) {
        CompositionLocalProvider(LocalPage provides page, LocalRoomsHidden provides hidden) {
            holder.SaveableStateProvider(page.id) { Screen(page.route) }
        }
    }
    // Dimmed as it's covered, by a sheet of black over it, not by fading all of it: one rectangle,
    // with no copy of the page to fade.
    if (above != null && !reduce) Spacer(
        Modifier
            .fillMaxSize()
            .placedWhile { !waitingNow }
            .graphicsLayer {
                val over = 1f - above.slide.value
                translationX = (page.slide.value - over / 3f) * size.width
                alpha = 0.6f * over
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }
            .background(Color.Black),
    )
}

private val PageSlide = FluidSpring(0.4f, 1f)
private val PageFade = FluidSpring(0.3f, 1f)

@Composable
private fun Screen(route: Route) {
    when (route) {
        Route.Home -> HomeScreen()
        Route.Search -> SearchScreen()
        Route.Library -> LibraryScreen()
        is Route.Album -> AlbumScreen(route.id)
        is Route.Artist -> ArtistScreen(route.id)
        is Route.Playlist -> PlaylistScreen(route.id)
        is Route.LocalPlaylist -> LocalPlaylistScreen(route.id)
        Route.Liked -> LikedScreen()
        Route.History -> HistoryScreen()
        is Route.Browse -> BrowseScreen(route.destination, route.title)
        Route.Charts -> ChartsScreen()
        Route.Moods -> MoodsScreen()
        Route.Settings -> SettingsScreen()
    }
}

/**
 * Whether the page has slid into its place. A page holds back its long lists until then, so that the
 * slide, and most of a folder's flight, run alone and smooth. (Not until the flight has landed too:
 * the list and the folder's head arriving on one frame was a frame of a quarter of a second.)
 */
@Composable
fun pageLanded(): Boolean {
    val page = LocalPage.current ?: return true
    return page.landed
}

/** A folder as it was touched: enough to draw it open on its page before the page has loaded. */
data class FolderPreview(val key: String, val title: String, val detail: String?, val artwork: Artwork, val files: List<FolderFile>)

/** A folder's journey to the page it opens: it's the same folder, and it knows the way back. */
@Stable
class FolderTrip internal constructor(
    val preview: FolderPreview,
    internal val pose: FolderPose,
    internal val from: LayoutCoordinates,
    internal val fromFrame: LayoutCoordinates?,
    internal val page: PageEntry,
) {
    /** Where the folder lies on the page it left, as that page stands at rest. */
    internal var fromRect: Rect = measure() ?: Rect.Zero
        private set
    private var lastTo: Rect? = null

    private fun measure(): Rect? = runCatching {
        if (!from.isAttached) return@runCatching null
        val frame = fromFrame?.takeIf { it.isAttached }
        frame?.localBoundingBoxOf(from, clipBounds = false) ?: from.boundsInRoot()
    }.getOrNull()

    internal fun refreshFrom(): Boolean {
        fromRect = measure() ?: return false
        return true
    }

    /** Where it stands at the head of its page, as that page stands at rest. */
    internal fun toRect(): Rect? {
        val head = page.head
        val frame = page.frame
        if (head != null && frame != null && head.isAttached && frame.isAttached) {
            runCatching { frame.localBoundingBoxOf(head, clipBounds = false) }.getOrNull()?.let { lastTo = it }
        }
        return lastTo
    }
}

/**
 * The folder in the air. The page behind it is made first and the flight begins only once its head
 * has been laid out, so the very first opening flies as well as any other: it can't start without
 * knowing where it's going, however long the page took.
 */
@Stable
class FolderFlight(private val scope: CoroutineScope) {
    /** The trip under way, if any: its folder is drawn here, and hidden in both its places. */
    var trip by mutableStateOf<FolderTrip?>(null)
        private set

    /** 0 where it was touched, 1 at the head of its page; past 1 a little as it lands. */
    internal val progress = Glide(0f, rest = 0.0008f)
    internal val at: () -> Float = { progress.value }
    private var underWay = false

    internal fun depart(preview: FolderPreview, pose: FolderPose, from: LayoutCoordinates, fromFrame: LayoutCoordinates?, page: PageEntry) {
        val next = FolderTrip(preview, pose, from, fromFrame, page)
        if (next.fromRect == Rect.Zero) return
        page.trip = next
        trip = next
        underWay = false
        scope.launch {
            progress.snap(0f)
            // A page with no head to land on (the playlist is gone): the folder goes back to its place.
            delay(700)
            if (trip === next && !underWay) trip = null
        }
    }

    /** The head of [page] has been laid out. If a folder is waiting to fly there, it goes now. */
    internal fun headPlaced(page: PageEntry) {
        val t = trip ?: return
        if (t.page !== page || underWay) return
        underWay = true
        scope.launch {
            progress.to(1f, FlightSpring)
            if (trip === t) trip = null
        }
    }

    /** [page] is leaving: its folder flies back to where it was touched, if it can see the way. */
    internal fun returning(page: PageEntry) {
        val t = page.trip ?: return
        val to = t.toRect()
        if (!t.refreshFrom() || to == null || to.bottom <= 0f) {
            if (trip === t) trip = null
            return
        }
        val caught = trip === t
        trip = t
        underWay = true
        scope.launch {
            if (!caught) progress.snap(1f)
            progress.to(0f, FlightSpring)
            if (trip === t) trip = null
        }
    }

    private companion object {
        val FlightSpring = FluidSpring(0.5f, 0.86f)
    }
}

/**
 * The folder itself in the air: the large one, scaled to where it is. It leaves exactly as the small
 * one lay and lands exactly as the large one stands; the words and small things on it hold their
 * size on the way, as they're the same size in both places.
 */
@Composable
private fun FlyingFolder(trip: FolderTrip, flight: FolderFlight) {
    val density = LocalDensity.current
    val p = trip.preview
    Box(Modifier.fillMaxSize().wrapContentSize(Alignment.TopStart, unbounded = true)) {
        FolderCard(
            title = p.title,
            detail = p.detail,
            artwork = p.artwork,
            files = p.files,
            onOpen = {},
            width = FolderHeadWidth,
            opened = true,
            pose = trip.pose,
            flying = flight.at,
            fromWidth = with(density) { trip.fromRect.width.toDp() },
            modifier = Modifier.graphicsLayer {
                val t = flight.progress.value
                val from = trip.fromRect
                val to = trip.toRect() ?: from
                val k = lerp(from.width, to.width, t) / size.width
                transformOrigin = TransformOrigin(0f, 0f)
                scaleX = k
                scaleY = k
                translationX = lerp(from.left, to.left, t)
                translationY = lerp(from.top, to.top, t)
            },
        )
    }
}

/**
 * A folder in a list that opens a page: touched, it flies up to the head of that page, and flies
 * back when the page is left.
 */
@Composable
fun FolderLink(preview: FolderPreview, route: Route, modifier: Modifier = Modifier) {
    val actions = LocalActions.current
    val flight = actions.flight
    val page = LocalPage.current
    val reduce = LocalReduceMotion.current
    val pose = remember { FolderPose() }
    val place = remember { arrayOfNulls<LayoutCoordinates>(1) }
    FolderCard(
        title = preview.title,
        detail = preview.detail,
        artwork = preview.artwork,
        files = preview.files,
        onOpen = { actions.openFolder(preview, route, pose, place[0].takeIf { !reduce }, page) },
        pose = pose,
        modifier = modifier
            .onPlaced { place[0] = it }
            .graphicsLayer { alpha = if (flight.trip?.pose === pose) 0f else 1f },
    )
}

/** Where a folder lands: the one held open at the head of its page. */
@Composable
fun Modifier.folderHead(): Modifier {
    val page = LocalPage.current ?: return this
    val flight = LocalActions.current.flight
    return this
        .onPlaced {
            page.head = it
            flight.headPlaced(page)
        }
        .graphicsLayer { alpha = if (flight.trip?.page === page) 0f else 1f }
}
