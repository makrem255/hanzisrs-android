package com.example

import com.example.audio.RecognitionFailure
import com.example.audio.RecognitionState
import com.example.audio.RecognitionVerdict
import com.example.audio.matchesTarget
import com.example.audio.normalizeForMatch
import com.example.audio.verdictFor
import com.example.ui.screens.failureText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What Random Review is allowed to claim about what it heard.
 *
 * These functions are the *entire* basis of the feedback a learner sees after speaking. They
 * compare a recogniser hypothesis with a target and say one of three things: this word, a
 * different word, or nothing. There is no score anywhere in this file, and there is no branch
 * that could produce one - which is the point. A number derived from speech-to-text would be
 * this app's invention presented as a measurement, and the tests below exist partly to keep it
 * that way.
 */
class SpeechMatchTest {

    // ---- normalisation -------------------------------------------------------------------

    @Test
    fun `whitespace and punctuation are not part of what was said`() {
        assertEquals(normalizeForMatch("学习"), normalizeForMatch(" 学习 "))
        assertEquals(normalizeForMatch("ni hao"), normalizeForMatch("ni, hao!"))
        assertEquals(normalizeForMatch("ni hao"), normalizeForMatch("ni-hao"))
        assertEquals("", normalizeForMatch("  ...  "))
    }

    @Test
    fun `full-width marks a Chinese recogniser adds are dropped`() {
        assertEquals(
            "a recogniser that punctuates its own answer must not fail the comparison",
            normalizeForMatch("学习。"),
            normalizeForMatch("学习"),
        )
        assertEquals(normalizeForMatch("「学习」"), normalizeForMatch("学习"))
    }

    @Test
    fun `tone marks are stripped so xuexi and xuéxí are the same syllables`() {
        assertEquals(
            "the tone is what the learner is being asked about, not a difference in identity",
            normalizeForMatch("xuéxí"),
            normalizeForMatch("xuexi"),
        )
        assertEquals(normalizeForMatch("nǐ hǎo"), normalizeForMatch("ni hao"))
        // The stripping applies inside matching too, so tone is not a difference in identity
        // there either. (Comparing a hanzi target against a pinyin hypothesis is deliberately
        // not attempted: that needs a dictionary the recogniser does not provide.)
        assertTrue(matchesTarget(target = "xuéxí", hypothesis = "xuexi"))
        assertTrue(matchesTarget(target = "nǐhǎo", hypothesis = "ni hao"))
    }

    @Test
    fun `case does not distinguish latin input`() {
        assertEquals(normalizeForMatch("Lao"), normalizeForMatch("lao"))
    }

    // ---- matching ------------------------------------------------------------------------

    @Test
    fun `the exact word is recognised`() {
        assertTrue(matchesTarget("学习", "学习"))
        assertTrue(matchesTarget("好", "好"))
    }

    @Test
    fun `a word wrapped in a longer utterance still counts as recognised`() {
        // Every one of these is the recogniser hearing the target and adding a neighbour, which
        // happens constantly on zh-CN engines.
        assertTrue(matchesTarget("学习", "学习了"))
        assertTrue(matchesTarget("学习", "是学习"))
        assertTrue(matchesTarget("学习", "我们学习吧"))
    }

    @Test
    fun `a partial answer is not the word`() {
        // The reverse direction is deliberately excluded. Asked for 学习 and hearing only 学 is
        // not recognising the word, and reporting a match would tell the learner they said it.
        assertFalse(matchesTarget("学习", "学"))
        assertFalse(matchesTarget("学生", "学"))
    }

    @Test
    fun `a different word is never a match`() {
        assertFalse(matchesTarget("学习", "学校"))
        assertFalse(matchesTarget("你好", "再见"))
    }

    @Test
    fun `nothing to compare against is never a match`() {
        assertFalse("an empty target must not match an empty answer by default", matchesTarget("", ""))
        assertFalse(matchesTarget("学习", ""))
        assertFalse(matchesTarget("", "学习"))
        assertFalse(matchesTarget("学习", "   "))
    }

    // ---- the three honest verdicts --------------------------------------------------------

    @Test
    fun `the verdict distinguishes heard-from-not-heard from right-from-wrong`() {
        assertEquals(RecognitionVerdict.Recognised, verdictFor("学习", "学习了"))
        assertEquals(RecognitionVerdict.Different, verdictFor("学习", "学校"))
        assertEquals(
            "silence and 'not this word' are different facts and must not be conflated",
            RecognitionVerdict.NothingHeard,
            verdictFor("学习", null),
        )
        assertEquals(RecognitionVerdict.NothingHeard, verdictFor("学习", ""))
    }

    // ---- failures read as different problems ---------------------------------------------

    @Test
    fun `every recogniser failure maps to its own explanation`() {
        // Cancelled is excluded: it is deliberately empty (see the test below), because the
        // learner who caused it has already left and there is nobody left to read a message.
        val messages = RecognitionFailure.entries
            .filter { it != RecognitionFailure.Cancelled }
            .map { failureText(it) }

        assertEquals(
            "a failure with no message would render a blank line where guidance belongs",
            RecognitionFailure.entries.size - 1,
            messages.count { it.isNotBlank() },
        )
        assertEquals(
            "two failures that read the same would hide which problem actually occurred",
            messages.size,
            messages.toSet().size,
        )
        // Nothing anywhere resembles a score - the failure branch of a grading feature would.
        assertTrue(messages.none { "%" in it })
        assertTrue(messages.none { it.contains("score", ignoreCase = true) })
    }

    @Test
    fun `a cancelled attempt explains nothing because nobody is left to read it`() {
        assertEquals("", failureText(RecognitionFailure.Cancelled))
    }

    @Test
    fun `an unknown recogniser code is never surfaced verbatim`() {
        val text = failureText(RecognitionFailure.Unknown)
        assertTrue("raw codes are not information to a learner", !text.any { it.isDigit() })
    }

    @Test
    fun `the heard-state carries its hypothesis and alternatives through unchanged`() {
        val state = RecognitionState.Heard(hypothesis = "学习了", alternatives = listOf("学校"))
        assertEquals("学习了", state.hypothesis)
        assertEquals(listOf("学校"), state.alternatives)
    }
}
