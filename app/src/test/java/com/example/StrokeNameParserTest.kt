package com.example

import com.example.data.srs.StrokeNameParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The v2 stroke-blob parser, on its own.
 *
 * `MIGRATION_2_3` and the starter-content seed both call this, so the two routes that populate
 * `character_strokes` agree about what a blob means. If they did not, a character imported by the
 * migration and a character typed in today would carry different stroke sequences, and nothing
 * would report it - the sequence is only ever read back as a list.
 *
 * The fixtures are production shapes, not shapes invented here, because the failure mode being
 * guarded against is a format the real data has and the test does not.
 */
class StrokeNameParserTest {

    @Test
    fun `a production-shaped blob becomes one dense 1-based sequence`() {
        val strokes = StrokeNameParser.parse(
            "丶 (Diǎn), 丶 (Diǎn), 丿 (Piǎo), 丶 (Diǎn), 亅 (Héng Gōu), ㇏ (Wàn Gōu), 一 (Héng)"
        )

        assertEquals(7, strokes.size)
        // Positions are 1-based and dense. A zero-based index would make a screen print "stroke 0
        // of 7" unless it added one, and a gap would break `ORDER BY position` as a way of
        // reading the writing order back.
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7), strokes.map { it.position })
        assertEquals("丶", strokes.first().nameCn)
        assertEquals("一", strokes.last().nameCn)
    }

    @Test
    fun `the reading is split off the name rather than kept inside it`() {
        val strokes = StrokeNameParser.parse("亅 (Héng Gōu), ㇏ (Wàn Gōu)")

        assertEquals("亅", strokes[0].nameCn)
        assertEquals("Héng Gōu", strokes[0].namePinyin)
        // A multi-syllable reading is one value, not two: the column holds the reading of the
        // stroke's *name*, which for 亅 is the two-character word 横钩.
        assertEquals(2, strokes.count { it.namePinyin.contains(" ") })
    }

    @Test
    fun `a name with no reading stored keeps the name and stores no pinyin`() {
        val strokes = StrokeNameParser.parse("一, 二, 三")

        assertEquals(listOf("一", "二", "三"), strokes.map { it.nameCn })
        // Empty rather than a space or a null: the column is NOT NULL, and a screen has to be
        // able to ask "is this known" without also having to strip whitespace.
        assertTrue(strokes.all { it.namePinyin.isEmpty() })
    }

    @Test
    fun `an unreadable segment is dropped and the rest stays contiguous`() {
        // The empty segment between two commas, and a lone bracket pair, are both things a
        // hand-edited blob can contain. Neither names a stroke, so neither is stored - and the
        // stroke after them must not move up a position to fill the gap, because the position is
        // the writing order and re-numbering it would change which stroke is which.
        val strokes = StrokeNameParser.parse("一 (Héng), , 丨 (Shù), ()")

        assertEquals(2, strokes.size)
        assertEquals(listOf(1, 2), strokes.map { it.position })
        assertEquals("一", strokes[0].nameCn)
        assertEquals("丨", strokes[1].nameCn)
    }

    @Test
    fun `a blob that names no stroke yields nothing at all`() {
        // The alternative - a single row with an empty name - would claim a stroke exists and
        // then be unable to say what it is, which is strictly worse than "no stroke data".
        assertTrue(StrokeNameParser.parse("").isEmpty())
        assertTrue(StrokeNameParser.parse("   ").isEmpty())
        assertTrue(StrokeNameParser.parse(",,,").isEmpty())
    }

    @Test
    fun `a repeated stroke name is stored once per occurrence`() {
        // 点 点 点 点 is a real sequence, and it is how a character like 灬 is written. Collapsing
        // repeats would lose strokes, and deduplicating the blob is not this function's job.
        //
        // The count is asserted, and so is the sequence of names - four rows all reading 丶. A
        // deduping parser still returns four names here, so the count alone cannot tell the
        // two implementations apart; asserting the ordered list is what pins "one row per
        // occurrence, in the order written" rather than "four rows, possibly in any order".
        val strokes = StrokeNameParser.parse("丶 (Diǎn), 丶 (Diǎn), 丶 (Diǎn), 丶 (Diǎn)")

        assertEquals(4, strokes.size)
        assertEquals(listOf("丶", "丶", "丶", "丶"), strokes.map { it.nameCn })
        assertEquals(listOf(1, 2, 3, 4), strokes.map { it.position })
    }

    @Test
    fun `a bracket that is not a trailing reading is part of the name`() {
        // The reading has to close at the end of the segment. A name that merely contains a
        // bracket must not have its last character cut off to satisfy the pattern.
        val strokes = StrokeNameParser.parse("㇅ (Shù Wān Gōu)")

        assertEquals("㇅", strokes.single().nameCn)
        assertEquals("Shù Wān Gōu", strokes.single().namePinyin)
    }

    @Test
    fun `the parser does not claim to know which character the strokes belong to`() {
        // The character id is the caller's to supply. It comes back as the entity's zero default
        // rather than being guessed, because a stroke row pointing at character 0 is a row the
        // foreign key will reject, which is a louder failure than one the parser invents.
        val stroke = StrokeNameParser.parse("一 (Héng)").single()

        assertEquals(0L, stroke.characterId)
    }
}
