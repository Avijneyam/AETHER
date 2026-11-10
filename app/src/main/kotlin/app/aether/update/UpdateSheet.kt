package app.aether.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aether.core.designsystem.component.Glyph
import app.aether.core.designsystem.component.GridDots
import app.aether.core.designsystem.component.Icon
import app.aether.core.designsystem.component.LightCross
import app.aether.core.designsystem.component.Txt
import app.aether.core.designsystem.component.quietClick
import app.aether.core.designsystem.theme.Ink
import app.aether.core.designsystem.theme.Motion
import app.aether.core.designsystem.theme.Space
import app.aether.core.designsystem.theme.Type
import androidx.compose.animation.core.animateFloatAsState

/** Sizes as people read them: "44 MB", "850 KB". */
internal fun megabytes(bytes: Long): String =
    if (bytes >= 1024 * 1024) "${(bytes + 512 * 1024) / (1024 * 1024)} MB" else "${(bytes + 512) / 1024} KB"

/**
 * What the update check has to say, on a sheet of the glass every menu is made of: a strapline, the
 * version in the serif, the release's own words in short, and what can be done, each a line of the
 * engraved capitals over a hairline as in a song's menu. A download draws a hairline filling with
 * light, as the keep ring does.
 */
@Composable
fun UpdateSheet(updates: UpdateCenter) {
    val state by updates.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    fun github(release: Release?) = Action(Glyph.Share, "Open on GitHub") {
        open(context, release?.pageUrl ?: UpdateCenter.RELEASES_PAGE)
        updates.dismiss()
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Space.edge, vertical = 14.dp),
    ) {
        when (val s = state) {
            UpdateState.Idle, UpdateState.Checking -> {
                Heading("Software update", "Looking for a newer AETHER", listOf("Asking GitHub for the newest release. Nothing about you goes with it."))
                Box(Modifier.fillMaxWidth().padding(top = 28.dp), contentAlignment = Alignment.Center) { GridDots() }
            }
            is UpdateState.UpToDate -> {
                Heading("Software update", "AETHER is up to date", listOf("You have the newest version, ${s.current}."))
                Actions(listOf(Action(Glyph.Check, "Done") { updates.dismiss() }))
            }
            is UpdateState.Offer -> {
                Heading("A new edition", "AETHER ${s.release.version}", s.highlights.ifEmpty { listOf("A newer version is out.") }, changes = s.highlights.isNotEmpty())
                Actions(
                    listOfNotNull(
                        if (s.canInstall) {
                            val size = s.release.apk?.size?.takeIf { it > 0 }?.let { "  ·  ${megabytes(it)}" }.orEmpty()
                            Action(Glyph.Download, "Download and install$size") { updates.install() }
                        } else null,
                        github(s.release),
                        Action(Glyph.Close, "Not this version") { updates.ignore() },
                    ),
                )
                if (!s.canInstall && s.release.apk != null) {
                    Note("This copy of AETHER is a test build, so the release installs from its page on GitHub.")
                }
            }
            is UpdateState.Downloading -> {
                val progress = s.total?.let { (s.done.toFloat() / it).coerceIn(0f, 1f) } ?: 0f
                Heading(
                    "Downloading",
                    "AETHER ${s.release.version}",
                    listOf(s.total?.let { "${megabytes(s.done)} of ${megabytes(it)}" } ?: megabytes(s.done), "Keep listening: pull this away, and AETHER asks again when it's ready."),
                )
                Filling(progress)
                Actions(listOf(Action(Glyph.Close, "Stop") { updates.cancel() }))
            }
            is UpdateState.Verifying -> {
                Heading("Checking the download", "AETHER ${s.release.version}", listOf("Making sure it's the release, and signed like your AETHER."))
                Filling(1f)
            }
            is UpdateState.NeedsPermission -> {
                Heading("One more step", "Allow AETHER to install updates", listOf("Android asks once. Turn on Allow from this source, then come back to AETHER."))
                Actions(listOf(Action(Glyph.Forward, "Open Android settings") { openSettings(context, updates) }, github(s.release)))
            }
            is UpdateState.Installing -> {
                Heading("Installing", "AETHER ${s.release.version}", listOf("Android asks you to confirm. AETHER closes while it updates; your library stays as it is."))
            }
            is UpdateState.Failed -> {
                val (title, line) = when (s.problem) {
                    UpdateProblem.NO_CONNECTION -> "Couldn't look for updates" to "AETHER couldn't reach GitHub. Check your connection and try again."
                    UpdateProblem.RATE_LIMITED -> "Couldn't look for updates" to "GitHub asked for fewer checks from this network. Try again in an hour."
                    UpdateProblem.NOTHING_TO_DOWNLOAD -> "Nothing to download here" to "This release has no app attached. Get it from its page on GitHub."
                    UpdateProblem.DOWNLOAD -> "The download stopped" to "It didn't finish. Check your connection and try again, or get it from GitHub."
                    UpdateProblem.CORRUPT -> "The download doesn't match" to "It isn't the file the release lists, so it wasn't installed. Try again, or get it from GitHub."
                    UpdateProblem.OTHER_APP -> "This can't replace your AETHER" to "It isn't signed like the AETHER on your phone, so Android won't install it over it. Get it from GitHub instead."
                    UpdateProblem.NOT_NEWER -> "Nothing newer to install" to "The download isn't newer than your AETHER."
                    UpdateProblem.INSTALL -> "The update didn't install" to (s.detail?.let { "Android said: ${it.take(120)}. " }.orEmpty() + "Try again, or get it from GitHub.")
                }
                Heading("Software update", title, listOf(line))
                val retry = when (s.problem) {
                    UpdateProblem.NO_CONNECTION, UpdateProblem.RATE_LIMITED -> Action(Glyph.Repeat, "Try again") { updates.checkNow() }
                    UpdateProblem.DOWNLOAD, UpdateProblem.CORRUPT, UpdateProblem.INSTALL -> Action(Glyph.Repeat, "Try again") { updates.install() }
                    else -> null
                }
                Actions(listOfNotNull(retry, github(s.release), Action(Glyph.Close, "Close") { updates.dismiss() }))
            }
        }
    }
}

private class Action(val glyph: Glyph, val label: String, val onClick: () -> Unit)

/**
 * The strapline, the version or what happened in the serif, and the words beneath. A release's
 * [changes] are set one to a line behind a dash, its summary (which opens with its name) as prose.
 */
@Composable
private fun Heading(strap: String, title: String, lines: List<String>, changes: Boolean = false) {
    Row(Modifier.padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        LightCross(size = 22.dp, intensity = 0.8f)
        Spacer(Modifier.width(12.dp))
        Txt(strap, Type.Label, color = Ink.Grey, upper = true)
    }
    Txt(title, Type.Display, modifier = Modifier.padding(start = 4.dp, top = 14.dp, end = 4.dp))
    Spacer(Modifier.height(10.dp))
    lines.forEachIndexed { i, line ->
        val prose = !changes || (i == 0 && line.startsWith("AETHER", ignoreCase = true))
        Row(Modifier.padding(horizontal = 4.dp, vertical = 3.dp)) {
            if (!prose) Txt("—", Type.Body, color = Ink.Dim, modifier = Modifier.width(22.dp))
            Txt(line, Type.Body, color = Ink.Grey)
        }
    }
}

@Composable
private fun Note(text: String) {
    Txt(text, Type.Meta, color = Ink.Dim, modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 18.dp))
}

/** What can be done: as a song's menu sets its entries. */
@Composable
private fun Actions(actions: List<Action>) {
    Spacer(Modifier.height(18.dp))
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(Ink.Hairline))
    actions.forEach { a ->
        Row(Modifier.fillMaxWidth().quietClick(a.onClick).padding(horizontal = 4.dp, vertical = 17.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(a.glyph, size = 17.dp, color = Ink.Grey)
            Spacer(Modifier.width(20.dp))
            Txt(a.label, Type.TitleSmall, maxLines = 1)
        }
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(Ink.Hairline))
    }
}

/** A hairline that fills with light as the download comes in, its leading point brightest. */
@Composable
private fun Filling(progress: Float) {
    val shown by animateFloatAsState(progress, Motion.fluid(0.5f, 1f, 0.001f), label = "filling")
    Canvas(Modifier.fillMaxWidth().padding(horizontal = 4.dp).padding(top = 22.dp).height(8.dp)) {
        val y = size.height / 2f
        drawLine(Ink.White.copy(alpha = 0.14f), Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx(), cap = StrokeCap.Round)
        val x = size.width * shown.coerceIn(0f, 1f)
        if (x > 0.5f) {
            drawLine(Ink.White.copy(alpha = 0.12f), Offset(0f, y), Offset(x, y), strokeWidth = 6.dp.toPx(), cap = StrokeCap.Round)
            drawLine(Ink.White, Offset(0f, y), Offset(x, y), strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round)
            drawCircle(Ink.White, radius = 2.5.dp.toPx(), center = Offset(x, y))
        }
    }
}

private fun open(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // No browser: nothing to open it in.
    }
}

private fun openSettings(context: Context, updates: UpdateCenter) {
    try {
        context.startActivity(updates.permissionPage().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // A phone without the page: the update can still be had from GitHub.
    }
}
