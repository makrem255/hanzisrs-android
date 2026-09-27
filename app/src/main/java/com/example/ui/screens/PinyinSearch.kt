package com.example.ui.screens

import java.text.Normalizer

/**
 * Makes pinyin searchable by the pinyin a phone keyboard actually produces.
 *
 * ## Why
 *
 * The library matched pinyin with a literal `contains`. A learner typing on a phone has
 * three diacritics, none of them on most keyboards, so the realistic query is `ni hao` or
 * `cha` or the numeric `ni3 hao3`. None of those are substrings of `nǐ hǎo` or `chá` — the
 * marks sit between the letters — so the search returned nothing for a word that was
 * plainly on screen, with no indication of why.
 *
 * ## What it does
 *
 * Decomposes to NFD and drops the combining marks, so tone-marked and tone-less forms
 * collapse to the same letters: `nǐ hǎo` → `ni hao`, `chá` → `cha`, `lǜ` → `lv`. It also
 * folds the numeric form, because `3` and the third tone are the same tone written two
 * ways and a learner should not have to know which one this app stores.
 *
 * ## What it does not do
 *
 * It does not map `v` to `ü`, and it does not handle `ng`, `erhua`, or any of the other
 * places Mandarin spelling is genuinely irregular. Those are not transliteration choices
 * this app should be making on the fly — `lǜ` folded to `lu` would be a *different syllable*
 * from `lu`, and silently merging them would return a word the learner did not search for.
 * Wrong results are worse than no results, so the fold stops at what is reversible.
 */
internal object PinyinSearch {

    private val TONE_MARKS = Regex("\\p{Mn}+")
    private val DIGIT_TONES = Regex("[1-5]")

    /**
     * Folds [text] to the form the search compares against.
     *
     * Handles tone marks and the numeric tone digits. Returns the input unchanged if it is
     * not Latin at all, so a search for 水 does not get mangled on its way through a
     * normaliser that has no opinion about it.
     */
    fun foldToneMarks(text: String): String {
        if (text.isEmpty()) return text
        // Fast path: if there is nothing to fold, do not pay for decomposition. This runs
        // per word per keystroke, and most rows in a large collection are plain pinyin.
        if (!text.any { it in TONE_CHARS || it in '1'..'5' }) return text

        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
        val withoutTones = TONE_MARKS.replace(decomposed, "")
        return DIGIT_TONES.replace(withoutTones, "")
    }

    private val TONE_CHARS = setOf(
        'ā', 'á', 'ǎ', 'à',
        'ē', 'é', 'ě', 'è',
        'ī', 'í', 'ǐ', 'ì',
        'ō', 'ó', 'ǒ', 'ò',
        'ū', 'ú', 'ǔ', 'ù',
        'ǖ', 'ǘ', 'ǚ', 'ǜ',
        'ń', 'ň', 'ǹ', 'ḿ'
    )
}
