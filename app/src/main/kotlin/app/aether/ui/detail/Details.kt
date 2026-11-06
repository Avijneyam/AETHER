package app.aether.ui.detail

import app.aether.ui.common.rememberPageListState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aether.core.designsystem.art.ArtTreatment
import app.aether.core.designsystem.art.TreatedArtwork
import app.aether.core.designsystem.component.DragSheet
import app.aether.core.designsystem.component.GlassButton
import app.aether.core.designsystem.component.GlassCircle
import app.aether.core.designsystem.component.GlassPanel
import app.aether.core.designsystem.component.GlassPill
import app.aether.core.designsystem.component.Strapline
import app.aether.core.designsystem.component.rememberDragState
import app.aether.core.designsystem.theme.Corners
import app.aether.core.designsystem.theme.Space
import app.aether.core.designsystem.component.Glyph
import app.aether.core.designsystem.component.IconAction
import app.aether.core.designsystem.component.Loading
import app.aether.core.designsystem.component.Notice
import app.aether.core.designsystem.component.Txt
import app.aether.core.designsystem.component.quietClick
import app.aether.core.designsystem.theme.Ink
import app.aether.core.designsystem.theme.Type
import app.aether.core.model.AlbumPage
import app.aether.core.model.Artwork
import app.aether.core.model.Charts
import app.aether.core.model.Destination
import app.aether.core.model.Feed
import app.aether.core.model.FeedSection
import app.aether.core.model.MediaId
import app.aether.core.model.Outcome
import app.aether.core.model.PageToken
import app.aether.core.model.PlaylistPage
import app.aether.core.model.SectionStyle
import app.aether.core.model.Track
import app.aether.ui.LocalActions
import app.aether.ui.Route
import app.aether.ui.common.Load
import app.aether.ui.common.LocalContainer
import app.aether.ui.common.SectionBlock
import app.aether.ui.common.TrackList
import app.aether.ui.common.rememberLoad
import kotlinx.coroutines.launch
import app.aether.ui.common.Colophon
import app.aether.ui.common.pageLoading
import app.aether.core.designsystem.component.FolderFile
import app.aether.core.designsystem.theme.separate
import app.aether.ui.common.sections
import app.aether.ui.pageLanded

/** The space a page leaves at its top for the shell's floating back key and the blurred status bar. */
@Composable
fun BackLine() {
    Spacer(Modifier.fillMaxWidth().statusBarsPadding().height(64.dp))
}

/** A page's head, centred: the picture, the name large, the line beneath, then its actions. */
@Composable
private fun CollectionHead(
    artwork: Artwork,
    title: String,
    line: String?,
    details: String?,
    onLine: (() -> Unit)?,
    tracks: List<Track>,
    saved: Boolean?,
    onSave: (() -> Unit)?,
) {
    val actions = LocalActions.current
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        TreatedArtwork(artwork, ArtTreatment.Monochrome(), Modifier.width(264.dp).aspectRatio(1f).clip(Corners.Medium), sizeHintPx = 1080)
        Spacer(Modifier.height(26.dp))
        Txt(title, Type.name(title, Type.Headline), align = TextAlign.Center, maxLines = 3, modifier = Modifier.padding(horizontal = 28.dp))
        if (line != null) {
            Spacer(Modifier.height(8.dp))
            Txt(line, Type.Body, color = Ink.Grey, align = TextAlign.Center, modifier = if (onLine != null) Modifier.quietClick(onLine) else Modifier)
        }
        if (!details.isNullOrBlank()) {
            Spacer(Modifier.height(12.dp))
            Strapline(details)
        }
        Spacer(Modifier.height(22.dp))
        SleeveActions(tracks, saved, onSave)
        Spacer(Modifier.height(26.dp))
    }
}

@Composable
fun AlbumScreen(id: MediaId) {
    val container = LocalContainer.current
    val actions = LocalActions.current
    val scope = rememberCoroutineScope()
    val source = container.sources.of(id) ?: return
    val (load, retry) = rememberLoad("album:$id") { source.album(id) }
    val saved by container.library.isSaved(id).collectAsStateWithLifecycle(false)
    LazyColumn(Modifier.fillMaxSize(), state = rememberPageListState(), contentPadding = PaddingValues(bottom = 0.dp)) {
        item { BackLine() }
        when (load) {
            Load.Loading -> pageLoading()
            is Load.Failed -> item { Notice(load.title, load.body, action = "Try again", onAction = retry) }
            is Load.Ready<AlbumPage> -> {
                val page = load.value
                item {
                    CollectionHead(
                        artwork = page.album.artwork,
                        title = page.album.title,
                        line = page.album.artists.joinToString(", ") { it.name }.ifEmpty { null },
                        details = listOfNotNull(page.album.subtitle, page.details).joinToString("   "),
                        onLine = page.album.artists.firstOrNull()?.id?.let { a -> { actions.nav.open(Route.Artist(a)) } },
                        tracks = page.tracks,
                        saved = saved,
                        onSave = { scope.launch { container.library.save(page.album, !saved) } },
                    )
                }
                item { TrackList(page.tracks, numbered = true, artists = false) }
                page.description?.let { d -> item { Description(d) } }
                item(key = "colophon") { Colophon() }
            }
        }
    }
}

/**
 * A playlist, as a record's sleeve: the folder that was touched flies up and stays open at the head;
 * the tracklist is set in sides; the label's credit at the foot. Long playlists keep loading as the
 * list nears its end.
 */
@Composable
fun PlaylistScreen(id: MediaId) {
    val container = LocalContainer.current
    val actions = LocalActions.current
    val scope = rememberCoroutineScope()
    val source = container.sources.of(id) ?: return
    val (load, retry) = rememberLoad("playlist:$id") { source.playlist(id) }
    val saved by container.library.isSaved(id).collectAsStateWithLifecycle(false)
    val playback by container.playback.state.collectAsStateWithLifecycle()
    val more = remember(id) { mutableStateListOf<Track>() }
    var next by remember(id) { mutableStateOf<PageToken?>(null) }
    var started by remember(id) { mutableStateOf(false) }
    val page = (load as? Load.Ready<PlaylistPage>)?.value
    LaunchedEffect(page) { if (page != null && !started) { started = true; next = page.next } }
    // The folder that was touched stands open at once; the list follows once it has landed.
    val preview = actions.preview?.takeIf { it.key == "folder:$id" }
    val landed = pageLanded()
    LazyColumn(Modifier.fillMaxSize(), state = rememberPageListState(), contentPadding = PaddingValues(bottom = 0.dp)) {
        item { BackLine() }
        when (load) {
            Load.Loading -> {
                if (preview != null) item(key = "head") {
                    SleeveHead(preview.title, preview.detail, preview.artwork, preview.files) { SleeveActions(emptyList(), saved = null, onSave = null) }
                }
                if (landed) pageLoading()
            }
            is Load.Failed -> item { Notice(load.title, load.body, action = "Try again", onAction = retry) }
            is Load.Ready -> {
                val p = load.value
                val all = p.tracks + more
                item(key = "head") {
                    // The folder stays as it was when touched, its pictures already in memory: it never
                    // reloads into something else once its list arrives.
                    val files = preview?.files ?: remember(p.tracks.firstOrNull()?.id) { all.take(3).map { FolderFile(it.title, it.artwork) } }
                    SleeveHead(preview?.title ?: p.playlist.title, preview?.detail ?: p.playlist.author ?: "${all.size} songs", preview?.artwork ?: p.playlist.artwork, files) {
                        SleeveActions(all, saved = saved, onSave = { scope.launch { container.library.save(p.playlist, !saved) } })
                    }
                }
                if (!landed) return@LazyColumn
                sleeveTracks(all, playing = playback.current?.id, isPlaying = playback.isPlaying, keyPrefix = "t") { i -> actions.play(all, i) }
                next?.let { token ->
                    item(key = "more:${token.value.hashCode()}") {
                        LaunchedEffect(token) {
                            val r = source.tracksMore(token)
                            if (r is Outcome.Ok) { more += r.value.items; next = r.value.next } else next = null
                        }
                        Loading()
                    }
                }
                item(key = "label") { SleeveLabel(p.playlist.artwork, p.playlist.title, p.playlist.author, all) }
                p.description?.let { d -> item { Description(d) } }
                item(key = "colophon") { Colophon() }
            }
        }
    }
}

@Composable
private fun Description(text: String) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = Space.edge, vertical = 36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Strapline("About")
        Spacer(Modifier.height(12.dp))
        GlassPanel(Modifier.quietClick { open = !open }) {
            Txt(text, Type.Aside, color = Ink.Grey, align = TextAlign.Center, maxLines = if (open) Int.MAX_VALUE else 5, modifier = Modifier.padding(vertical = 12.dp))
        }
    }
}

@Composable
fun ArtistScreen(id: MediaId) {
    val container = LocalContainer.current
    val actions = LocalActions.current
    val scope = rememberCoroutineScope()
    val source = container.sources.of(id) ?: return
    val (load, retry) = rememberLoad("artist:$id") { source.artist(id) }
    val followed by container.library.isSaved(id).collectAsStateWithLifecycle(false)
    LazyColumn(Modifier.fillMaxSize(), state = rememberPageListState(), contentPadding = PaddingValues(bottom = 0.dp)) {
        when (load) {
            Load.Loading -> { item { BackLine() }; pageLoading() }
            is Load.Failed -> { item { BackLine() }; item { Notice(load.title, load.body, action = "Try again", onAction = retry) } }
            is Load.Ready -> {
                val a = load.value
                item(key = "hero") { ArtistHero(a) }
                item {
                    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        GlassCircle(Glyph.Shuffle, "Shuffle", { actions.shuffle(a.topTracks) }, size = 52.dp)
                        Spacer(Modifier.width(14.dp))
                        GlassButton("Play", onClick = {
                            val first = a.topTracks.firstOrNull() ?: return@GlassButton
                            actions.play(listOf(first))
                        })
                        Spacer(Modifier.width(14.dp))
                        GlassPill(if (followed) "Following" else "Follow", icon = if (followed) Glyph.Check else Glyph.Plus, lit = followed,
                            onClick = { scope.launch { container.library.follow(a.artist, !followed) } })
                    }
                }
                items(a.sections, key = { "s:${it.title}" }) { section ->
                    if (section.style == SectionStyle.LIST) {
                        Column(Modifier.separate().padding(top = 48.dp)) {
                            app.aether.core.designsystem.component.SectionHeading(section.title)
                            Spacer(Modifier.height(16.dp))
                            TrackList(section.items.filterIsInstance<Track>(), playContext = a.topTracks, artists = false)
                        }
                    } else {
                        SectionBlock(section)
                    }
                }
                a.description?.let { d -> item { Description(d) } }
                item(key = "colophon") { Colophon() }
            }
        }
    }
}

/** Any other page of sections: a mood, new releases, a shelf's "See all", a podcast. */
@Composable
fun BrowseScreen(destination: Destination, title: String?) {
    val container = LocalContainer.current
    val source = container.sources.of(destination.source) ?: return
    val (load, retry) = rememberLoad("browse:${destination.key}:${destination.params}") { source.browse(destination) }
    FeedPage(title, load, retry)
}

@Composable
private fun FeedPage(title: String?, load: Load<Feed>, retry: () -> Unit, extra: (@Composable () -> Unit)? = null) {
    LazyColumn(Modifier.fillMaxSize(), state = rememberPageListState(), contentPadding = PaddingValues(bottom = 0.dp)) {
        item { BackLine() }
        item {
            val name = (load as? Load.Ready)?.value?.title ?: title ?: ""
            Txt(name, Type.name(name), align = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp))
        }
        if (extra != null) item { extra() }
        when (load) {
            Load.Loading -> pageLoading()
            is Load.Failed -> item { Notice(load.title, load.body, action = "Try again", onAction = retry) }
            is Load.Ready -> {
                sections(load.value.sections, topSpace = 48.dp)
                item(key = "colophon") { Colophon() }
            }
        }
    }
}

/** The charts, for any of the countries they're kept for. */
@Composable
fun ChartsScreen() {
    val container = LocalContainer.current
    val source = container.sources.primary
    val country by container.settings.chartsCountry.collectAsStateWithLifecycle()
    val picker = rememberDragState()
    val (load, retry) = rememberLoad("charts:${country ?: ""}") { source.charts(country) }
    val charts = (load as? Load.Ready<Charts>)?.value
    Box(Modifier.fillMaxSize()) {
        FeedPage(
            "Charts",
            when (load) {
                is Load.Ready -> Load.Ready(Feed(load.value.sections, title = "Charts"))
                is Load.Failed -> load
                Load.Loading -> Load.Loading
            },
            retry,
        ) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Strapline("Weekly, daily and trending")
                Spacer(Modifier.height(14.dp))
                GlassPill(charts?.country?.name ?: "Where you are", trailingIcon = Glyph.Down, onClick = { if (charts != null) picker.show() })
            }
        }
        DragSheet(picker) {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = Space.edge)) {
                item { Txt("Country", Type.Display, modifier = Modifier.padding(vertical = 16.dp)) }
                items(charts?.countries.orEmpty(), key = { it.code }) { c ->
                    Txt(
                        c.name,
                        Type.Title.copy(fontStyle = if (c.code == charts?.country?.code) FontStyle.Italic else FontStyle.Normal),
                        color = if (c.code == charts?.country?.code) Ink.White else Ink.Grey,
                        modifier = Modifier.fillMaxWidth().quietClick { container.settings.setChartsCountry(c.code); picker.hide() }.padding(vertical = 11.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun MoodsScreen() {
    val container = LocalContainer.current
    val (load, retry) = rememberLoad("moods") { container.sources.primary.moods() }
    LazyColumn(Modifier.fillMaxSize(), state = rememberPageListState(), contentPadding = PaddingValues(bottom = 0.dp)) {
        item { BackLine() }
        item { Txt("Moods & genres", Type.Monument, align = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) }
        when (load) {
            Load.Loading -> pageLoading()
            is Load.Failed -> item { Notice(load.title, load.body, action = "Try again", onAction = retry) }
            is Load.Ready -> {
                sections(load.value.map { group -> FeedSection(group.title, group.moods, style = SectionStyle.GRID) }, topSpace = 48.dp)
                item(key = "colophon") { Colophon() }
            }
        }
    }
}
