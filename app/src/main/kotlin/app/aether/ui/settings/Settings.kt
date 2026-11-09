package app.aether.ui.settings

import app.aether.ui.common.rememberPageListState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aether.core.designsystem.theme.separate
import app.aether.core.designsystem.art.ArtTreatment
import app.aether.core.designsystem.component.GlassPill
import app.aether.core.designsystem.component.GlassSegments
import app.aether.core.designsystem.component.Glyph
import app.aether.core.designsystem.component.LightCross
import app.aether.core.designsystem.component.Txt
import app.aether.core.designsystem.component.quietClick
import app.aether.core.designsystem.theme.Ink
import app.aether.core.designsystem.theme.Space
import app.aether.core.designsystem.theme.Type
import app.aether.core.model.songCount
import app.aether.core.model.songsIncluded
import app.aether.ui.LocalActions
import app.aether.ui.Route
import app.aether.ui.common.LocalContainer
import app.aether.ui.detail.BackLine
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import app.aether.ui.common.Colophon
import androidx.compose.ui.platform.LocalContext
import app.aether.ui.account.SignInActivity
import app.aether.core.model.Outcome
import app.aether.sources.addon.AddonSource
import app.aether.ui.common.NameDialog

/**
 * Settings, set like the back pages of a magazine rather than a form: numbered heads in spaced
 * capitals over a hairline; each setting named as everything in AETHER is named (the grotesk's
 * capitals) with its reason beneath in the serif; and every control a key of the tab bar's glass,
 * a choice marked by the bar's lit drop sliding to it.
 */
@Composable
fun SettingsScreen() {
    val container = LocalContainer.current
    val settings = container.settings
    val scope = rememberCoroutineScope()
    val haptics by settings.haptics.collectAsStateWithLifecycle()
    val reduce by settings.reduceMotion.collectAsStateWithLifecycle()
    val efficient by settings.efficient.collectAsStateWithLifecycle()
    val artwork by settings.artwork.collectAsStateWithLifecycle()
    val simpleGlass by settings.simpleGlass.collectAsStateWithLifecycle()
    val timedLyrics by settings.timedLyrics.collectAsStateWithLifecycle()
    val artistArt by settings.artistArt.collectAsStateWithLifecycle()
    val lyricsStyle by settings.lyricsStyle.collectAsStateWithLifecycle()
    val phoneFolders by container.local.folders.collectAsStateWithLifecycle()
    val phoneChoice by container.local.selection.collectAsStateWithLifecycle()
    val actions = LocalActions.current
    LaunchedEffect(Unit) { if (container.local.hasPermission() && phoneFolders == null) container.local.refresh() }
    val session by container.account.cookies.collectAsStateWithLifecycle()
    val sources by container.sources.added.collectAsStateWithLifecycle()
    val addresses by container.settings.addons.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var addError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    LazyColumn(Modifier.fillMaxSize(), state = rememberPageListState(), contentPadding = PaddingValues(bottom = 0.dp)) {
        item { BackLine() }
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = Space.edge + 12.dp).padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Txt("Settings", Type.Monument, align = TextAlign.Center)
                Spacer(Modifier.height(14.dp))
                Txt("How AETHER sounds, how it moves, and what it keeps.", Type.Aside, color = Ink.Grey, align = TextAlign.Center)
            }
        }
        item {
            Section("I", "Account") {
                Row(Modifier.fillMaxWidth().padding(vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Words(
                        "YouTube Music",
                        if (session != null) "Signed in. Home, your library and mixes are your own."
                        else "Sign in on Google's own page for your Home, library and mixes. AETHER never sees your password.",
                        Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(18.dp))
                    if (session != null) {
                        GlassPill("Sign out", small = true, onClick = {
                            container.account.signOut()
                            container.catalog.clear()
                        })
                    } else {
                        GlassPill("Sign in", small = true, lit = true, onClick = { context.startActivity(SignInActivity.intent(context)) })
                    }
                }
                Rule()
            }
        }
        item {
            Section("II", "Sources") {
                Words("Your own sources", "Add a source by its address: your own server or an open library that speaks the addon protocol. AETHER plays open media only, and ships no sources.", Modifier.padding(vertical = 18.dp))
                addresses.forEach { address ->
                    val connected = sources.firstOrNull { (it as? AddonSource)?.address == address }
                    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Words(connected?.name ?: "Not reachable", address, Modifier.weight(1f))
                        Spacer(Modifier.width(18.dp))
                        GlassPill("Remove", small = true, onClick = {
                            container.settings.removeAddon(address)
                            container.sources.unregister(AddonSource.idFor(address))
                        })
                    }
                    Rule()
                }
                addError?.let { Txt(it, Type.Meta, color = Ink.Grey, modifier = Modifier.padding(top = 12.dp)) }
                GlassPill("Add a source", icon = Glyph.Plus, onClick = { adding = true }, modifier = Modifier.padding(vertical = 18.dp))
                Rule()
            }
        }
        item {
            Section("III", "Music on this phone") {
                Row(Modifier.fillMaxWidth().padding(vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Words(
                        "Music folders",
                        phoneFolders?.let { f -> songsIncluded(f, phoneChoice).let { n -> "${songCount(n)} from the folders you've chosen. Voice notes and other apps' audio are left out unless you add them." } }
                            ?: "Choose which of the phone's folders AETHER reads. They show on Listen, under On this phone.",
                        Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(18.dp))
                    GlassPill("Choose", small = true, trailingIcon = Glyph.Forward, onClick = { actions.nav.open(Route.MusicFolders()) })
                }
                Rule()
            }
        }
        item {
            Section("IV", "Sound") {
                Toggle("Data saver", "Lower-bitrate streams for metered networks. Off plays the best the source has.", efficient) { settings.setEfficient(!efficient) }
            }
        }
        item {
            Section("V", "Feel") {
                Toggle("Haptics", "A light touch you can feel on the actions that matter.", haptics) { settings.setHaptics(!haptics) }
                Toggle("Reduce motion", "No stretch, ripples or drifting. Also follows your phone's setting.", reduce) { settings.setReduceMotion(!reduce) }
                Choice(
                    title = "Glass",
                    detail = "Simple is PODIUM's glass: frosted, lit at the rim, the screen's edges fading to black. Liquid bends light at every edge and frosts the screen's edges live; it asks much more of the phone.",
                    options = listOf("Simple", "Liquid"),
                    selected = if (simpleGlass) 0 else 1,
                    onSelect = { settings.setSimpleGlass(it == 0) },
                )
            }
        }
        item {
            Section("VI", "Now Playing") {
                Toggle(
                    "Timed lyrics",
                    "Words in time with the song, from LRCLIB, an open library of lyrics. Asking it sends a song's title, artist and length; nothing else. Off, the music service's own words are shown.",
                    timedLyrics,
                ) { settings.setTimedLyrics(!timedLyrics) }
                Choice(
                    title = "How lyrics are drawn",
                    detail = "Constellations sets each word as a star in a night sky. Line writes the song by hand, as one unbroken line.",
                    options = listOf("Constellations", "Line"),
                    selected = lyricsStyle.coerceIn(0, 1),
                    onSelect = { settings.setLyricsStyle(it) },
                )
                Choice(
                    title = "The cover",
                    detail = "How the print is shown. Touch the cover on Now Playing to change it at any time.",
                    options = listOf("Original", "Mono", "Halftone", "ASCII", "Dither"),
                    selected = artwork.mod(ArtTreatment.Cycle.size),
                    onSelect = { settings.setArtwork(it) },
                )
            }
        }
        item {
            Section("VII", "Artists and albums") {
                Toggle(
                    "Artist and album art",
                    "A portrait, the artist's own lettering and a short film at the head of their page, and an album's moving cover at the head of its, from Apple Music's public pages, for those who have them. It also asks what kind of music your most played artists make, for your Profile. Asking sends the artist's name and the album's title; nothing else. Off, or on the data saver for the films, a page uses what the source gives.",
                    artistArt,
                ) { settings.setArtistArt(!artistArt) }
            }
        }
        item {
            Section("VIII", "Your data") {
                Erase("Listening history", "Everything you've played, gone from this phone.") { scope.launch { container.library.clearHistory() } }
                Erase("Recent searches", "What you've looked for on Discover.") { scope.launch { container.library.clearSearches() } }
            }
        }
        item {
            Section("IX", "Privacy") {
                Paragraph("AETHER has no analytics and no accounts. Your likes, playlists and history stay on this phone. The only requests it makes are the ones that load pages and play music, and, if you leave them on, the ones for timed lyrics and for artist and album art. The music on the phone is read on the phone.")
            }
        }
        item {
            Section("X", "About") {
                Spacer(Modifier.height(16.dp))
                Txt("AETHER 0.1", Type.TitleSmall)
                Paragraph(
                    "Free software under the GNU GPL v3. Built on PODIUM's, LastWave's, BitChord's and NewPipe's work, Media3, Coil and Kyant's Backdrop; set in Pinyon Script, EB Garamond, Montserrat and JetBrains Mono (SIL Open Font License).",
                    top = 6.dp,
                )
            }
        }
        item(key = "colophon") { Colophon() }
    }
    if (adding) NameDialog("Source address", "https://", onDone = { address ->
        adding = false
        scope.launch {
            when (val r = AddonSource.connect(address, container.http)) {
                is Outcome.Ok -> {
                    container.sources.register(r.value)
                    container.settings.addAddon(address)
                    addError = null
                }
                is Outcome.Err -> addError = "That address didn't answer as a source."
            }
        }
    }, onDismiss = { adding = false })
}

/** A numbered head: the numeral in italic, the name in spaced capitals, a hairline beneath. */
@Composable
private fun Section(number: String, title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.separate().fillMaxWidth().padding(horizontal = Space.edge).padding(top = 48.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Txt(number, Type.Aside.copy(fontSize = 15.sp, lineHeight = 18.sp), color = Ink.Dim)
            Spacer(Modifier.width(12.dp))
            Txt(title, Type.Label, color = Ink.Grey, upper = true)
        }
        Spacer(Modifier.height(12.dp))
        Rule()
        content()
    }
}

@Composable
private fun Rule() {
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(Ink.Hairline))
}

@Composable
private fun Words(title: String, detail: String?, modifier: Modifier = Modifier) {
    Column(modifier) {
        Txt(title, Type.TitleSmall)
        if (detail != null) {
            Spacer(Modifier.height(4.dp))
            Txt(detail, Type.Meta, color = Ink.Grey)
        }
    }
}

/** On or off: the words, and at the right a switch of the tab bar's glass whose lit drop slides to the choice. */
@Composable
private fun Toggle(title: String, detail: String, on: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = false) { role = Role.Switch; stateDescription = if (on) "On" else "Off" }
            .padding(vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Words(title, detail, Modifier.weight(1f))
        Spacer(Modifier.width(16.dp))
        GlassSegments(listOf("Off", "On"), if (on) 1 else 0, { if ((it == 1) != on) onToggle() })
    }
    Rule()
}

/** One of a few: the words, then the choices on one bar of glass, the chosen one under the lit drop. */
@Composable
private fun Choice(title: String, detail: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 18.dp)) {
        Words(title, detail)
        Spacer(Modifier.height(16.dp))
        GlassSegments(options, selected, onSelect)
    }
    Rule()
}

/**
 * Something you can wipe. Nothing goes on the first touch: the line asks you to touch again, for a
 * few seconds, and only that second touch clears it.
 */
@Composable
private fun Erase(title: String, detail: String, onErase: () -> Unit) {
    var armed by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    LaunchedEffect(armed) { if (armed) { delay(3500); armed = false } }
    LaunchedEffect(done) { if (done) { delay(2200); done = false } }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Words(title, detail, Modifier.weight(1f))
        Spacer(Modifier.width(16.dp))
        GlassPill(
            when { done -> "Cleared"; armed -> "Touch again"; else -> "Clear" },
            small = true,
            lit = armed,
            icon = if (done) Glyph.Check else null,
            onClick = { if (armed) { onErase(); armed = false; done = true } else if (!done) armed = true },
        )
    }
    Rule()
}

@Composable
private fun Paragraph(text: String, top: androidx.compose.ui.unit.Dp = 16.dp) {
    Txt(text, Type.Body.copy(lineHeight = 21.sp), color = Ink.Grey, modifier = Modifier.padding(top = top, bottom = 4.dp))
}
