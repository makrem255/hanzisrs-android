package com.example.data.strokes

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Offline handwriting comparison for practice mode, version 1.
 *
 * Pure Kotlin, no Compose, no Android: every rule here is unit-testable on the JVM.
 * It compares a learner's drawn strokes against the bundled reference medians and
 * reports cautious, descriptive verdicts - never grades, percentages, mastery, or
 * correctness judgments, and never anything that could reach SRS scheduling.
 *
 * ## Coordinate conventions, verified, not assumed
 *
 * Reference medians grow upward in y (dataset convention: larger y is higher; a
 * top-to-bottom stroke has *decreasing* y... see below). Canvas fractions grow
 * downward in y (top of the canvas is 0). [evaluateAttempt] negates drawn y once,
 * in one place, so the two sides share an orientation before anything is
 * compared. A top-down stroke drawn on screen matches a top-down reference
 * median if and only if this harmonization is right, which is exactly what the
 * y-convention test pins - remove it and that test fails.
 *
 * Wait, precisely: reference medians run from the stroke's visual top (large y)
 * to its visual bottom (small y). A drawn top-down stroke runs from small
 * fraction-y to large fraction-y. Negating drawn y maps drawn-top (0.2) to -0.2
 * and drawn-bottom (0.8) to -0.8, so drawn order top-down becomes
 * decreasing-y - the same direction as the reference. Directions then compare
 * like with like.
 *
 * ## What v1 deliberately does not do
 *
 * Ordered pairing only (i-th drawn stroke against i-th reference stroke): a
 * learner who merges or splits strokes gets an explicit count mismatch instead
 * of a forced alignment. No splitting/merging search - that is stated as a
 * limitation, not attempted badly. Thresholds are provisional and named as
 * such; they are tripwires for "clearly close" vs "clearly different", not
 * validated measures of handwriting quality.
 */

// ---- vocabulary --------------------------------------------------------------------------------

/** One 2D point in either coordinate space. Plain floats, no UI types. */
data class TracePoint(val x: Float, val y: Float)

/** How many samples a stroke is resampled to before comparison. */
const val RESAMPLE_POINTS = 16

/**
 * Provisional shape bands. A mean point distance under [PROVISIONAL_CLOSE] reads
 * as close; at or above [PROVISIONAL_DIFFERENT] reads as different; between the
 * two is explicitly unclear. Uncalibrated against real handwriting - the names
 * say provisional because they are.
 */
const val PROVISIONAL_CLOSE = 0.35f
const val PROVISIONAL_DIFFERENT = 0.6f

/**
 * Minimum bounding span of a drawn stroke, in canvas fractions. Below this a
 * stroke is a dot or a tremor, and neither its shape nor its direction can be
 * assessed - dots match everything and prove nothing.
 */
const val MIN_DRAWN_SPAN = 0.03f

/**
 * Relative margin for calling a direction. When forward and reversed distances
 * agree within this fraction, the stroke is symmetric, retraced, or otherwise
 * directionless, and must not be labelled either way.
 */
const val DIRECTION_MARGIN_REL = 0.15f

/** Why something could not be assessed. Every dead end names itself. */
enum class AssessmentGap {
    /** Fewer than two distinct points: taps, single samples, total standstills. */
    TooShort,

    /** Distinct points exist but span almost nothing: dots and tremors. */
    TooSmall,

    /** Forward and reversed match equally well: symmetric, retraced, directionless. */
    DirectionUnclear,

    /** Between the provisional bands: neither close nor clearly different. */
    SimilarityUnclear,

    /** Nothing was drawn at all. */
    EmptyAttempt,

    /** Drawn count differs from reference count: ordered pairing cannot proceed. */
    StrokeCountMismatch,

    /** No reference medians (character without bundled geometry). */
    NoReferenceData,
}

/** Shape verdict for one stroke. Cautious words only. */
enum class ShapeVerdict {
    CloseToReference,
    DifferentFromReference,
    NotEnoughInfo,
}

/** Direction verdict for one stroke. Never guessed when ambiguous. */
enum class DirectionVerdict {
    Forward,
    Backward,
    Unclear,
}

/** One stroke's verdict: shape and direction reported independently. */
data class StrokeVerdict(
    val shape: ShapeVerdict,
    val shapeDistance: Float,
    val direction: DirectionVerdict,
    val gap: AssessmentGap?,
)

/** An attempt's verdict: either explicitly insufficient, or per-stroke results. */
sealed interface AttemptAssessment {
    data class Insufficient(
        val gap: AssessmentGap,
        val expectedStrokes: Int,
        val drawnStrokes: Int,
    ) : AttemptAssessment

    data class Assessed(val results: List<StrokeVerdict>) : AttemptAssessment
}

// ---- geometry ------------------------------------------------------------------------------------

/** Drops consecutive duplicates (finger pauses record the same point repeatedly). */
internal fun dedupTrace(points: List<TracePoint>): List<TracePoint> {
    if (points.isEmpty()) return points
    val out = ArrayList<TracePoint>(points.size)
    out.add(points[0])
    for (i in 1 until points.size) {
        if (points[i] != out.last()) out.add(points[i])
    }
    return out
}

/** Largest side of the bounding box: 0 for points and standstills. */
internal fun traceSpan(points: List<TracePoint>): Float {
    if (points.isEmpty()) return 0f
    var minX = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE
    var minY = Float.MAX_VALUE
    var maxY = -Float.MAX_VALUE
    for (p in points) {
        if (!p.x.isFinite() || !p.y.isFinite()) return 0f
        if (p.x < minX) minX = p.x
        if (p.x > maxX) maxX = p.x
        if (p.y < minY) minY = p.y
        if (p.y > maxY) maxY = p.y
    }
    return max(maxX - minX, maxY - minY)
}

/**
 * Resamples to exactly [n] arc-length-equidistant points, preserving order.
 *
 * A degenerate (zero-length) path resamples to n copies of its point rather
 * than dividing by zero; the span gate keeps such strokes out of assessment.
 */
internal fun resampleTrace(points: List<TracePoint>, n: Int = RESAMPLE_POINTS): List<TracePoint> {
    require(n > 0) { "resampling needs at least one point" }
    if (points.isEmpty()) return emptyList()
    if (points.size == 1) return List(n) { points[0] }
    val total = cumulativeLengths(points).last()
    if (total <= 0f) return List(n) { points[0] }
    return sampleByArcLength(points, total, n)
}

private fun cumulativeLengths(points: List<TracePoint>): List<Float> {
    val out = ArrayList<Float>(points.size)
    out.add(0f)
    for (i in 1 until points.size) {
        val dx = points[i].x - points[i - 1].x
        val dy = points[i].y - points[i - 1].y
        out.add(out.last() + sqrt(dx * dx + dy * dy))
    }
    return out
}

private fun sampleByArcLength(
    points: List<TracePoint>,
    total: Float,
    n: Int,
): List<TracePoint> {
    val cum = cumulativeLengths(points)
    val out = ArrayList<TracePoint>(n)
    var j = 0
    for (i in 0 until n) {
        val target = total * i / (n - 1).coerceAtLeast(1)
        while (j < cum.size - 2 && cum[j + 1] < target) j++
        val a = points[j]
        val b = points[j + 1]
        val seg = (cum[j + 1] - cum[j]).takeIf { it > 0f } ?: 1f
        val u = ((target - cum[j]) / seg).coerceIn(0f, 1f)
        out.add(TracePoint(a.x + (b.x - a.x) * u, a.y + (b.y - a.y) * u))
    }
    return out
}

/**
 * Centroid + RMS-radius normalization with a uniform scale, so position and size
 * vanish but aspect and orientation survive. Translation invariance is what lets
 * canvas fractions and dataset units compare at all; uniformity is what keeps a
 * tall stroke from matching a wide one.
 */
internal fun normalizeTrace(points: List<TracePoint>): List<TracePoint> {
    if (points.isEmpty()) return points
    val cx = points.sumOf { it.x.toDouble() }.toFloat() / points.size
    val cy = points.sumOf { it.y.toDouble() }.toFloat() / points.size
    var variance = 0f
    for (p in points) {
        val dx = p.x - cx
        val dy = p.y - cy
        variance += dx * dx + dy * dy
    }
    val radius = sqrt(variance / points.size)
    if (radius <= 0f) return points.map { TracePoint(0f, 0f) }
    return points.map { TracePoint((it.x - cx) / radius, (it.y - cy) / radius) }
}

/** Mean point distance over two equally sized, equally ordered sequences. */
internal fun shapeDistance(a: List<TracePoint>, b: List<TracePoint>): Float {
    require(a.size == b.size) { "compared sequences must be resampled first" }
    if (a.isEmpty()) return 0f
    var sum = 0f
    for (i in a.indices) {
        val dx = a[i].x - b[i].x
        val dy = a[i].y - b[i].y
        sum += sqrt(dx * dx + dy * dy)
    }
    return sum / a.size
}

// ---- assessment ------------------------------------------------------------------------------------

/**
 * Assesses one drawn stroke against one reference median.
 *
 * The drawn side is harmonized into the reference orientation first (see the
 * file KDoc): without that step a correct top-down stroke reads as backward.
 * Assessability is decided before any verdict: too short, too small, or
 * directionally ambiguous strokes report [AssessmentGap], never a guess.
 */
internal fun assessPair(
    reference: List<TracePoint>,
    drawnRaw: List<TracePoint>,
): StrokeVerdict {
    val drawn = dedupTrace(drawnRaw)
    if (drawn.size < 2) {
        return StrokeVerdict(ShapeVerdict.NotEnoughInfo, Float.NaN, DirectionVerdict.Unclear, AssessmentGap.TooShort)
    }
    if (traceSpan(drawn) < MIN_DRAWN_SPAN) {
        return StrokeVerdict(ShapeVerdict.NotEnoughInfo, Float.NaN, DirectionVerdict.Unclear, AssessmentGap.TooSmall)
    }
    val refDeduped = dedupTrace(reference)
    if (refDeduped.size < 2) {
        return StrokeVerdict(ShapeVerdict.NotEnoughInfo, Float.NaN, DirectionVerdict.Unclear, AssessmentGap.TooShort)
    }
    val oriented = drawn.map { TracePoint(it.x, -it.y) }
    val ref = normalizeTrace(resampleTrace(refDeduped))
    val mine = normalizeTrace(resampleTrace(oriented))
    val forward = shapeDistance(ref, mine)
    val reversed = shapeDistance(ref, mine.asReversed())

    val direction = when {
        abs(forward - reversed) <= DIRECTION_MARGIN_REL * max(forward, reversed) ->
            DirectionVerdict.Unclear
        forward < reversed -> DirectionVerdict.Forward
        else -> DirectionVerdict.Backward
    }
    val shape = when {
        forward < PROVISIONAL_CLOSE -> ShapeVerdict.CloseToReference
        forward >= PROVISIONAL_DIFFERENT -> ShapeVerdict.DifferentFromReference
        else -> null
    }
    return if (shape != null) {
        StrokeVerdict(shape, forward, direction, gap = null)
    } else {
        StrokeVerdict(ShapeVerdict.NotEnoughInfo, forward, direction, AssessmentGap.SimilarityUnclear)
    }
}

/**
 * Assesses a whole attempt with ordered pairing: drawn stroke i against reference
 * stroke i.
 *
 * Ordered pairing is a v1 limitation, stated plainly: a learner who merges two
 * strokes into one (or splits one into two) shifts every later pairing, so the
 * only honest response to unequal counts is [AttemptAssessment.Insufficient]
 * rather than a forced alignment. No splitting/merging search is attempted.
 */
fun evaluateAttempt(
    reference: List<List<TracePoint>>?,
    drawn: List<List<TracePoint>>,
): AttemptAssessment {
    if (reference == null) {
        return AttemptAssessment.Insufficient(AssessmentGap.NoReferenceData, 0, drawn.size)
    }
    if (drawn.isEmpty()) {
        return AttemptAssessment.Insufficient(AssessmentGap.EmptyAttempt, reference.size, 0)
    }
    if (drawn.size != reference.size) {
        return AttemptAssessment.Insufficient(
            AssessmentGap.StrokeCountMismatch,
            expectedStrokes = reference.size,
            drawnStrokes = drawn.size,
        )
    }
    return AttemptAssessment.Assessed(
        reference.zip(drawn) { ref, one -> assessPair(ref, one) },
    )
}

/**
 * One honest sentence about an assessment, in the app's cautious wording. No
 * percentages, no grades, no mastery - just counts of what the verdicts say.
 */
fun summarizeAssessment(assessment: AttemptAssessment): String = when (assessment) {
    is AttemptAssessment.Insufficient -> when (assessment.gap) {
        AssessmentGap.EmptyAttempt ->
            "Not enough information to compare - nothing was drawn yet."
        AssessmentGap.StrokeCountMismatch ->
            "Not enough information to compare - this character has " +
                "${assessment.expectedStrokes} strokes and ${assessment.drawnStrokes} were drawn."
        AssessmentGap.NoReferenceData ->
            "Not enough information to compare - no reference data for this character."
        else ->
            "Not enough information to compare."
    }
    is AttemptAssessment.Assessed -> {
        val close = assessment.results.count { it.shape == ShapeVerdict.CloseToReference }
        val total = assessment.results.size
        val backward = assessment.results.count { it.direction == DirectionVerdict.Backward }
        buildString {
            append("$close of $total strokes close to the reference.")
            if (backward > 0) {
                append(if (backward == 1) " One stroke looks drawn backward." else " $backward strokes look drawn backward.")
            }
        }
    }
}
