package com.example.data.dictionary

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lazy-loaded, thread-safe offline dictionary.
 *
 * Reads from a JSON asset file on first access. Returns null/empty for unknown
 * words. Never throws — a load failure results in an empty dictionary.
 *
 * ## Thread safety
 * Kotlin's `by lazy` is thread-safe by default (SYNCHRONIZED mode).
 *
 * ## Asset format
 * ```json
 * {
 *   "source": "CC-CEDICT",
 *   "version": "2026-10-19",
 *   "entries": [
 *     { "h": "妈妈", "t": "媽媽", "p": "māma", "pn": "ma1 ma5", "m": "mama; mommy; mother", "l": 1 }
 *   ]
 * }
 * ```
 */
class OfflineDictionary(context: Context) {

    private val appContext = context.applicationContext

    private val data: DictionaryData by lazy {
        loadFromAssets()
    }

    /** All entries keyed by simplified Hanzi. */
    val entries: Map<String, DictionaryEntry>
        get() = data.entries

    /** Lookup by exact Hanzi. Returns null if not found. */
    fun lookupByHanzi(hanzi: String): DictionaryEntry? =
        data.entries[hanzi.trim()]

    /**
     * Lookup by Pinyin. Supports:
     * - Tone-marked Pinyin (e.g. "xuésheng", "māma")
     * - Numbered Pinyin (e.g. "xue2 sheng5", "ma1 ma5") — converted via PinyinConverter
     * - Untoned Pinyin (e.g. "xuesheng", "mama") — matched by stripping tone marks
     *
     * Input is trimmed, lowercased, and spaces are removed before lookup.
     */
    fun lookupByPinyin(pinyin: String): List<DictionaryEntry> {
        val trimmed = pinyin.trim()
        if (trimmed.isEmpty()) return emptyList()

        // Try numbered Pinyin conversion first (if input contains digits).
        if (trimmed.any { it.isDigit() }) {
            val converted = com.example.data.srs.PinyinConverter.fromNumbered(trimmed)
            if (converted != trimmed) {
                val normalized = converted.lowercase().replace(" ", "")
                data.pinyinIndex[normalized]?.let { return it }
            }
        }

        // Try direct tone-marked lookup.
        val normalized = trimmed.lowercase().replace(" ", "")
        data.pinyinIndex[normalized]?.let { return it }

        // Try untoned lookup: strip tone marks from input and compare.
        val untoned = stripTones(normalized)
        data.untonedIndex[untoned]?.let { return it }

        return emptyList()
    }

    private fun loadFromAssets(): DictionaryData {
        return try {
            val json = appContext.assets.open(ASSET_PATH).bufferedReader().use { it.readText() }
            val root = JSONObject(json)
            val entriesArray = root.optJSONArray("entries") ?: JSONArray()
            val entries = mutableMapOf<String, DictionaryEntry>()
            val pinyinIndex = mutableMapOf<String, MutableList<DictionaryEntry>>()
            val untonedIndex = mutableMapOf<String, MutableList<DictionaryEntry>>()

            for (i in 0 until entriesArray.length()) {
                val obj = entriesArray.optJSONObject(i) ?: continue
                val hanzi = obj.optString("h", "").trim()
                if (hanzi.isEmpty()) continue

                val entry = DictionaryEntry(
                    hanzi = hanzi,
                    traditional = obj.optString("t", "").trim(),
                    pinyin = obj.optString("p", "").trim(),
                    pinyinNumbered = obj.optString("pn", "").trim(),
                    meaning = obj.optString("m", "").trim(),
                    hskLevel = obj.optInt("l", 0),
                )

                entries[hanzi] = entry
                val normalizedPinyin = entry.pinyin.lowercase().replace(" ", "")
                pinyinIndex.getOrPut(normalizedPinyin) { mutableListOf() }.add(entry)

                // Build untoned index for tone-insensitive lookup.
                val untoned = stripTones(normalizedPinyin)
                untonedIndex.getOrPut(untoned) { mutableListOf() }.add(entry)
            }

            DictionaryData(entries, pinyinIndex, untonedIndex)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load dictionary asset: ${e.message}")
            DictionaryData(emptyMap(), emptyMap(), emptyMap())
        }
    }

    private data class DictionaryData(
        val entries: Map<String, DictionaryEntry>,
        val pinyinIndex: Map<String, List<DictionaryEntry>>,
        val untonedIndex: Map<String, List<DictionaryEntry>>,
    )

    companion object {
        private const val TAG = "OfflineDictionary"

        private const val ASSET_PATH = "dictionary/pilot.json"

        /** Strips tone marks from pinyin for untoned lookup. */
        fun stripTones(pinyin: String): String {
            val toneMap = mapOf(
                'ā' to 'a', 'á' to 'a', 'ǎ' to 'a', 'à' to 'a',
                'ē' to 'e', 'é' to 'e', 'ě' to 'e', 'è' to 'e',
                'ī' to 'i', 'í' to 'i', 'ǐ' to 'i', 'ì' to 'i',
                'ō' to 'o', 'ó' to 'o', 'ǒ' to 'o', 'ò' to 'o',
                'ū' to 'u', 'ú' to 'u', 'ǔ' to 'u', 'ù' to 'u',
                'ǖ' to 'ü', 'ǘ' to 'ü', 'ǚ' to 'ü', 'ǜ' to 'ü',
            )
            return pinyin.map { toneMap[it] ?: it }.joinToString("")
        }
    }
}