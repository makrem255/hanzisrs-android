package com.example.data.srs

/**
 * Decomposes a written pinyin syllable into the parts the `pinyin_syllables` table stores.
 *
 * Pure and total: it never throws and never returns null, so a value produced by the AI
 * generation path or typed by a learner can always be stored. Unrecognised input is preserved
 * as a neutral-tone syllable rather than discarded, because losing the learner's text is
 * worse than storing it without a tone analysis.
 */
object PinyinAnalyzer {

    /** The decomposed form stored in `pinyin_syllables`. */
    data class Analysis(
        /** Toneless ASCII syllable, lower case. */
        val syllable: String,
        /** 0-4, where 0 is the neutral tone. */
        val toneNumber: Int,
        /** Input with its diacritics, normalised. */
        val toneMarked: String,
        val initial: String,
        val final: String,
        /**
         * Five-level tone contour, from the standard mapping.
         *
         * Always populated: [toneNumber] is 0-4 with no unknown case, and the neutral tone is
         * the ordinary mid-level `33`. An empty string here would mean "no contour", which the
         * drawing code would then have to special-case, so it is better for the neutral tone
         * to carry its real contour and be distinguishable by [toneNumber].
         */
        val toneContour: String
    )

    /** The 24 initials, longest first so `zh` is preferred over `z`. */
    private val INITIALS = listOf(
        "zh", "ch", "sh",
        "b", "p", "m", "f", "d", "t", "n", "l", "g", "k", "h",
        "j", "q", "x", "r", "z", "c", "s", "y", "w"
    )

    private val TONE_MARKS = mapOf(
        'a' to charArrayOf('a', 'ā', 'á', 'ǎ', 'à'),
        'o' to charArrayOf('o', 'ō', 'ó', 'ǒ', 'ò'),
        'e' to charArrayOf('e', 'ē', 'é', 'ě', 'è'),
        'i' to charArrayOf('i', 'ī', 'í', 'ǐ', 'ì'),
        'u' to charArrayOf('u', 'ū', 'ú', 'ǔ', 'ù'),
        'ü' to charArrayOf('ü', 'ǖ', 'ǘ', 'ǚ', 'ǜ')
    )

    /** Reverse map from every toned vowel to (base letter, tone number). */
    private val TONE_LOOKUP: Map<Char, Pair<Char, Int>> = buildMap {
        for ((base, forms) in TONE_MARKS) {
            for (tone in 1..4) {
                put(forms[tone], base to tone)
            }
        }
    }

    private val CONTOURS = mapOf(1 to "55", 2 to "35", 3 to "214", 4 to "51", 0 to "33")

    /**
     * The contour for [tone], total over every value `analyze` can produce.
     *
     * `Analysis.toneContour` documents itself as always populated, because an empty contour means
     * "no contour" and every drawing site would then have to special-case it. That invariant was
     * enforced in exactly one of the two places that produce a contour — the empty-input branch,
     * which was returning `""` — and left to a `.orEmpty()` in the other, which is how it came
     * back: a caller could still have been handed the case the field says is impossible.
     *
     * [TONE_MARKS] only ever defines tones 1 to 4 and `analyze` starts at 0, so the `?:` below
     * cannot currently be taken. It is kept anyway, as the one total place the mapping is read,
     * so that adding a tone to [TONE_MARKS] without adding its contour degrades to neutral rather
     * than to a string the type says does not exist.
     */
    private fun contourFor(tone: Int): String = CONTOURS[tone] ?: CONTOURS.getValue(0)

    /**
     * Analyses [input], tolerating surrounding whitespace, an initial capital, and the ASCII
     * `v` spelling of `ü` that older data uses.
     */
    fun analyze(input: String): Analysis {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) {
            // The neutral contour, not an empty string. The `toneContour` field documents
            // itself as always populated — an empty value would mean "no contour", which the
            // drawing code would then have to special-case — and this branch was returning
            // exactly the case its own invariant rules out. Tone 0 is also the honest answer:
            // an unparseable reading is neutral, not "unknown".
            return Analysis("", 0, "", "", "", contourFor(0))
        }

        // `v` is used for `ü` when a tone mark cannot be shown; expand it before anything else.
        val normalised = trimmed.replace('v', 'ü').replace('V', 'ü')
        val lower = normalised.lowercase()

        var tone = 0
        val plain = StringBuilder(lower.length)
        for (ch in lower) {
            val decomposed = TONE_LOOKUP[ch]
            if (decomposed != null) {
                plain.append(decomposed.first)
                // A syllable carries at most one tone; a second mark is not a valid reading.
                if (tone == 0) tone = decomposed.second
            } else {
                plain.append(ch)
            }
        }

        val base = plain.toString()
        val (initial, final) = splitInitial(base)
        val toneMarked = applyToneMark(base, tone)
        return Analysis(
            syllable = base,
            toneNumber = tone,
            toneMarked = toneMarked,
            initial = initial,
            final = canonicalise(final),
            toneContour = contourFor(tone)
        )
    }

    /**
     * Splits a toneless syllable into its initial consonant and its rime.
     *
     * `y` and `w` are not initials. They are orthographic spellings of the glides that stand in
     * for a missing vowel onset, so a syllable beginning with either has no initial at all.
     * They are also aliases: `you` and `liu` are the same rime, and `wei` and `gui` are the
     * same rime. Because `pinyin_syllables` is queried by rime, leaving the letter in place
     * would file those pairs as different words, so the letter is restored to the underlying
     * form. Storing `yao` as the rime of `yáo` would be the kind of quietly wrong data that
     * only surfaces later, as an empty result for a legitimate search.
     */
    private fun splitInitial(base: String): Pair<String, String> {
        if (base.startsWith("y")) return "" to restoreY(base)
        if (base.startsWith("w")) return "" to restoreW(base)
        for (candidate in INITIALS) {
            if (base.startsWith(candidate) && base.length > candidate.length) {
                return candidate to base.substring(candidate.length)
            }
        }
        return "" to base
    }

    private fun restoreY(base: String): String = when {
        base.length == 1 -> "i"
        // `yu` stands for `ü`, as do its combinations. The dot is dropped in writing only
        // because `ü` cannot follow `j`, `q` or `x`, so it is restored here.
        base.startsWith("yu") -> "ü" + base.substring(2)
        // `yi`, `yin` and `ying` already begin with the i that y stands for; the rest need it
        // written out. `you` is the case that needs the full `iou` before it is collapsed.
        base.startsWith("yi") -> base.substring(1)
        else -> "i" + base.substring(1)
    }

    private fun restoreW(base: String): String = when {
        base.length == 1 -> "u"
        base == "wu" -> "u"
        else -> "u" + base.substring(1)
    }

    /**
     * Collapses the three rimes that have a short form in common use.
     *
     * `you`, `wei` and `wen` restore to `iou`, `uei` and `uen`, which is how the syllable is
     * described in full, but the short forms are what every other syllable in the table uses:
     * `liu` and `gui` analyse to `iu` and `ui`. Without this the `y` and `w` spellings would
     * be the only rows filed under the long form, and the table would hold two answers to
     * "what is the rime of this word".
     */
    private fun canonicalise(rime: String): String = when (rime) {
        "iou" -> "iu"
        "uei" -> "ui"
        "uen" -> "un"
        else -> rime
    }

    /**
     * Re-applies the tone diacritic to the base syllable using the standard placement rules:
     * mark `a` if present, else `o` or `e`, else the last vowel — with `iu` and `ui` marked
     * on the second vowel.
     */
    private fun applyToneMark(base: String, tone: Int): String {
        if (tone == 0 || base.isEmpty()) return base
        val target = toneMarkTarget(base) ?: return base
        val chars = base.toCharArray()
        val index = chars.indexOf(target)
        if (index < 0) return base
        val marked = TONE_MARKS[chars[index]] ?: return base
        chars[index] = marked[tone]
        return String(chars)
    }

    private fun toneMarkTarget(base: String): Char? {
        if (base.contains('a')) return 'a'
        if (base.contains('o')) return 'o'
        if (base.contains('e')) return 'e'
        // `iu` takes the mark on the u, `ui` on the i.
        if (base.endsWith("iu")) return 'u'
        if (base.endsWith("ui")) return 'i'
        val lastVowel = base.lastOrNull { it in "iuü" }
        return lastVowel
    }
}
