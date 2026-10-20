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

    /** Lookup by Pinyin (normalized: trimmed, lowercased, spaces removed). */
    fun lookupByPinyin(pinyin: String): List<DictionaryEntry> {
        val normalized = pinyin.trim().lowercase().replace(" ", "")
        return data.pinyinIndex[normalized] ?: emptyList()
    }

    private fun loadFromAssets(): DictionaryData {
        return try {
            val json = appContext.assets.open(ASSET_PATH).bufferedReader().use { it.readText() }
            val root = JSONObject(json)
            val entriesArray = root.optJSONArray("entries") ?: JSONArray()
            val entries = mutableMapOf<String, DictionaryEntry>()
            val pinyinIndex = mutableMapOf<String, MutableList<DictionaryEntry>>()

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
            }

            DictionaryData(entries, pinyinIndex)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load dictionary asset: ${e.message}")
            DictionaryData(emptyMap(), emptyMap())
        }
    }

    private data class DictionaryData(
        val entries: Map<String, DictionaryEntry>,
        val pinyinIndex: Map<String, List<DictionaryEntry>>,
    )

    companion object {
        private const val TAG = "OfflineDictionary"
        private const val ASSET_PATH = "dictionary/pilot.json"
    }
}