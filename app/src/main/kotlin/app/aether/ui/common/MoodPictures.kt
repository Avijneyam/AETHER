package app.aether.ui.common

import android.content.Context
import app.aether.R

/**
 * The photograph a mood or genre's tile shows. The catalogue gives these tiles a name and a colour;
 * AETHER has no use for the colour and gives each a picture of its own, already in black and white.
 *
 * Going by the name is the only way: a tile's address is opaque, and its name is all that says what
 * it is. A name with no picture of its own (a new mood, or the catalogue in another language) borrows
 * one, always the same one for that name, and nothing depends on any of this but how a tile looks.
 */
object MoodPictures {
    /** Tile names, as the catalogue spells them in lower case, to the name of their picture. */
    private val slugs = mapOf(
        "chill" to "chill",
        "commute" to "commute",
        "diwali" to "diwali",
        "energize" to "energize",
        "energise" to "energize",
        "feel good" to "feel_good",
        "focus" to "focus",
        "gaming" to "gaming",
        "party" to "party",
        "romance" to "romance",
        "sad" to "sad",
        "sleep" to "sleep",
        "workout" to "workout",
        "african" to "african",
        "arabic" to "arabic",
        "bengali" to "bengali",
        "bhojpuri" to "bhojpuri",
        "carnatic classical" to "carnatic_classical",
        "classical" to "classical",
        "country & americana" to "country",
        "dance & electronic" to "dance",
        "decades" to "decades",
        "desi hip-hop" to "desi_hip_hop",
        "devotional" to "devotional",
        "family" to "family",
        "folk & acoustic" to "folk",
        "ghazal/sufi" to "ghazal",
        "gujarati" to "gujarati",
        "haryanvi" to "haryanvi",
        "hindi" to "hindi",
        "hindustani classical" to "hindustani_classical",
        "hip-hop" to "hip_hop",
        "indian indie" to "indian_indie",
        "indian pop" to "indian_pop",
        "indie & alternative" to "indie",
        "j-pop" to "j_pop",
        "jazz" to "jazz",
        "k-pop" to "k_pop",
        "kannada" to "kannada",
        "latin" to "latin",
        "malayalam" to "malayalam",
        "marathi" to "marathi",
        "metal" to "metal",
        "monsoon" to "monsoon",
        "pop" to "pop",
        "punjabi" to "punjabi",
        "r&b & soul" to "rnb",
        "reggae & caribbean" to "reggae",
        "rock" to "rock",
        "tamil" to "tamil",
        "telugu" to "telugu",
        "new releases" to "new_releases",
        "charts" to "charts",
        "moods & genres" to "moods_genres",
        "podcasts" to "podcasts",
    )

    /** The pictures in the app, by name. */
    private val pictures: Map<String, Int> = mapOf(
        "party" to R.drawable.mood_party,
        "commute" to R.drawable.mood_commute,
        "diwali" to R.drawable.mood_diwali,
        "chill" to R.drawable.mood_chill,
        "energize" to R.drawable.mood_energize,
        "sleep" to R.drawable.mood_sleep,
        "workout" to R.drawable.mood_workout,
        "sad" to R.drawable.mood_sad,
        "romance" to R.drawable.mood_romance,
        "gaming" to R.drawable.mood_gaming,
        "focus" to R.drawable.mood_focus,
        "feel_good" to R.drawable.mood_feel_good,
        "podcasts" to R.drawable.mood_podcasts,
        "moods_genres" to R.drawable.mood_moods_genres,
        "charts" to R.drawable.mood_charts,
        "new_releases" to R.drawable.mood_new_releases,
        "hip_hop" to R.drawable.mood_hip_hop,
        "hindi" to R.drawable.mood_hindi,
        "haryanvi" to R.drawable.mood_haryanvi,
        "gujarati" to R.drawable.mood_gujarati,
        "ghazal" to R.drawable.mood_ghazal,
        "folk" to R.drawable.mood_folk,
        "family" to R.drawable.mood_family,
        "devotional" to R.drawable.mood_devotional,
        "decades" to R.drawable.mood_decades,
        "desi_hip_hop" to R.drawable.mood_desi_hip_hop,
        "dance" to R.drawable.mood_dance,
        "country" to R.drawable.mood_country,
        "classical" to R.drawable.mood_classical,
        "carnatic_classical" to R.drawable.mood_carnatic_classical,
        "hindustani_classical" to R.drawable.mood_hindustani_classical,
        "bhojpuri" to R.drawable.mood_bhojpuri,
        "bengali" to R.drawable.mood_bengali,
        "arabic" to R.drawable.mood_arabic,
        "african" to R.drawable.mood_african,
        "telugu" to R.drawable.mood_telugu,
        "tamil" to R.drawable.mood_tamil,
        "rock" to R.drawable.mood_rock,
        "reggae" to R.drawable.mood_reggae,
        "rnb" to R.drawable.mood_rnb,
        "punjabi" to R.drawable.mood_punjabi,
        "pop" to R.drawable.mood_pop,
        "monsoon" to R.drawable.mood_monsoon,
        "metal" to R.drawable.mood_metal,
        "marathi" to R.drawable.mood_marathi,
        "malayalam" to R.drawable.mood_malayalam,
        "latin" to R.drawable.mood_latin,
        "kannada" to R.drawable.mood_kannada,
        "k_pop" to R.drawable.mood_k_pop,
        "jazz" to R.drawable.mood_jazz,
        "j_pop" to R.drawable.mood_j_pop,
        "indie" to R.drawable.mood_indie,
        "indian_pop" to R.drawable.mood_indian_pop,
        "indian_indie" to R.drawable.mood_indian_indie,
    )

    /** The address of [title]'s picture, or null while the app has none at all. */
    fun of(context: Context, title: String): String? {
        val all = pictures
        if (all.isEmpty()) return null
        val name = title.trim().lowercase()
        val own = slugs[name]?.let { all[it] }
        val id = own ?: all.values.sorted().let { it[Math.floorMod(name.hashCode(), it.size)] }
        return "android.resource://${context.packageName}/$id"
    }
}
