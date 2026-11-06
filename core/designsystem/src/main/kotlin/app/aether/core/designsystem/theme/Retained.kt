package app.aether.core.designsystem.theme

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Keeps this as a picture of itself: Android draws it once into a texture and reuses that until
 * something in it changes. A page scrolled, or the glass over it sampling it again, then costs one
 * quad here instead of every path and shader in it, on every frame. For what's small, costly to draw
 * and rarely changes: a folder's two paintings, a capsule on the moving ribbon, a print. Never for a
 * section or a page: these textures stay in graphics memory, which has a budget (see [separate]).
 *
 * It clips to its own bounds: nothing inside may paint past them. Not for what changes every frame.
 *
 * It keeps best with no other layers inside it. The glass samples the page by reference, so Android
 * meets the page's layers twice a frame, and on the second visit it can't tell what changed: a layer
 * with layers inside is drawn again, though only its own few lines, with those inside still kept.
 */
fun Modifier.retained(): Modifier = this then RetainedLayer

private val RetainedLayer = Modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }

/**
 * Gives this a drawing of its own, with no texture behind it: when something beside it changes, it
 * isn't recorded again, and when it moves, nothing in it is. For whole sections and pages, which are
 * too large to be [retained]: a texture for each of those, in every room, held graphics memory at
 * Android's limit (372 MB on this phone) with little of it free.
 */
fun Modifier.separate(): Modifier = this then SeparateLayer

private val SeparateLayer = Modifier.graphicsLayer { }
