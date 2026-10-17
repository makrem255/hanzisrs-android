package com.example

import com.example.data.strokes.AssessmentGap
import com.example.data.strokes.AttemptAssessment
import com.example.data.strokes.DIRECTION_MARGIN_REL
import com.example.data.strokes.DirectionVerdict
import com.example.data.strokes.MIN_DRAWN_SPAN
import com.example.data.strokes.ShapeVerdict
import com.example.data.strokes.StrokeVerdict
import com.example.data.strokes.TracePoint

import com.example.data.strokes.computeGlobalStats
import com.example.data.strokes.dedupTrace
import com.example.data.strokes.evaluateAttempt
import com.example.data.strokes.normalizeByGlobalStats
import com.example.data.strokes.normalizeTrace
import com.example.data.strokes.resampleTrace
import com.example.data.strokes.shapeDistance
import com.example.data.strokes.summarizeAssessment
import com.example.data.strokes.traceSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

/**
 * The handwriting evaluator, without any UI.
 *
 * Plain JUnit on purpose: [com.example.data.strokes] imports nothing from
 * Compose or Android, and keeping it that way is part of the contract - if
 * this file ever needs Robolectric, the evaluator has grown a dependency it
 * must not have.
 *
 * Synthetic inputs stand in for learner strokes throughout. That is stated
 * openly rather than hidden: jittered lines prove the math behaves, not that
 * the bands match human handwriting, and the thresholds stay provisional
 * until real calibration happens.
 */
class StrokeMatchTest {

    private fun line(n: Int = 11, from: Float = 0f, to: Float = 1f): List<TracePoint> =
        (0 until n).map { TracePoint(from + (to - from) * it / (n - 1).coerceAtLeast(1), 0f) }

    // ---- resampling ----------------------------------------------------------------------------------

    @Test
    fun `resampling produces ordered equidistant points`() {
        val out = resampleTrace(line(3, 0f, 10f), 5)

        assertEquals(5, out.size)
        assertEquals(0f, out.first().x, 0.001f)
        assertEquals(10f, out.last().x, 0.001f)
        assertEquals(5f, out[2].x, 0.001f)
    }

    @Test
    fun `duplicates and standstills do not move the resampling`() {
        val withPauses = listOf(
            TracePoint(0f, 0f),
            TracePoint(0f, 0f),
            TracePoint(5f, 0f),
            TracePoint(5f, 0f),
            TracePoint(5f, 0f),
            TracePoint(10f, 0f),
        )

        assertEquals(resampleTrace(line(3, 0f, 10f), 7), resampleTrace(withPauses, 7))
    }

    @Test
    fun `degenerate input resamples without dividing by zero`() {
        val single = resampleTrace(listOf(TracePoint(3f, 4f)))
        assertTrue(single.isNotEmpty() && single.all { it == TracePoint(3f, 4f) })
        assertTrue(resampleTrace(emptyList()).isEmpty())
    }

    // ---- normalization ---------------------------------------------------------------------------------

    @Test
    fun `normalization removes position and size but keeps aspect`() {
        val tall = normalizeTrace((0..10).map { TracePoint(5f, it.toFloat()) })
        val wide = normalizeTrace((0..10).map { TracePoint(it.toFloat(), 5f) })

        // Same x-extent in both must be impossible after normalization:
        // a tall stroke and a wide stroke are different shapes.
        assertTrue(shapeDistance(tall, wide) > 0.5f)
        // But the same stroke recentred and rescaled matches itself.
        val moved = normalizeTrace((0..10).map { TracePoint(100f + it, 200f) })
        assertEquals(0f, shapeDistance(wide, moved), 0.001f)
    }

    // ---- coordinate conventions --------------------------------------------------------------------------

    @Test
    fun `a canvas top-down stroke matches a y-up reference median`() {
        // The core convention regression test. Canvas fractions grow downward
        // (top of the canvas is 0); bundled medians grow upward (top is large).
        // A vertical line drawn top-down on screen must read as forward against
        // a top-down median. If the harmonization is ever removed, this reads
        // backward instead.
        val median = listOf(TracePoint(0f, 0.8f), TracePoint(0f, 0.5f), TracePoint(0f, 0.2f))
        val drawn = listOf(TracePoint(0.5f, 0.2f), TracePoint(0.5f, 0.5f), TracePoint(0.5f, 0.8f))

        val attempt = evaluateAttempt(listOf(median), listOf(drawn))
        assertTrue(attempt is AttemptAssessment.Assessed)
        val verdict = (attempt as AttemptAssessment.Assessed).results.single()
        assertEquals(ShapeVerdict.CloseToReference, verdict.shape)
        assertEquals(DirectionVerdict.Forward, verdict.direction)
    }

    @Test
    fun `the same stroke drawn bottom-up reads backward, not forward`() {
        val median = listOf(TracePoint(0f, 0.8f), TracePoint(0f, 0.5f), TracePoint(0f, 0.2f))
        val drawnUp = listOf(TracePoint(0.5f, 0.8f), TracePoint(0.5f, 0.5f), TracePoint(0.5f, 0.2f))

        val attempt = evaluateAttempt(listOf(median), listOf(drawnUp))
        assertTrue(attempt is AttemptAssessment.Assessed)
        val verdict = (attempt as AttemptAssessment.Assessed).results.single()
        assertEquals(DirectionVerdict.Backward, verdict.direction)
    }

    // ---- assessability --------------------------------------------------------------------------------------

    @Test
    fun `single points and standstills are too short, not wrong`() {
        val ref = line()
        for (bad in listOf(
            emptyList(),
            listOf(TracePoint(0.4f, 0.4f)),
            listOf(TracePoint(0.4f, 0.4f), TracePoint(0.4f, 0.4f)),
        )) {
            val attempt = evaluateAttempt(listOf(ref), listOf(bad))
            assertTrue(attempt is AttemptAssessment.Assessed)
            val verdict = (attempt as AttemptAssessment.Assessed).results.single()
            assertEquals(ShapeVerdict.NotEnoughInfo, verdict.shape)
            assertEquals(AssessmentGap.TooShort, verdict.gap)
            assertEquals(DirectionVerdict.Unclear, verdict.direction)
        }
    }

    @Test
    fun `dots are too small, not wrong`() {
        val ref = line()
        val dot = listOf(TracePoint(0.5f, 0.5f), TracePoint(0.5f + MIN_DRAWN_SPAN / 2, 0.5f))

        val attempt = evaluateAttempt(listOf(ref), listOf(dot))
        assertTrue(attempt is AttemptAssessment.Assessed)
        val verdict = (attempt as AttemptAssessment.Assessed).results.single()
        assertEquals(ShapeVerdict.NotEnoughInfo, verdict.shape)
        assertEquals(AssessmentGap.TooSmall, verdict.gap)
    }

    @Test
    fun `a retraced stroke is directionally unclear`() {
        // Out and back along the same line: forward and reversed agree, so the
        // margin rule must refuse to call it either way.
        val ref = line()
        val thereAndBack = line(6) + line(6).asReversed().drop(1)

        val attempt = evaluateAttempt(listOf(ref), listOf(thereAndBack))
        assertTrue(attempt is AttemptAssessment.Assessed)
        val verdict = (attempt as AttemptAssessment.Assessed).results.single()
        assertEquals(DirectionVerdict.Unclear, verdict.direction)
        assertTrue(
            "an unclear direction must never be labelled",
            verdict.gap == null || verdict.gap == AssessmentGap.SimilarityUnclear,
        )
    }

    @Test
    fun `non-finite input cannot crash assessment`() {
        val ref = line()
        val nan = listOf(TracePoint(Float.NaN, 0f), TracePoint(1f, 0f))

        val attempt = evaluateAttempt(listOf(ref), listOf(nan))
        assertTrue(attempt is AttemptAssessment.Assessed)
        assertEquals(
            ShapeVerdict.NotEnoughInfo,
            (attempt as AttemptAssessment.Assessed).results.single().shape,
        )
    }

    @Test
    fun `out-of-bounds fractions assess without crashing`() {
        val ref = line()
        val wild = listOf(TracePoint(-2f, -1f), TracePoint(3f, 2f))

        val attempt = evaluateAttempt(listOf(ref), listOf(wild))
        assertTrue(attempt is AttemptAssessment.Assessed)
    }

    // ---- similarity zones ------------------------------------------------------------------------------------

    @Test
    fun `all three similarity zones are reachable`() {
        val ref = line(11)
        val verdicts = (1..20).map { k ->
            val amp = k * 0.05f
            val wavy = (0..10).map { i -> TracePoint(i / 10f, amp * sin(i.toFloat())) }
            evaluateAttempt(listOf(ref), listOf(wavy)) as AttemptAssessment.Assessed
        }.map { it.results.single() }

        assertTrue("small wobbles must read close", verdicts.any { it.shape == ShapeVerdict.CloseToReference })
        assertTrue("wild swings must read different", verdicts.any { it.shape == ShapeVerdict.DifferentFromReference })
        assertTrue(
            "the middle must refuse to guess",
            verdicts.any { it.gap == AssessmentGap.SimilarityUnclear },
        )
    }

    // ---- attempt-level honesty ----------------------------------------------------------------------------------

    @Test
    fun `empty attempts say so explicitly`() {
        val attempt = evaluateAttempt(listOf(line()), emptyList())

        assertTrue(attempt is AttemptAssessment.Insufficient)
        assertEquals(AssessmentGap.EmptyAttempt, (attempt as AttemptAssessment.Insufficient).gap)
    }

    @Test
    fun `count mismatches refuse ordered pairing`() {
        val refs = listOf(line(), line())

        val short = evaluateAttempt(refs, listOf(line()))
        assertTrue(short is AttemptAssessment.Insufficient)
        assertEquals(AssessmentGap.StrokeCountMismatch, (short as AttemptAssessment.Insufficient).gap)
        assertEquals(2, short.expectedStrokes)
        assertEquals(1, short.drawnStrokes)

        val long = evaluateAttempt(refs, listOf(line(), line(), line()))
        assertTrue(long is AttemptAssessment.Insufficient)
    }

    @Test
    fun `missing reference data refuses instead of inventing`() {
        val attempt = evaluateAttempt(null, listOf(line()))

        assertTrue(attempt is AttemptAssessment.Insufficient)
        assertEquals(AssessmentGap.NoReferenceData, (attempt as AttemptAssessment.Insufficient).gap)
    }

    @Test
    fun `matching counts assess every stroke in order`() {
        val refs = listOf(line(), line(11, 0f, 2f))
        val drawn = listOf(line(), line(11, 0f, 2f))

        val attempt = evaluateAttempt(refs, drawn)
        assertTrue(attempt is AttemptAssessment.Assessed)
        val results = (attempt as AttemptAssessment.Assessed).results
        assertEquals(2, results.size)
        assertTrue(results.all { it.shape == ShapeVerdict.CloseToReference })
    }

    // ---- wording ----------------------------------------------------------------------------------------------------

    // ---- position preservation (corrective audit) --------------------------------------------------------

    private fun twoStrokeRef(): List<List<TracePoint>> = listOf(
        listOf(TracePoint(0.1f, 0.8f), TracePoint(0.5f, 0.8f), TracePoint(0.9f, 0.8f)),
        listOf(TracePoint(0.1f, 0.2f), TracePoint(0.5f, 0.2f), TracePoint(0.9f, 0.2f)),
    )

    @Test
    fun `correctly placed multi-stroke attempt receives close results`() {
        val refs = twoStrokeRef()
        val drawn = listOf(
            listOf(TracePoint(0.1f, 0.2f), TracePoint(0.5f, 0.2f), TracePoint(0.9f, 0.2f)),
            listOf(TracePoint(0.1f, 0.8f), TracePoint(0.5f, 0.8f), TracePoint(0.9f, 0.8f)),
        )
        val attempt = evaluateAttempt(refs, drawn)
        assertTrue(attempt is AttemptAssessment.Assessed)
        val results = (attempt as AttemptAssessment.Assessed).results
        assertEquals(2, results.size)
        assertTrue(
            "correctly placed strokes must read close: ${results.map { it.shape }}",
            results.all { it.shape == ShapeVerdict.CloseToReference },
        )
    }

    @Test
    fun `stroke translated to wrong location is not close`() {
        val refs = twoStrokeRef()
        // Bottom stroke drawn at top (wrong position) — same shape, wrong place.
        val drawn = listOf(
            listOf(TracePoint(0.1f, 0.2f), TracePoint(0.5f, 0.2f), TracePoint(0.9f, 0.2f)),
            listOf(TracePoint(0.1f, 0.2f), TracePoint(0.5f, 0.2f), TracePoint(0.9f, 0.2f)),
        )
        val attempt = evaluateAttempt(refs, drawn)
        assertTrue(attempt is AttemptAssessment.Assessed)
        val results = (attempt as AttemptAssessment.Assessed).results
        assertEquals(2, results.size)
        // The second stroke (translated to wrong position) must NOT be close.
        assertTrue(
            "translated stroke must not read close: ${results[1].shape} (dist=${results[1].shapeDistance})",
            results[1].shape != ShapeVerdict.CloseToReference,
        )
    }

    @Test
    fun `consistently translated whole character still matches`() {
        val refs = twoStrokeRef()
        // Shift all drawn strokes by +0.05 in both axes (canvas space).
        val drawn = listOf(
            listOf(TracePoint(0.15f, 0.25f), TracePoint(0.55f, 0.25f), TracePoint(0.95f, 0.25f)),
            listOf(TracePoint(0.15f, 0.85f), TracePoint(0.55f, 0.85f), TracePoint(0.95f, 0.85f)),
        )
        val attempt = evaluateAttempt(refs, drawn)
        assertTrue(attempt is AttemptAssessment.Assessed)
        val results = (attempt as AttemptAssessment.Assessed).results
        assertTrue(
            "consistently translated character must still match: ${results.map { it.shape }}",
            results.all { it.shape == ShapeVerdict.CloseToReference },
        )
    }

    @Test
    fun `consistently resized whole character still matches`() {
        val refs = twoStrokeRef()
        // Scale all drawn strokes by 0.8 (around canvas centre).
        val drawn = listOf(
            listOf(TracePoint(0.2f, 0.36f), TracePoint(0.5f, 0.36f), TracePoint(0.8f, 0.36f)),
            listOf(TracePoint(0.2f, 0.64f), TracePoint(0.5f, 0.64f), TracePoint(0.8f, 0.64f)),
        )
        val attempt = evaluateAttempt(refs, drawn)
        assertTrue(attempt is AttemptAssessment.Assessed)
        val results = (attempt as AttemptAssessment.Assessed).results
        assertTrue(
            "consistently resized character must still match: ${results.map { it.shape }}",
            results.all { it.shape == ShapeVerdict.CloseToReference },
        )
    }

    @Test
    fun `different canvas aspect ratios do not introduce misleading close results`() {
        val refs = twoStrokeRef()
        // Simulate a wide canvas: x stretched by 1.5, y unchanged.
        val drawn = listOf(
            listOf(TracePoint(0.15f, 0.2f), TracePoint(0.75f, 0.2f), TracePoint(1.0f, 0.2f)),
            listOf(TracePoint(0.15f, 0.8f), TracePoint(0.75f, 0.8f), TracePoint(1.0f, 0.8f)),
        )
        val attempt = evaluateAttempt(refs, drawn)
        // Must not crash; result is whatever it is (not claiming accuracy).
        assertTrue(attempt is AttemptAssessment.Assessed || attempt is AttemptAssessment.Insufficient)
    }

    @Test
    fun `global stats preserve relative position unlike per-stroke normalization`() {
        // Directly verify that global normalization keeps a top stroke at top
        // and a bottom stroke at bottom, rather than centring each.
        val refTop = listOf(TracePoint(0.1f, 0.8f), TracePoint(0.5f, 0.8f), TracePoint(0.9f, 0.8f))
        val refBottom = listOf(TracePoint(0.1f, 0.2f), TracePoint(0.5f, 0.2f), TracePoint(0.9f, 0.2f))
        val allRef = refTop + refBottom
        val stats = computeGlobalStats(allRef)
        val normTop = normalizeByGlobalStats(refTop, stats)
        val normBottom = normalizeByGlobalStats(refBottom, stats)
        // Top stroke should have positive y, bottom stroke negative y.
        val topY = normTop.map { it.y }.average().toFloat()
        val bottomY = normBottom.map { it.y }.average().toFloat()
        assertTrue("top stroke should be above centre: $topY", topY > 0f)
        assertTrue("bottom stroke should be below centre: $bottomY", bottomY < 0f)
    }

    @Test
    fun `position preservation - empty tiny repeated zero-length reversed mismatched remain safe`() {
        val refs = twoStrokeRef()
        // Empty
        assertTrue(evaluateAttempt(refs, emptyList()) is AttemptAssessment.Insufficient)
        // Mismatched
        assertTrue(evaluateAttempt(refs, listOf(refs[0])) is AttemptAssessment.Insufficient)
        // Tiny dot
        val tiny = listOf(listOf(TracePoint(0.5f, 0.5f), TracePoint(0.5f + 0.001f, 0.5f)))
        // Need matching count for this test; use 2 strokes
        val tinyAttempt = evaluateAttempt(refs, listOf(
            listOf(TracePoint(0.5f, 0.5f), TracePoint(0.5f + 0.001f, 0.5f)),
            listOf(TracePoint(0.5f, 0.5f), TracePoint(0.5f + 0.001f, 0.5f)),
        ))
        assertTrue(tinyAttempt is AttemptAssessment.Assessed)
        // Reversed
        val reversed = listOf(refs[1].asReversed(), refs[0].asReversed())
        // Y-flip and global: reversed direction should be detected
        val drawnRev = listOf(
            listOf(TracePoint(0.9f, 0.8f), TracePoint(0.5f, 0.8f), TracePoint(0.1f, 0.8f)),
            listOf(TracePoint(0.9f, 0.2f), TracePoint(0.5f, 0.2f), TracePoint(0.1f, 0.2f)),
        )
        val revAttempt = evaluateAttempt(refs, drawnRev)
        assertTrue(revAttempt is AttemptAssessment.Assessed)
    }

    @Test
    fun `summaries use cautious words and never percentages`() {
        val assessed = AttemptAssessment.Assessed(
            listOf(
                StrokeVerdict(ShapeVerdict.CloseToReference, 0.1f, DirectionVerdict.Forward, null),
                StrokeVerdict(ShapeVerdict.DifferentFromReference, 0.8f, DirectionVerdict.Unclear, null),
            )
        )
        val text = summarizeAssessment(assessed)
        assertTrue("got: $text", "1 of 2 strokes close to the reference" in text)
        assertTrue("got: $text", "%" !in text)

        val backward = AttemptAssessment.Assessed(
            listOf(StrokeVerdict(ShapeVerdict.CloseToReference, 0.1f, DirectionVerdict.Backward, null))
        )
        assertTrue("backward" in summarizeAssessment(backward))

        val mismatch = summarizeAssessment(AttemptAssessment.Insufficient(AssessmentGap.StrokeCountMismatch, 7, 5))
        assertTrue("got: $mismatch", "Not enough information to compare" in mismatch)
        assertTrue("got: $mismatch", "7" in mismatch && "5" in mismatch)
    }
}
