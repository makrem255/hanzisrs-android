package com.example.data.srs

import com.example.data.model.CharacterStrokeEntity

/**
 * Reads the v2 `vocabulary.strokeJson` blob into stroke rows.
 *
 * The blob is a comma-separated list of stroke *names*, each optionally followed by its reading in
 * parentheses:
 *
 * ```
 * 点 (Diǎn), 点 (Diǎn), 丿 (Piǎo), 丶 (Diǎn), 亅 (Héng Gōu), ㇏ (Wàn Gōu), 一 (Héng)
 * ```
 *
 * Pure, with no Android, database or clock dependency, for the same reason [PinyinAnalyzer] and
 * [SrsAlgorithm] are: the migration runs it, the seed runs it, and both need to agree on what a
 * blob means. If a second implementation existed, migrated characters and freshly imported ones
 * would disagree about the same string.
 *
 * ## What this does not do
 *
 * It does not derive a stroke count by counting separators, and it does not invent a name for a
 * segment it cannot read. A stroke whose text is empty or is only punctuation is dropped rather
 * than stored as a blank row, because a blank row is a claim that a stroke exists and has no name.
 * A blob that is not in this format yields nothing at all, which the schema represents as "no
 * stroke data" - a state the UI must already handle for a character nobody has data for.
 */
object StrokeNameParser {

    /** Separates one stroke from the next. `,` alone, with surrounding space optional. */
    private val SEPARATOR = Regex("\\s*,\\s*")

    /**
     * Splits the reading off the end of a name: `点 (Diǎn)` becomes `点` and `Diǎn`.
     *
     * Requires the parentheses to close at the end of the segment, so a name that legitimately
     * contains a bracket - none in this vocabulary do, but the rule should not depend on that -
     * is not mangled. The pinyin is stripped of spaces between syllables only where the source
     * wrote them as a multi-word reading; the value is display text, not something to re-parse.
     */
    private val READING = Regex("^(.*?)\\s*\\(([^()]*)\\)\\s*$")

    /**
     * Parses [blob] into stroke rows, in the order the blob lists them.
     *
     * The `characterId` and `namePinyin` defaults of the entity are filled in by the caller, which
     * is the only thing that knows which character is being written. Positions are 1-based and
     * dense: if segment 3 of the blob is unreadable it is skipped and the next stroke continues the
     * sequence, because a gap in `position` would break `ORDER BY position` as a way of reading the
     * order back.
     *
     * @return empty for a blank blob or one with no readable segment. Never null, and never a
     *   partial sequence with holes in it.
     */
    fun parse(blob: String): List<CharacterStrokeEntity> {
        if (blob.isBlank()) return emptyList()

        val strokes = mutableListOf<CharacterStrokeEntity>()
        for (segment in blob.split(SEPARATOR)) {
            if (segment.isBlank()) continue

            val match = READING.matchEntire(segment)
            val name = (match?.groupValues?.get(1) ?: segment).trim()
            // A segment with no readable name is a stroke we cannot describe, so it is not stored.
            if (name.isEmpty()) continue

            val pinyin = match?.groupValues?.get(2)?.trim().orEmpty()
            // A pinyin reading that is only whitespace is no reading at all. `namePinyin` is
            // nullable-as-empty rather than holding a blank that a screen has to detect.
            strokes += CharacterStrokeEntity(
                characterId = 0L,
                position = strokes.size + 1,
                nameCn = name,
                namePinyin = if (pinyin.isBlank()) "" else pinyin
            )
        }
        return strokes
    }
}
