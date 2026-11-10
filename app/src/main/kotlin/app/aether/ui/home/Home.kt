@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.aether.ui.home

import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import app.aether.core.designsystem.art.DotMorphLayer
import app.aether.core.designsystem.art.DotMorph
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.layout.onSizeChanged
import androidx.annotation.RequiresApi
import android.util.Log
import android.os.Build
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.content.Context
import kotlinx.coroutines.delay
import app.aether.core.designsystem.art.Pictures
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.key
import app.aether.ui.common.rememberPageListState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aether.core.designsystem.art.ArtTreatment
import app.aether.core.designsystem.art.TreatedArtwork
import app.aether.core.designsystem.art.edgeRefraction
import app.aether.core.designsystem.art.gentleEdgeRefraction
import app.aether.core.designsystem.component.FolderCard
import app.aether.core.designsystem.component.FolderFile
import app.aether.core.designsystem.component.GlassPill
import app.aether.core.designsystem.component.Strapline
import app.aether.core.designsystem.component.LightCross
import app.aether.core.designsystem.component.Loading
import app.aether.core.designsystem.component.Notice
import app.aether.core.designsystem.component.SectionHeading
import app.aether.core.designsystem.component.Txt
import app.aether.core.designsystem.glass.LocalReduceMotion
import app.aether.core.designsystem.theme.Ink
import app.aether.core.designsystem.theme.Space
import app.aether.core.designsystem.theme.Type
import app.aether.core.model.songCount
import app.aether.core.model.Artwork
import app.aether.core.model.Feed
import app.aether.core.model.FeedFilter
import app.aether.core.model.FeedSection
import app.aether.core.model.Outcome
import app.aether.core.model.PageToken
import app.aether.core.model.SectionStyle
import app.aether.ui.LocalActions
import app.aether.ui.Route
import app.aether.ui.Tab
import app.aether.ui.common.Load
import app.aether.ui.common.LocalContainer
import app.aether.ui.common.SectionBlock
import app.aether.ui.common.rememberLoad
import android.os.SystemClock
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import app.aether.ui.LocalArrival
import app.aether.ui.LocalRoomsHidden
import app.aether.ui.LocalPageSettled
import app.aether.core.designsystem.theme.FluidSpring
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.withTimeoutOrNull
import app.aether.ui.common.Colophon
import app.aether.ui.common.DockClearance
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import app.aether.R
import app.aether.core.designsystem.theme.Motion
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.ui.platform.LocalWindowInfo
import app.aether.ui.DockHeight
import app.aether.ui.FolderLink
import app.aether.ui.FolderPreview
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.layout.layout
import app.aether.core.designsystem.theme.retained
import app.aether.core.designsystem.theme.separate
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.layout.size

/** The figures behind Listen, each held a while before the next comes up. */
private val Statues = listOf(
    R.drawable.art_statue,
    R.drawable.art_god_zeus,
    R.drawable.art_god_hera,
    R.drawable.art_god_apollo,
    R.drawable.art_god_nike,
    R.drawable.art_god_smyrna,
    R.drawable.art_god_artemis,
    R.drawable.art_god_poseidon,
    R.drawable.art_god_athena,
    R.drawable.art_god_hermes,
    R.drawable.art_god_aphrodite,
    R.drawable.art_god_dionysus,
)
/** How long each figure is on show before the next may come: time Listen is seen, not time away. */
private const val StatueHoldMs = 9_000L

/** How long Listen must have been perfectly still before a figure changes: no touch, no scroll, no page moving. */
private const val StillBeforeChangeMs = 3_500L

/** How long it must be still again before a change that was interrupted carries on from where it stopped. */
private const val StillBeforeResumeMs = 1_200L

/** The dots have all landed by 0.94 of this; it has a long, slow end, in which one print becomes the other. */
private val Reassembles = FluidSpring(3.6f, 1f)
private val Crossfades = FluidSpring(1.7f, 1f)

/** 0 to 1 along [spring], [seconds] into it; null once it has come to rest. */
private fun FluidSpring.progressAt(seconds: Float, rest: Float): Float? {
    val (offset, velocity) = solve(-1f, 0f, seconds)
    if (kotlin.math.abs(offset) < rest && kotlin.math.abs(velocity) < rest * 8f) return null
    return (1f + offset).coerceIn(0f, 1f)
}

private fun ramp(x: Float, from: Float, to: Float) = ((x - from) / (to - from)).coerceIn(0f, 1f)

/** The morph from one statue to the next, or null if this phone can't make it (the masthead crossfades instead). */
@RequiresApi(34)
private suspend fun dotsBetween(context: Context, from: Int, to: Int, box: IntSize, density: Float): DotMorph? = withContext(Dispatchers.Default) {
    runCatching {
        val options = BitmapFactory.Options().apply {
            inScaled = false
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val a = BitmapFactory.decodeResource(context.resources, from, options)
        val b = BitmapFactory.decodeResource(context.resources, to, options)
        DotMorph.build(a, b, box.width, box.height, StatuePrint.dotDp * density, StatuePrint.angle, StatuePrint.contrast, density).also {
            a.recycle()
            b.recycle()
        }
    }.onFailure { Log.w("AETHER", "Dot morph unavailable", it) }.getOrNull()
}
private val StatuePrint = ArtTreatment.Halftone(dotDp = 2.3f, angle = 0.6f, contrast = 1.45f)

/** What Home has loaded past its first page, and the first page it continues ([anchor], its first section's name). */
private class HomeMore(val anchor: String?, val sections: List<FeedSection>, val next: PageToken?)

private fun sectionKey(section: FeedSection): String = "s:${section.title}:${section.items.firstOrNull()?.title}"

/** Listen: the day's music, as an editorial front page. */
@Composable
fun HomeScreen() {
    val container = LocalContainer.current
    val actions = LocalActions.current
    val source = container.sources.primary
    var filterTitle by rememberSaveable { mutableStateOf<String?>(null) }
    var filters by remember { mutableStateOf(container.catalog.peek<List<FeedFilter>>("home:filters").orEmpty()) }
    val filter = filters.firstOrNull { it.title == filterTitle }
    val key = "home:${filterTitle ?: ""}"
    val (load, retry) = rememberLoad(key) { if (filter == null) source.home() else source.filter(filter) }
    // The rest of the page, loaded further down: its sections and where to continue, kept with the
    // catalogue as one thing, so coming back to Home finds the page as long as it was. (They were
    // kept as three separate entries; when the catalogue let one go and not the others, Home came
    // back with only its first few sections and never asked for the rest.)
    var more by remember(key) { mutableStateOf(container.catalog.peek<HomeMore>("$key:more")) }
    val recent by container.library.recent(12).collectAsStateWithLifecycle(emptyList())
    val playlists by container.library.playlistsWithPreview().collectAsStateWithLifecycle(emptyList())

    val feed = (load as? Load.Ready<Feed>)?.value
    LaunchedEffect(feed) {
        if (feed == null) return@LaunchedEffect
        val anchor = feed.sections.firstOrNull()?.title
        // A fresh first page (or a different one) continues from its own token.
        if (more?.anchor != anchor) {
            more = HomeMore(anchor, emptyList(), feed.next).also { container.catalog.put("$key:more", it) }
        }
        if (feed.filters.isNotEmpty()) {
            filters = feed.filters
            container.catalog.put("home:filters", feed.filters)
        }
    }

    val list = rememberPageListState()
    // The day's capsule rides with the masthead: Home tells it how far the masthead has scrolled up,
    // and that the masthead is gone once it isn't the first row.
    val arrival = LocalArrival.current
    DisposableEffect(list) {
        arrival.homeScroll = { if (list.firstVisibleItemIndex == 0) list.firstVisibleItemScrollOffset else null }
        onDispose { arrival.homeScroll = null }
    }
    // A finger anywhere on the page, watched without being taken: the figures wait while one is down.
    var touching by remember { mutableStateOf(false) }
    LazyColumn(
        Modifier.fillMaxSize().pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    touching = event.changes.any { it.pressed }
                }
            }
        },
        state = list,
        contentPadding = PaddingValues(bottom = 0.dp),
    ) {
        // A photograph, not the service's abstract backdrop: the first cover on the page.
        item(key = "masthead") { Masthead(Modifier.separate(), onScreen = { list.firstVisibleItemIndex == 0 }, scrolling = { touching || list.isScrollInProgress }) }
        // Every row keeps its place in the list even while it's empty (the library's rows arrive a
        // frame after the page), so coming back restores the page exactly where it was left.
        item(key = "filters") {
            // Only the masthead can be above it: once it's scrolled off the top, it rests.
            if (filters.isNotEmpty()) Filters(filters, filterTitle, onScreen = { list.firstVisibleItemIndex <= 1 }) { t -> filterTitle = if (filterTitle == t) null else t }
        }
        // Up where it's seen, and whatever the network does: this is the music that needs none, as
        // albums, the phone's own and what's been kept, each a shelf of its own.
        item(key = "phone") { if (filterTitle == null) app.aether.ui.library.OfflineShelves() }
        when (load) {
            Load.Loading -> item(key = "loading") { Loading(Modifier.padding(top = 40.dp)) }
            is Load.Failed -> item(key = "failed") { Notice(load.title, load.body, action = "Try again", onAction = retry) }
            is Load.Ready -> {
                item(key = "recent") {
                    if (filterTitle == null && recent.isNotEmpty()) SectionBlock(FeedSection("Recently played", recent, strapline = "From your listening", style = SectionStyle.CAROUSEL))
                }
                item(key = "yours") {
                    if (filterTitle == null && playlists.isNotEmpty()) Column(Modifier.separate().fillMaxWidth().padding(top = 52.dp)) {
                        SectionHeading("Your playlists", strapline = "Kept by you", onMore = { actions.nav.select(Tab.Library) })
                        Spacer(Modifier.height(18.dp))
                        val rowState = rememberLazyListState()
                        LazyRow(
                            state = rowState,
                            modifier = Modifier.fillMaxWidth().gentleEdgeRefraction(rowState),
                            contentPadding = PaddingValues(horizontal = Space.edge, vertical = 22.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            items(playlists, key = { it.id }) { p ->
                                FolderLink(
                                    FolderPreview("folder:local:${p.id}", p.title, songCount(p.count), p.preview.firstOrNull()?.artwork ?: Artwork.None, p.preview.map { FolderFile(it.title, it.artwork) }),
                                    Route.LocalPlaylist(p.id),
                                )
                            }
                        }
                    }
                }
                // A section the continuation repeats is shown once (and can't break the list's keys).
                val sections = (load.value.sections + more?.sections.orEmpty()).distinctBy(::sectionKey)
                items(sections, key = ::sectionKey) { SectionBlock(it) }
                item(key = "tail") {
                    val m = more
                    val token = m?.next
                    if (m == null || token != null) {
                        if (m != null && token != null) LaunchedEffect(token) {
                            // A filtered page continues the same way as Home itself.
                            val r = source.homeMore(token)
                            val after = if (r is Outcome.Ok) HomeMore(m.anchor, m.sections + r.value.sections, r.value.next) else HomeMore(m.anchor, m.sections, null)
                            more = after
                            container.catalog.put("$key:more", after)
                        }
                        Loading(Modifier.padding(top = 48.dp, bottom = DockClearance))
                    } else {
                        Colophon()
                    }
                }
            }
        }
    }
}

/**
 * The front page's head: a classical statue, printed as a fine halftone in white ink on black,
 * emerging slowly out of the dark; "Listen", oversized, over the fall of its drapery. Art first, as
 * a jeweller's catalogue opens on a photograph rather than a list.
 *
 * The figures change only when nothing else is happening. Each is held for [StatueHoldMs] of being
 * seen; the next comes only once Listen has been perfectly still for [StillBeforeChangeMs] (no finger,
 * no scroll, no room or page on the move, the app in front). A touch, a scroll or a switch mid-change
 * holds it exactly where it is, the dots stopped in the air, asking for no frames, and it carries on
 * along the same curve once the page is still again. So a change never shares the screen's time with
 * anything you're doing.
 */
@Composable
private fun Masthead(modifier: Modifier = Modifier, onScreen: () -> Boolean, scrolling: () -> Boolean) {
    val reduce = LocalReduceMotion.current
    val context = LocalContext.current
    val statues = remember { Statues.map { Artwork.single("android.resource://${context.packageName}/$it", 493, 740) } }
    var shown by rememberSaveable { mutableIntStateOf(0) }
    var coming by remember { mutableStateOf<Int?>(null) }
    // A DotMorph, typed loosely so that phones before Android 14 never load it. They crossfade.
    var morph by remember { mutableStateOf<Any?>(null) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    val change = remember { mutableFloatStateOf(0f) }
    val roomsHidden = LocalRoomsHidden.current
    val settled = LocalPageSettled.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val resumed by lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    val density = LocalDensity.current.density
    // Seen: on show, the app in front. Still: seen, and nothing on the screen moving.
    val seen = { onScreen() && !roomsHidden() && resumed.isAtLeast(Lifecycle.State.RESUMED) && box != IntSize.Zero }
    val still = { seen() && settled() && !scrolling() }
    if (statues.size > 1 && !reduce) LaunchedEffect(statues) {
        while (true) {
            // Held: counted only while it can be seen.
            var held = 0L
            while (held < StatueHoldMs) {
                snapshotFlow { seen() }.first { it }
                val since = SystemClock.uptimeMillis()
                withTimeoutOrNull(StatueHoldMs - held) { snapshotFlow { seen() }.first { !it } }
                held += SystemClock.uptimeMillis() - since
            }
            stillFor(StillBeforeChangeMs, still)
            val next = (shown + 1) % statues.size
            // Everything the change needs is ready before it starts, made off the main thread.
            Pictures.fetch(context, statues[next].url(1080), 1080, software = true)
            val dots = if (Build.VERSION.SDK_INT >= 34) dotsBetween(context, Statues[shown], Statues[next], box, density) else null
            // Making it took a moment: if the page moved meanwhile, it waits for stillness again.
            if (!still()) stillFor(StillBeforeChangeMs, still)
            val spring = if (dots != null) Reassembles else Crossfades
            val rest = if (dots != null) 0.0008f else 0.002f
            try {
                change.floatValue = 0f
                coming = next
                morph = dots
                // Its own clock, which runs only while the page is still: held, the change asks for nothing.
                var elapsed = 0f
                var last = -1L
                while (true) {
                    if (!still()) {
                        last = -1L
                        stillFor(StillBeforeResumeMs, still)
                        continue
                    }
                    val now = withFrameNanos { it }
                    if (last >= 0L) elapsed += ((now - last) / 1e9f).coerceAtMost(1f / 30f)
                    last = now
                    change.floatValue = spring.progressAt(elapsed, rest) ?: break
                }
                change.floatValue = 1f
                shown = next
            } finally {
                coming = null
                morph = null
                change.floatValue = 0f
            }
        }
    }
    val emerge = remember { Animatable(if (reduce) 1f else 0f) }
    LaunchedEffect(Unit) { emerge.animateTo(1f, tween(if (reduce) 0 else 2400, easing = Motion.Editorial)) }

    // The first screen is one composition: the figure down to the ribbon, the ribbon just above the
    // dock, and nothing half hidden under it. So the figure is as tall as that leaves.
    val window = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    val foot = WindowInsets.navigationBarsIgnoringVisibility.asPaddingValues().calculateBottomPadding() + DockHeight + RibbonHeight + 12.dp
    Box(modifier.fillMaxWidth().height((window - foot).coerceIn(480.dp, 760.dp)).onSizeChanged { box = it }) {
        Box(Modifier.matchParentSize()) {
            key(shown) {
                TreatedArtwork(
                    statues[shown],
                    StatuePrint,
                    Modifier.fillMaxSize().graphicsLayer {
                        alpha = emerge.value * 0.92f * when {
                            coming == null -> 1f
                            morph != null -> 1f - ramp(change.floatValue, 0f, 0.05f)
                            else -> 1f - change.floatValue
                        }
                    },
                    sizeHintPx = 1080,
                )
            }
            coming?.let { next ->
                key(next) {
                    TreatedArtwork(
                        statues[next],
                        StatuePrint,
                        Modifier.fillMaxSize().graphicsLayer {
                            alpha = emerge.value * 0.92f * (if (morph != null) ramp(change.floatValue, 0.94f, 1f) else change.floatValue)
                        },
                        sizeHintPx = 1080,
                    )
                }
            }
            if (Build.VERSION.SDK_INT >= 34) morph?.let { dots ->
                DotMorphLayer(
                    dots as DotMorph,
                    { change.floatValue },
                    Modifier.fillMaxSize().graphicsLayer {
                        alpha = 0.92f * ramp(change.floatValue, 0f, 0.05f) * (1f - ramp(change.floatValue, 0.94f, 1f))
                    },
                )
            }
            // The room's darkness drawn in from the left and from below, so the figure stands out of it.
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.6f), 0.45f to Color.Transparent)))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.35f), 0.4f to Color.Transparent, 0.72f to Color.Black.copy(alpha = 0.55f), 1f to Color.Black)))
        }

        Txt(
            "Listen",
            Type.Monument.copy(fontSize = Type.Monument.fontSize * 1.35f, lineHeight = Type.Monument.lineHeight * 1.35f),
            align = TextAlign.Center,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 36.dp),
        )
    }
}

/**
 * Moods and activities as glass capsules, the chosen one lit, on a ribbon that drifts by itself,
 * round and round (the list repeats), slowly enough to read as it passes. A touch stops it at once,
 * and it can be pushed by hand; a moment after you let go, and after any flick has run out, it eases
 * back into its drift, and keeps going for as long as you can see it. It holds still when reduced
 * motion is asked for; scrolled out of sight, under Now Playing or in another room, it asks for no
 * frames at all.
 */
@Composable
private fun Filters(filters: List<FeedFilter>, selected: String?, onScreen: () -> Boolean, onSelect: (String) -> Unit) {
    val count = filters.size
    val state = rememberLazyListState(initialFirstVisibleItemIndex = count * Laps / 2)
    val reduce = LocalReduceMotion.current
    val density = LocalDensity.current
    var touching by remember { mutableStateOf(false) }
    val roomsHidden = LocalRoomsHidden.current
    // Between the list's own steps the ribbon slides as one picture, by fractions of a pixel: the list
    // can only stand on whole pixels, which at this pace came unevenly and read as a stutter. The list
    // is laid out a little wider than the screen and moved only every so often, in step.
    val slide = remember { mutableFloatStateOf(0f) }
    val slack = with(density) { 56.dp.toPx() }
    if (!reduce && count > 1) LaunchedEffect(count) {
        val speed = with(density) { 18.dp.toPx() }
        while (true) {
            // Out of sight it isn't running: not a frame until it can be seen again.
            snapshotFlow { !roomsHidden() && onScreen() }.first { it }
            // Its pace follows a critically damped spring up to speed; a touch stops it outright.
            var pace = 0f
            var resumeAt = 0L
            var then = withFrameNanos { it }
            while (!roomsHidden() && onScreen()) {
                val now = withFrameNanos { it }
                val dt = ((now - then) / 1e9f).coerceIn(0f, 0.05f)
                then = now
                if (touching || state.isScrollInProgress) {
                    pace = 0f
                    resumeAt = now + DriftPauseNanos
                } else if (now >= resumeAt) {
                    pace += (1f - pace) * (1f - kotlin.math.exp(-dt / 0.45f))
                    slide.floatValue += speed * dt * pace
                    if (slide.floatValue >= slack) {
                        // A delta, not a scroll: it never competes with a finger for the list. (A scroll
                        // here lost to the finger with a cancellation, which ended this loop for good:
                        // the ribbon that never moved again once it had been touched.)
                        val whole = slide.floatValue.toInt()
                        state.dispatchRawDelta(whole.toFloat())
                        slide.floatValue -= whole
                    }
                }
            }
        }
    }
    LazyRow(
        state = state,
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                // Seen before the row itself takes the touch: any finger on the ribbon holds it.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    touching = true
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                    } while (event.changes.any { it.pressed })
                    touching = false
                }
            }
            .edgeRefraction(state, liftFraction = 0.1f)
            .layout { measurable, constraints ->
                val wide = constraints.maxWidth + slack.toInt() + 1
                val placeable = measurable.measure(constraints.copy(minWidth = wide, maxWidth = wide))
                layout(constraints.maxWidth, placeable.height) {
                    placeable.placeWithLayer(0, 0) { translationX = -slide.floatValue }
                }
            },
        contentPadding = PaddingValues(horizontal = Space.edge, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(count * Laps, key = { it }) { i ->
            val f = filters[i % count]
            // Each capsule a picture, so the ribbon's every step is a few quads, and its words slide
            // with it instead of snapping from pixel to pixel against their glass.
            GlassPill(f.title, onClick = { onSelect(f.title) }, lit = f.title == selected, modifier = Modifier.retained())
        }
    }
}

/** How tall the ribbon stands, its capsules and the room left around them. */
private val RibbonHeight = 58.dp

/** How many times the ribbon's list repeats: enough that no one reaches either end. */
private const val Laps = 2000

/** The ribbon waits this long after a touch, or a flick's end, before drifting on. */
private const val DriftPauseNanos = 1_600_000_000L


/** Returns once [still] has held, without a break, for [ms]; a break starts the wait again. */
private suspend fun stillFor(ms: Long, still: () -> Boolean) {
    while (true) {
        snapshotFlow { still() }.first { it }
        val broken = withTimeoutOrNull(ms) { snapshotFlow { still() }.first { !it } }
        if (broken == null) return
    }
}
