package app.aether.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aether.core.designsystem.art.gentleEdgeRefraction
import app.aether.core.designsystem.component.FolderFile
import app.aether.core.designsystem.component.GlassPill
import app.aether.core.designsystem.component.Glyph
import app.aether.core.designsystem.component.Loading
import app.aether.core.designsystem.component.Notice
import app.aether.core.designsystem.component.SectionHeading
import app.aether.core.designsystem.theme.Space
import app.aether.core.designsystem.theme.separate
import app.aether.core.model.songCount
import app.aether.core.model.Artwork
import app.aether.core.model.FolderNode
import app.aether.core.model.FolderSelection
import app.aether.core.model.MusicFolder
import app.aether.core.model.PhoneSong
import app.aether.core.model.Track
import app.aether.core.model.foldersInside
import app.aether.player.Downloads
import app.aether.ui.FolderLink
import app.aether.ui.FolderPreview
import app.aether.ui.LocalActions
import app.aether.ui.Route
import app.aether.ui.common.Colophon
import app.aether.ui.common.LocalContainer
import app.aether.ui.common.TrackList
import app.aether.ui.common.rememberPageListState
import app.aether.ui.detail.BackLine
import app.aether.ui.detail.SleeveHead
import app.aether.ui.detail.sleeveTracks
import app.aether.ui.pageLanded

/** A folder on a shelf: how it looks and where it opens. */
internal class PhoneCard(val preview: FolderPreview, val route: Route)

/** The music that plays with no connection: what's been kept, and the songs in the phone's chosen folders. */
internal class PhoneMusic(
    val allowed: Boolean,
    val asked: Boolean,
    val ask: () -> Unit,
    val scanned: Boolean,
    val songs: List<PhoneSong>,
    val folders: List<MusicFolder>?,
    val selection: FolderSelection,
    val kept: List<Track>,
) {
    /** The folders the listener chose, among all those with music. */
    val chosen: List<MusicFolder> = folders.orEmpty().filter { selection.includes(it.path) }
    val phoneTracks: List<Track> = songs.map { it.track }

    /** Everything: what's been kept first, then the phone's own. */
    val all: List<Track> = (kept + phoneTracks).distinctBy { it.id }

    fun under(path: String): List<PhoneSong> = songs.filter { it.folder.startsWith(path) }

    /** The whole of it, as one playlist. */
    val allCard: PhoneCard get() = PhoneCard(
        preview(rootKey, "On this phone", when {
            !allowed && kept.isEmpty() -> "Allow your music"
            else -> songCount(all.size)
        }, all),
        Route.OnThisPhone(),
    )

    val keptCard: PhoneCard get() = PhoneCard(preview(KeptKey, "Kept", songCount(kept.size), kept), Route.Kept)

    fun card(node: FolderNode): PhoneCard =
        PhoneCard(preview(rootKey + node.path, node.name, songCount(node.songCount), under(node.path).map { it.track }), Route.OnThisPhone(node.path))

    private fun preview(key: String, title: String, detail: String, tracks: List<Track>) =
        FolderPreview(key, title, detail, tracks.firstOrNull()?.artwork ?: Artwork.None, tracks.take(3).map { FolderFile(it.title, it.artwork) })

    private val rootKey get() = "folder:phone:"
}

private const val KeptKey = "folder:kept"

/** Reads the phone's music for a screen: asked for once, then it keeps itself up to date. */
@Composable
internal fun rememberPhoneMusic(): PhoneMusic {
    val container = LocalContainer.current
    val phone = container.local
    var allowed by remember { mutableStateOf(phone.hasPermission()) }
    val songs by phone.songs.collectAsStateWithLifecycle()
    val folders by phone.folders.collectAsStateWithLifecycle()
    val selection by phone.selection.collectAsStateWithLifecycle()
    val entries by container.downloads.entries.collectAsStateWithLifecycle()
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        allowed = granted
        container.settings.markAsked(phone.permission)
        phone.refresh()
    }
    // Given or taken away in the phone's settings while the app was out of sight.
    LifecycleResumeEffect(phone) {
        allowed = phone.hasPermission()
        onPauseOrDispose { }
    }
    // The first to ask has the phone looked at; so does a permission that has just been given.
    LaunchedEffect(allowed) { if (songs == null || (allowed && folders == null)) phone.refresh() }
    val kept = remember(entries) { entries.values.filter { it.status == Downloads.Status.Kept }.map { it.track } }
    return remember(allowed, songs, folders, selection, kept) {
        PhoneMusic(
            allowed = allowed,
            asked = container.settings.asked(phone.permission),
            ask = { request.launch(phone.permission) },
            scanned = songs != null,
            songs = songs.orEmpty(),
            folders = folders,
            selection = selection,
            kept = kept,
        )
    }
}

/** On Home: offline music as a playlist of its own, and the folders it's made of, up where they can be seen. */
@Composable
fun OnThisPhoneShelf(modifier: Modifier = Modifier) {
    val music = rememberPhoneMusic()
    val rowState = rememberLazyListState()
    val cards = remember(music) {
        buildList {
            add(music.allCard)
            if (music.kept.isNotEmpty()) add(music.keptCard)
            foldersInside("", music.chosen).forEach { add(music.card(it)) }
        }
    }
    Column(modifier.separate().fillMaxWidth().padding(top = 52.dp)) {
        SectionHeading("On this phone", strapline = "Plays without a connection")
        Spacer(Modifier.height(18.dp))
        LazyRow(
            state = rowState,
            modifier = Modifier.fillMaxWidth().gentleEdgeRefraction(rowState),
            contentPadding = PaddingValues(horizontal = Space.edge, vertical = 22.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(cards, key = { it.preview.key }) { FolderLink(it.preview, it.route) }
        }
    }
}

/** Folders, two to a row, each one opening on the next level down. */
private fun LazyListScope.folderGrid(cards: List<PhoneCard>) {
    items(cards.chunked(2), key = { it.first().preview.key }) { pair ->
        Row(Modifier.separate().fillMaxWidth().padding(horizontal = Space.edge, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            pair.forEach { FolderLink(it.preview, it.route) }
            if (pair.size == 1) Spacer(Modifier.width(168.dp))
        }
    }
}

@Composable
private fun AllowMusic(music: PhoneMusic) {
    Notice(
        "Your own music",
        if (music.asked) "Allow AETHER to read your audio in the phone's settings, under Apps." else "AETHER can play the songs on this phone straight from their files, at their own quality. It needs permission to read your audio; nothing leaves the phone.",
        action = if (music.asked) null else "Allow",
        onAction = music.ask,
    )
}

@Composable
private fun ChooseFolders() {
    val actions = LocalActions.current
    Box(Modifier.fillMaxWidth().padding(vertical = 18.dp), contentAlignment = Alignment.Center) {
        GlassPill("Choose folders", trailingIcon = Glyph.Forward, small = true, onClick = { actions.nav.open(Route.MusicFolders()) })
    }
}

/** The Library's shelf of the phone's own music. */
internal fun LazyListScope.phoneShelf(music: PhoneMusic) {
    when {
        !music.allowed -> item { AllowMusic(music) }
        !music.scanned -> item { Loading() }
        else -> {
            if (music.phoneTracks.isNotEmpty()) item { PlayRow(music.phoneTracks) }
            folderGrid(foldersInside("", music.chosen).map(music::card))
            item { ChooseFolders() }
            if (music.phoneTracks.isNotEmpty()) item { TrackList(music.phoneTracks, playContext = music.phoneTracks) }
        }
    }
}

/**
 * Offline music as a playlist: the whole of it at the top ([path] empty), and from there the phone's
 * folders, each a page of the folders inside it and the songs in it. Every level plays everything
 * under it.
 */
@Composable
fun OnThisPhoneScreen(path: String) {
    val container = LocalContainer.current
    val actions = LocalActions.current
    val music = rememberPhoneMusic()
    val playback by container.playback.state.collectAsStateWithLifecycle()
    val here = FolderSelection.normalize(path)
    val root = here.isEmpty()
    val name = if (root) "On this phone" else here.removeSuffix("/").substringAfterLast('/')
    val tracks = remember(music, here) { if (root) music.all else music.under(here).map { it.track } }
    val songsHere = remember(music, here) { music.songs.filter { it.folder == here }.map { it.track } }
    val cards = remember(music, here) { buildList { if (root && music.kept.isNotEmpty()) add(music.keptCard); foldersInside(here, music.chosen).forEach { add(music.card(it)) } } }
    val preview = actions.preview?.takeIf { it.key == "folder:phone:$here" }
    val landed = pageLanded()
    LazyColumn(Modifier.fillMaxSize(), state = rememberPageListState(), contentPadding = PaddingValues(bottom = 0.dp)) {
        item { BackLine() }
        item(key = "head") {
            SleeveHead(
                preview?.title ?: name,
                preview?.detail ?: songCount(tracks.size),
                preview?.artwork ?: tracks.firstOrNull()?.artwork ?: Artwork.None,
                preview?.files ?: tracks.take(3).map { FolderFile(it.title, it.artwork) },
            ) { if (tracks.isNotEmpty()) PlayRow(tracks) }
        }
        if (!landed) return@LazyColumn
        when {
            root && !music.allowed && music.kept.isEmpty() -> item { AllowMusic(music) }
            !music.scanned -> item { Loading() }
            tracks.isEmpty() -> {
                item { Notice("No music here", "Songs you keep and songs in the folders you've chosen show here.") }
                if (root) item { ChooseFolders() }
            }
            else -> {
                folderGrid(cards)
                if (root && !music.allowed) item { AllowMusic(music) }
                if (root && music.allowed) item { ChooseFolders() }
                sleeveTracks(songsHere, playing = playback.current?.id, isPlaying = playback.isPlaying, keyPrefix = "t") { i -> actions.play(songsHere, i) }
            }
        }
        item(key = "colophon") { Colophon() }
    }
}

/** What's been kept on the phone from the catalogue. */
@Composable
fun KeptScreen() {
    val container = LocalContainer.current
    val actions = LocalActions.current
    val entries by container.downloads.entries.collectAsStateWithLifecycle()
    val playback by container.playback.state.collectAsStateWithLifecycle()
    val kept = remember(entries) { entries.values.filter { it.status == Downloads.Status.Kept }.map { it.track } }
    val preview = actions.preview?.takeIf { it.key == KeptKey }
    val landed = pageLanded()
    LazyColumn(Modifier.fillMaxSize(), state = rememberPageListState(), contentPadding = PaddingValues(bottom = 0.dp)) {
        item { BackLine() }
        item(key = "head") {
            SleeveHead(
                preview?.title ?: "Kept",
                preview?.detail ?: songCount(kept.size),
                preview?.artwork ?: kept.firstOrNull()?.artwork ?: Artwork.None,
                preview?.files ?: kept.take(3).map { FolderFile(it.title, it.artwork) },
            ) { if (kept.isNotEmpty()) PlayRow(kept) }
        }
        if (!landed) return@LazyColumn
        if (kept.isEmpty()) item { Notice("Nothing kept yet", "Hold a song and choose Keep on this phone. Kept songs play without a connection.") }
        else sleeveTracks(kept, playing = playback.current?.id, isPlaying = playback.isPlaying, keyPrefix = "t") { i -> actions.play(kept, i) }
        item(key = "colophon") { Colophon() }
    }
}
