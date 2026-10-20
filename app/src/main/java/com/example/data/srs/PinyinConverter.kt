package com.example.data.srs

/**
 * Converts numbered Pinyin (e.g. "ma1 ma5") to tone-marked Pinyin (e.g. "māma").
 *
 * This is the reverse of [PinyinAnalyzer.analyze], which decomposes tone-marked
 * Pinyin into syllable + tone number. The converter is needed for importing
 * dictionary data (CC-CEDICT uses numbered Pinyin) into the app's tone-marked
 * display convention.
 *
 * ## Conventions
 *
 * - Input: space-separated syllables, each with a trailing tone digit 0-5.
 * - Output: tone-marked syllables joined **without spaces** (matching the app's
 *   existing display convention, e.g. "lǎoshī" not "lǎo shī").
 * - Uppercase initials are normalized to lowercase.
 * - Neutral tone (0 or 5): no diacritic.
 * - Malformed input is returned unchanged (no fabrication, no crash).
 *
 * ## Vowel selection (standard Pinyin tone-mark placement)
 *
 * 1. If 'a' is present, mark 'a'.
 * 2. If 'o' is present (no 'a'), mark 'o'.
 * 3. If 'e' is present (no 'a' or 'o'), mark 'e'.
 * 4. If the syllable ends in "iu", mark 'u' (second vowel).
 * 5. If the syllable ends in "ui", mark 'i' (second vowel).
 * 6. Otherwise, mark the last vowel in "iuü".
 */
object PinyinConverter {

    private val TONE_MARKS = mapOf(
        'a' to charArrayOf('a', 'ā', 'á', 'ǎ', 'à'),
        'o' to charArrayOf('o', 'ō', 'ó', 'ǒ', 'ò'),
        'e' to charArrayOf('e', 'ē', 'é', 'ě', 'è'),
        'i' to charArrayOf('i', 'ī', 'í', 'ǐ', 'ì'),
        'u' to charArrayOf('u', 'ū', 'ú', 'ǔ', 'ù'),
        'ü' to charArrayOf('ü', 'ǖ', 'ǘ', 'ǚ', 'ǜ'),
    )

    /**
     * Converts numbered Pinyin to tone-marked Pinyin.
     *
     * @param numbered Input like "ma1 ma5" or "Zhong1 guo2".
     * @return Tone-marked Pinyin like "māma" or "zhōngguó".
     *         Returns the input unchanged if it cannot be parsed.
     */
    fun fromNumbered(numbered: String): String {
        val trimmed = numbered.trim()
        if (trimmed.isEmpty()) return ""

        val syllables = trimmed.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (syllables.isEmpty()) return trimmed

        val converted = syllables.map { convertSyllable(it) }
        // If any syllable could not be converted, return the original input.
        if (converted.any { it == null }) return trimmed
        return converted.joinToString("") { it!! }
    }

    private fun convertSyllable(syllable: String): String? {
        // Extract tone digit from the end.
        val toneMatch = Regex("^(.*?)([0-5])$").find(syllable) ?: return null
        val base = toneMatch.groupValues[1]
        val tone = toneMatch.groupValues[2].toInt()

        if (base.isEmpty()) return null

        // Normalize ü: v → ü, u: → ü
        val normalized = base
            .replace('v', 'ü')
            .replace('V', 'ü')
            .replace("u:", "ü")
            .replace("U:", "ü")

        // Validate: only letters and ü allowed
        if (!normalized.all { it.isLetter() || it == 'ü' }) return null

        val lower = normalized.lowercase()

        // Neutral tone: no mark
        if (tone == 0 || tone == 5) return lower

        // Find the vowel to mark
        val target = toneMarkTarget(lower) ?: return lower
        val chars = lower.toCharArray()
        val index = chars.indexOf(target)
        if (index < 0) return lower

        val marked = TONE_MARKS[chars[index]] ?: return lower
        if (tone !in 1..4) return lower
        chars[index] = marked[tone]
        return String(chars)
    }

    private fun toneMarkTarget(base: String): Char? {
        if (base.contains('a')) return 'a'
        if (base.contains('o')) return 'o'
        if (base.contains('e')) return 'e'
        if (base.endsWith("iu")) return 'u'
        if (base.endsWith("ui")) return 'i'
        val lastVowel = base.lastOrNull { it in "iuü" }
        return lastVowel
    }
}