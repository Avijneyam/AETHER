package app.aether.core.designsystem.component

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import app.aether.core.designsystem.theme.Ink
import app.aether.core.model.withoutEmoji

/** Text in AETHER's voice: no Material, colour always chosen. */
@Composable
fun Txt(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Ink.White,
    align: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
    upper: Boolean = false,
) {
    // A style asking for capitals' forms (`case`) is set in capitals.
    val caps = upper || style.fontFeatureSettings?.contains("case") == true
    BasicText(
        text = text.withoutEmoji().let { if (caps) it.uppercase() else it },
        modifier = modifier,
        style = style.copy(color = color, textAlign = align ?: style.textAlign),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * Touch you can feel, in four strengths, all off with one setting. Used where something happened,
 * never on every frame of a gesture.
 */
class Haptics(private val view: View?, private val enabled: () -> Boolean) {
    /** A row chosen, a tab changed. */
    fun select() = perform(HapticFeedbackConstants.CLOCK_TICK)

    /** A minor action: a filter, a toggle. */
    fun light() = perform(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)

    /** Play, pause, skip: the actions that matter most. */
    fun strong() = perform(HapticFeedbackConstants.LONG_PRESS)

    /** A slider passing a mark, a folder opening. */
    fun tick() = perform(if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_FREQUENT_TICK else HapticFeedbackConstants.CLOCK_TICK)

    private fun perform(kind: Int) {
        if (enabled()) view?.performHapticFeedback(kind)
    }
}

val LocalHaptics = staticCompositionLocalOf { Haptics(null) { false } }
