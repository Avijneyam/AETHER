// The way through the folder tree is PODIUM's (feature/settings/MusicFolders.kt, GPL-3.0), for the touch screen.
package app.aether.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.aether.core.designsystem.component.Glyph
import app.aether.core.designsystem.component.Icon
import app.aether.core.designsystem.component.Loading
import app.aether.core.designsystem.component.Notice
import app.aether.core.designsystem.component.PillToggle
import app.aether.core.designsystem.component.Txt
import app.aether.core.designsystem.component.quietClick
import app.aether.core.designsystem.theme.Ink
import app.aether.core.designsystem.theme.Space
import app.aether.core.designsystem.theme.Type
import app.aether.core.model.songCount
import app.aether.core.model.FolderSelection
import app.aether.core.model.foldersInside
import app.aether.core.model.songsIncluded
import app.aether.ui.LocalActions
import app.aether.ui.Route
import app.aether.ui.common.Colophon
import app.aether.ui.common.LocalContainer
import app.aether.ui.common.rememberPageListState
import app.aether.ui.detail.BackLine
import app.aether.ui.library.rememberPhoneMusic

/**
 * Which of the phone's folders AETHER reads, one level at a time. A folder with nothing but songs in
 * it has a switch. A folder with others inside has the square, which chooses it with everything in
 * it, and opens on the rest of its row; the square can show that only part of it is read.
 */
@Composable
fun MusicFoldersScreen(path: String) {
    val container = LocalContainer.current
    val actions = LocalActions.current
    val music = rememberPhoneMusic()
    val here = FolderSelection.normalize(path)
    val root = here.isEmpty()
    val folders = music.folders
    val selection = music.selection
    val children = remember(folders, here) { foldersInside(here, folders.orEmpty()) }
    val toggle: (String) -> Unit = { container.local.select(selection.toggled(it)) }
    LazyColumn(Modifier.fillMaxSize(), state = rememberPageListState(), contentPadding = PaddingValues(bottom = 0.dp)) {
        item { BackLine() }
        item {
            val name = if (root) "Music folders" else here.removeSuffix("/").substringAfterLast('/')
            Column(Modifier.fillMaxWidth().padding(horizontal = Space.edge + 12.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Txt(name, Type.name(name), align = TextAlign.Center)
                if (root) {
                    Spacer(Modifier.height(14.dp))
                    Txt("Only the folders chosen here are read, and nothing leaves the phone.", Type.Aside, color = Ink.Grey, align = TextAlign.Center)
                }
                Spacer(Modifier.height(26.dp))
            }
        }
        when {
            !music.allowed -> item {
                Notice(
                    "Your own music",
                    if (music.asked) "Allow AETHER to read your audio in the phone's settings, under Apps." else "AETHER needs permission to read your audio before it can show your folders.",
                    action = if (music.asked) null else "Allow",
                    onAction = music.ask,
                )
            }
            folders == null -> item { Loading() }
            folders.isEmpty() -> item { Notice("No music on this phone", "Folders that hold songs show here.") }
            else -> {
                if (root) item(key = "all") {
                    FolderChoice(
                        name = "All folders",
                        detail = if (selection == FolderSelection.Everything) songCount(folders.sumOf { it.songCount }) else "${songCount(songsIncluded(folders, selection))} chosen",
                        on = selection == FolderSelection.Everything,
                        mixed = selection != FolderSelection.Everything,
                        first = true,
                        onChoose = { container.local.select(FolderSelection.Everything) },
                        onOpen = null,
                        leaf = false,
                    )
                } else item(key = "here") {
                    FolderChoice(
                        name = "All of ${here.removeSuffix("/").substringAfterLast('/')}",
                        detail = null,
                        on = selection.includes(here),
                        mixed = selection.isMixedBelow(here),
                        first = true,
                        onChoose = { toggle(here) },
                        onOpen = null,
                        leaf = false,
                    )
                }
                items(children, key = { it.path }) { node ->
                    FolderChoice(
                        name = node.name,
                        detail = songCount(node.songCount),
                        on = selection.includes(node.path),
                        mixed = selection.isMixedBelow(node.path),
                        first = false,
                        onChoose = { toggle(node.path) },
                        onOpen = if (node.hasSubfolders) ({ actions.nav.open(Route.MusicFolders(node.path)) }) else null,
                        leaf = !node.hasSubfolders,
                    )
                }
            }
        }
        item(key = "colophon") { Colophon() }
    }
}

@Composable
private fun FolderChoice(name: String, detail: String?, on: Boolean, mixed: Boolean, first: Boolean, onChoose: () -> Unit, onOpen: (() -> Unit)?, leaf: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .quietClick { if (onOpen != null) onOpen() else onChoose() }
            .drawBehind {
                if (!first) {
                    val inset = Space.edge.toPx()
                    drawLine(Ink.White.copy(alpha = 0.16f), Offset(inset, 0f), Offset(size.width - inset, 0f), strokeWidth = 0.5.dp.toPx())
                }
            }
            .padding(start = Space.edge - 16.dp, end = Space.edge),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A folder with a switch keeps this column empty, so that every name stands in one line.
        if (leaf) Spacer(Modifier.size(56.dp))
        else Box(
            Modifier
                .size(56.dp)
                .semantics {
                    role = Role.Checkbox
                    stateDescription = if (on) "Read" else "Skipped"
                }
                .quietClick(onChoose),
            contentAlignment = Alignment.Center,
        ) { ChoiceBox(on, mixed) }
        Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
            Txt(name, Type.TitleSmall, maxLines = 1)
            if (detail != null) Txt(detail, Type.Meta, color = Ink.Grey, maxLines = 1)
        }
        if (onOpen != null) {
            Spacer(Modifier.width(12.dp))
            Icon(Glyph.Forward, size = 13.dp, color = Ink.Dim)
        }
        if (leaf) {
            Spacer(Modifier.width(12.dp))
            PillToggle(on, onChoose, name)
        }
    }
}

/** A small square: filled when read, empty when skipped, half when some of it is read. */
@Composable
private fun ChoiceBox(on: Boolean, mixed: Boolean) {
    Canvas(Modifier.size(18.dp)) {
        val r = CornerRadius(4.dp.toPx())
        drawRoundRect(if (on || mixed) Ink.White else Ink.Dim, cornerRadius = r, style = Stroke(1.5.dp.toPx()))
        val inset = 4.dp.toPx()
        val inner = Size(size.width - inset * 2, size.height - inset * 2)
        when {
            on && !mixed -> drawRoundRect(Ink.White, Offset(inset, inset), inner, CornerRadius(r.x / 2))
            mixed -> drawRoundRect(Ink.White, Offset(inset, inset), Size(inner.width / 2, inner.height), CornerRadius(r.x / 2))
            else -> Unit
        }
    }
}
