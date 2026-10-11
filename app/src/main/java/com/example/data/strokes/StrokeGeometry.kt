package com.example.data.strokes

import android.content.Context
import org.json.JSONObject

/**
 * One stroke's geometry: its filled outline path plus the median polyline.
 *
 * @param pathData SVG path data (`M/L/Q/C/Z`) in the dataset's coordinate space, filled
 *   (`Z`-closed) outlines, not centerlines. Rendered as-is, never edited.
 * @param median the stroke's centerline as [x, y] points, in drawing order. Used only to
 *   reason about a stroke (length estimates); the visible animation follows [pathData].
 */
data class StrokeGeometry(
    val pathData: String,
    val median: List<StrokePoint>,
)

data class StrokePoint(val x: Float, val y: Float)

/**
 * A character's verified stroke geometry: one entry per stroke, in writing order.
 *
 * The list position *is* the stroke order - entry `i` is drawn `i`-th - so a caller
 * that preserves order cannot mis-sequence, and one that reorders has to do it loudly.
 */
data class CharacterGeometry(
    val character: String,
    val strokes: List<StrokeGeometry>,
) {
    val strokeCount: Int get() = strokes.size
}

/** Asset file holding [character]'s data, or null when [hanzi] is not one character. */
fun strokeAssetName(hanzi: String): String? {
    if (hanzi.codePointCount(0, hanzi.length) != 1) return null
    return "strokes/%04x.json".format(hanzi.codePointAt(0))
}

/**
 * Characters with bundled, verified stroke data. Everything else reports "unavailable"
 * rather than a guess.
 */
val SUPPORTED_STROKE_CHARACTERS: Set<String> = setOf(
    "你", "好", "我", "是", "人", "中", "国", "学", "生", "老", "师",
)

/**
 * Validates decoded stroke data into [CharacterGeometry], or returns null.
 *
 * Pure: takes already-decoded lists so this rule is unit-testable on the JVM without
 * Android's JSON classes. Null covers every failure the same way - wrong sizes, blank
 * paths, short medians, non-numeric points - because the caller has exactly one honest
 * response to all of them: the "unavailable" state.
 */
fun parseStrokeData(
    character: String,
    strokes: List<String>,
    medians: List<List<Pair<Double, Double>>>,
): CharacterGeometry? {
    if (strokes.isEmpty() || strokes.size != medians.size) return null
    val parsed = strokes.zip(medians).map { (pathData, median) ->
        if (pathData.isBlank() || !pathData.trimStart().startsWith('M')) return null
        if (!pathData.all { it in "MLQCZmlqcz0123456789eE.,+ \t-" }) return null
        if (median.size < 2) return null
        StrokeGeometry(
            pathData = pathData,
            median = median.map { (x, y) -> StrokePoint(x.toFloat(), y.toFloat()) },
        )
    }
    return CharacterGeometry(character = character, strokes = parsed)
}

/**
 * Parses one bundled JSON document into [CharacterGeometry], or null when it is missing
 * or malformed.
 *
 * The document shape is the hanzi-writer-data schema: `{"strokes": [...],
 * "medians": [[...], ...]}`. Anything else - including a valid JSON document that is
 * not this schema - is "unavailable", never an exception to the caller.
 */
fun parseStrokeJson(character: String, json: String): CharacterGeometry? {
    return runCatching {
        val root = JSONObject(json)
        val strokesJson = root.optJSONArray("strokes") ?: return null
        val mediansJson = root.optJSONArray("medians") ?: return null
        if (strokesJson.length() != mediansJson.length()) return null
        val strokes = List(strokesJson.length()) { i -> strokesJson.optString(i, "") }
        val medians = List(mediansJson.length()) { i ->
            val points = mediansJson.optJSONArray(i) ?: return null
            List(points.length()) { j ->
                val point = points.optJSONArray(j) ?: return null
                point.optDouble(0, Double.NaN) to point.optDouble(1, Double.NaN)
            }
        }
        if (medians.any { points -> points.any { (x, y) -> x.isNaN() || y.isNaN() } }) return null
        parseStrokeData(character, strokes, medians)
    }.getOrNull()
}

/**
 * Loads bundled stroke geometry, memorised per character.
 *
 * Asset reads are small (under 3 KB) and cached after the first, so the review card can
 * call [load] during composition without a loader ceremony. A missing file, an
 * unreadable file and a malformed document all answer null: the section renders its
 * "unavailable" state for all three, because from the learner's side they are the
 * same fact - this character has no drawable strokes here.
 */
class StrokeGeometryStore(context: Context) {

    private val assets = context.applicationContext.assets
    private val cache = mutableMapOf<String, CharacterGeometry?>()

    fun load(hanzi: String): CharacterGeometry? {
        if (hanzi.codePointCount(0, hanzi.length) != 1) return null
        return cache.getOrPut(hanzi) { readBundled(hanzi) }
    }

    private fun readBundled(hanzi: String): CharacterGeometry? {
        val name = strokeAssetName(hanzi) ?: return null
        return runCatching {
            assets.open(name).bufferedReader(Charsets.UTF_8).use { reader ->
                parseStrokeJson(hanzi, reader.readText())
            }
        }.getOrNull()
    }
}
