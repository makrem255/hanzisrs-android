package com.example.data.dictionary

/**
 * A single entry from the bundled offline dictionary.
 *
 * All fields except [hanzi] and [pinyin] may be empty or zero when the source
 * does not provide them. Empty means "not available," never "verified absent."
 */
data class DictionaryEntry(
    /** Simplified Chinese (the lookup key). */
    val hanzi: String,
    /** Traditional Chinese, or empty if not provided. */
    val traditional: String = "",
    /** Tone-marked Pinyin, unspaced (e.g. "māma"). */
    val pinyin: String,
    /** Numbered Pinyin from the source (e.g. "ma1 ma5"). */
    val pinyinNumbered: String = "",
    /** English definition(s), joined with "; ". */
    val meaning: String = "",
    /** HSK level from the source, or 0 if unknown. */
    val hskLevel: Int = 0,
)

/**
 * Maps a dictionary entry to the app's review/approval model.
 *
 * Missing fields (radical, strokes, examples) are left empty — never fabricated.
 * The origin is LOCAL_FALLBACK (no new enum). The source is set by the caller.
 */
fun DictionaryEntry.toGeneratedWordData(): com.example.data.ai.GeneratedWordData =
    com.example.data.ai.GeneratedWordData(
        hanzi = hanzi,
        pinyin = pinyin,
        meaning = meaning,
        hskLevel = hskLevel,
        radical = "",
        exampleCn = "",
        examplePy = "",
        exampleEn = "",
        strokeBreakdown = "",
        strokeCount = 0,
        origin = com.example.data.ai.WordDataOrigin.LOCAL_FALLBACK,
        source = com.example.data.model.StorageValues.VocabularySource.IMPORTED,
    )