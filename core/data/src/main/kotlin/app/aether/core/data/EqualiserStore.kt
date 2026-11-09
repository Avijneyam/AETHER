package app.aether.core.data

import android.content.Context
import app.aether.core.model.EqFilter
import app.aether.core.model.EqKind
import app.aether.core.model.EqProfile
import app.aether.core.model.EqShape
import app.aether.core.model.EqTunings
import app.aether.core.model.EqText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * The equaliser's state: on or off, which profile, and the ones the listener has brought in. The
 * headphones are AutoEq's published corrections (`assets/eq_headphones.json`, see NOTICES).
 */
class EqualiserStore(private val context: Context, scope: CoroutineScope) {
    private val prefs = context.getSharedPreferences("aether_equaliser", Context.MODE_PRIVATE)

    private val _on = MutableStateFlow(prefs.getBoolean(ON, false))
    val on: StateFlow<Boolean> = _on.asStateFlow()

    private val _imported = MutableStateFlow(readImported())
    val imported: StateFlow<List<EqProfile>> = _imported.asStateFlow()

    private val _headphones = MutableStateFlow<List<EqProfile>>(emptyList())
    val headphones: StateFlow<List<EqProfile>> = _headphones.asStateFlow()

    private val _chosenId = MutableStateFlow(prefs.getString(CHOSEN, null) ?: EqTunings.Flat.id)

    /** The chosen one, Flat if what was chosen has gone. */
    val chosen: StateFlow<EqProfile> = combine(_chosenId, _imported, _headphones) { id, mine, phones ->
        EqTunings.All.firstOrNull { it.id == id } ?: mine.firstOrNull { it.id == id } ?: phones.firstOrNull { it.id == id } ?: EqTunings.Flat
    }.stateIn(scope, SharingStarted.Eagerly, EqTunings.Flat)

    /** What the music should go through; null when nothing should touch it. */
    val shaping: StateFlow<EqProfile?> = combine(_on, chosen) { on, profile -> profile.takeIf { on && it.shaping } }
        .stateIn(scope, SharingStarted.Eagerly, null)

    init {
        scope.launch(Dispatchers.IO) { _headphones.value = readCatalogue() }
    }

    fun setOn(on: Boolean) {
        _on.value = on
        prefs.edit().putBoolean(ON, on).apply()
    }

    /** Takes [id] and turns the equaliser on: choosing something means wanting to hear it. */
    fun choose(id: String) {
        _chosenId.value = id
        prefs.edit().putString(CHOSEN, id).apply()
        setOn(true)
    }

    /** Brings in [text] under [name] and chooses it. Null if it isn't a profile. */
    fun import(name: String, text: String): EqProfile? {
        val filters = EqText.parse(text)?.takeIf { it.isNotEmpty() } ?: return null
        val profile = EqProfile("imported:${UUID.randomUUID()}", name.ifBlank { "Imported" }, EqKind.Imported, filters, detail = "${filters.size} filters")
        _imported.value = _imported.value + profile
        writeImported()
        choose(profile.id)
        return profile
    }

    fun remove(id: String) {
        _imported.value = _imported.value.filter { it.id != id }
        writeImported()
    }

    private fun filtersOf(rows: JSONArray): List<EqFilter> = List(rows.length()) { i ->
        val row = rows.getJSONArray(i)
        val shape = when (row.getString(0)) {
            "LSC" -> EqShape.LowShelf
            "HSC" -> EqShape.HighShelf
            "LP" -> EqShape.LowPass
            "HP" -> EqShape.HighPass
            else -> EqShape.Peak
        }
        EqFilter(shape, row.getDouble(1).toFloat(), row.getDouble(2).toFloat(), row.getDouble(3).toFloat())
    }

    private fun readCatalogue(): List<EqProfile> = runCatching {
        val rows = JSONArray(context.assets.open("eq_headphones.json").bufferedReader().use { it.readText() })
        List(rows.length()) { i ->
            val o = rows.getJSONObject(i)
            val brand = o.getString("b")
            val name = o.getString("n")
            EqProfile("phones:$brand $name", name, EqKind.Headphones, filtersOf(o.getJSONArray("f")), group = brand, detail = "Measured by ${o.getString("by")}")
        }
    }.getOrDefault(emptyList())

    private fun readImported(): List<EqProfile> = runCatching {
        val rows = JSONArray(prefs.getString(IMPORTED, "[]"))
        List(rows.length()) { i ->
            val o = rows.getJSONObject(i)
            val filters = filtersOf(o.getJSONArray("f"))
            EqProfile(o.getString("id"), o.getString("name"), EqKind.Imported, filters, detail = "${filters.size} filters")
        }
    }.getOrDefault(emptyList())

    private fun writeImported() {
        val rows = JSONArray()
        _imported.value.forEach { p ->
            val filters = JSONArray()
            p.filters.forEach { f ->
                val code = when (f.shape) {
                    EqShape.Peak -> "PK"
                    EqShape.LowShelf -> "LSC"
                    EqShape.HighShelf -> "HSC"
                    EqShape.LowPass -> "LP"
                    EqShape.HighPass -> "HP"
                }
                filters.put(JSONArray().put(code).put(f.hz.toDouble()).put(f.gainDb.toDouble()).put(f.q.toDouble()))
            }
            rows.put(JSONObject().put("id", p.id).put("name", p.name).put("f", filters))
        }
        prefs.edit().putString(IMPORTED, rows.toString()).apply()
    }

    private companion object {
        const val ON = "on"
        const val CHOSEN = "chosen"
        const val IMPORTED = "imported"
    }
}
