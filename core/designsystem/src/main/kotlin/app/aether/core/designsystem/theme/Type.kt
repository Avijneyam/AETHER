package app.aether.core.designsystem.theme

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.aether.core.designsystem.R

/*
 * A jeweller's house style, in open-licence faces (SIL OFL), the same on every page: a copperplate
 * script (Pinyon Script) for the wordmark and the headings, as a maison signs its name; a book
 * Garamond (EB Garamond) for anything read, and its italic for asides; and a wide, engraved-looking
 * grotesk (Montserrat) for everything functional, its capitals spaced like an engraver's. A mono
 * (JetBrains Mono) only where characters are pictures: ASCII artwork and microtype.
 */

@OptIn(ExperimentalTextApi::class)
private fun face(resId: Int, weight: Int, italic: Boolean = false) = Font(
    resId = resId,
    weight = FontWeight(weight),
    style = if (italic) FontStyle.Italic else FontStyle.Normal,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/** The script has one weight; its letters join, so it's never spaced. */
val Script = FontFamily(androidx.compose.ui.text.font.Font(R.font.pinyon_script, FontWeight.W400))

val Serif = FontFamily(
    face(R.font.eb_garamond, 400), face(R.font.eb_garamond, 500), face(R.font.eb_garamond, 600),
    // The heaviest two only for words sung (Verse), where a lit word has to read at a glance.
    face(R.font.eb_garamond, 700), face(R.font.eb_garamond, 800),
    face(R.font.eb_garamond_italic, 400, italic = true), face(R.font.eb_garamond_italic, 500, italic = true),
)

val Sans = FontFamily(face(R.font.montserrat, 400), face(R.font.montserrat, 500), face(R.font.montserrat, 600))
val Mono = FontFamily(face(R.font.jetbrains_mono, 400), face(R.font.jetbrains_mono, 700))

private val Tight = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)

object Type {
    /** The page's own name, dramatically large: "Listen", "Library", "Now Playing". */
    val Monument = TextStyle(fontFamily = Script, fontWeight = FontWeight.W400, fontSize = 74.sp, lineHeight = 80.sp, lineHeightStyle = Tight)

    /** A section's name, as a magazine heads a page: the book serif, large, the script kept for page names. */
    val Display = TextStyle(fontFamily = Serif, fontWeight = FontWeight.W400, fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = (-0.005).em, lineHeightStyle = Tight)

    /** An album's name on its own page: the page's name, so in the script, smaller than a room's. */
    val Headline = TextStyle(fontFamily = Script, fontWeight = FontWeight.W400, fontSize = 40.sp, lineHeight = 46.sp, lineHeightStyle = Tight)

    /**
     * The style for a page's own name in [script]. A script's capitals are made to begin words, and a
     * name set all in capitals (JENNIE, BTS) is a tangle of swashes in it, so that goes in the serif's
     * capitals instead, spaced, as a masthead is.
     */
    fun name(name: String, script: TextStyle = Monument): TextStyle {
        val letters = name.filter(Char::isLetter)
        val capitals = letters.any(Char::isUpperCase) && letters.none(Char::isLowerCase)
        if (!capitals) return script
        return script.copy(
            fontFamily = Serif,
            fontWeight = FontWeight.W400,
            fontSize = script.fontSize * 0.6f,
            lineHeight = script.lineHeight * 0.72f,
            letterSpacing = 0.1.em,
        )
    }

    /** The italic aside: "a music sanctuary", a strapline in prose. */
    val Aside = TextStyle(fontFamily = Serif, fontStyle = FontStyle.Italic, fontWeight = FontWeight.W400, fontSize = 18.sp, lineHeight = 24.sp)

    /** A line read as a title: a setting, a menu's entry, a lyric. */
    val Title = TextStyle(fontFamily = Serif, fontWeight = FontWeight.W500, fontSize = 17.5.sp, lineHeight = 22.sp)

    /**
     * A thing's name, as a jeweller captions a piece: in the wide grotesk's capitals (its OpenType
     * `case` forms, which [app.aether.core.designsystem.component.Txt] reads as "set in capitals"),
     * with what it is beneath in the serif.
     */
    val TitleSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.W600, fontSize = 12.5.sp, lineHeight = 17.sp, letterSpacing = 0.05.em, fontFeatureSettings = "case")

    /** What a thing is, who made it, details: the book serif, as beneath a jeweller's caption. */
    val Body = TextStyle(fontFamily = Serif, fontWeight = FontWeight.W400, fontSize = 15.5.sp, lineHeight = 20.sp)
    val Meta = TextStyle(fontFamily = Serif, fontWeight = FontWeight.W400, fontSize = 13.5.sp, lineHeight = 18.sp)

    /** Spaced capitals: straplines, labels, the controls' words. The face is wide, so it needs less space. */
    val Label = TextStyle(fontFamily = Sans, fontWeight = FontWeight.W500, fontSize = 10.sp, lineHeight = 14.sp, letterSpacing = 0.14.em)
    val LabelLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.W500, fontSize = 11.5.sp, lineHeight = 16.sp, letterSpacing = 0.12.em)

    /** The wordmark: "Aether", signed in the script. */
    val Inscription = TextStyle(fontFamily = Script, fontWeight = FontWeight.W400, fontSize = 50.sp, lineHeight = 56.sp)

    /** Characters as pictures, and the smallest editorial microtype. */
    val Micro = TextStyle(fontFamily = Mono, fontWeight = FontWeight.W400, fontSize = 7.sp, lineHeight = 9.sp, letterSpacing = 0.06.em)
}
