package com.example

import android.content.Context
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.activity.ComponentActivity
import androidx.core.view.drawToBitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.example.data.strokes.StrokeGeometryStore
import com.example.data.strokes.StrokePoint
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.components.StrokeGeometryCanvas
import com.example.ui.components.StrokeGeometryColors
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * The renderer draws characters the right way round - proved with pixels, not promises.
 *
 * Two independent halves. First, the bundled data itself is checked for the orientation
 * every learner knows: in 你/好/我 the left component's strokes sit left of the last
 * stroke, measured straight from the median points with plain arithmetic. Second, the
 * real production renderer draws the real bundled paths on a real Canvas, the bitmap
 * is captured, and each cell is compared against a from-scratch software rasterizer
 * written for this test alone (own tokenizer, own flattener, own fill, own fit math).
 * A mirror or inversion anywhere - data, parser, transform, draw calls - drops cell
 * agreement toward a coin flip; the bar is 85%.
 *
 * Nothing here shares code with the renderer under test except the bundled JSON files
 * themselves, which are the ground truth both sides answer to.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class StrokeOrientationTest {

    @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val store = StrokeGeometryStore(context)

    private val palette = StrokeGeometryColors(
        guide = Color.Transparent,
        completed = Color.Black,
        current = Color.Black,
    )

    // ---- data-space orientation: the bundle says left is left -----------------------------------

    private fun meanX(points: List<StrokePoint>): Double =
        points.sumOf { it.x.toDouble() } / points.size

    @Test
    fun `left components sit left of right components in the data`() {
        // First vs last stroke medians: 亻 vs 尔, 女 vs 子, left sweep vs right 戈.
        for (hanzi in listOf("你", "好", "我")) {
            val geometry = store.load(hanzi)!!
            val first = meanX(geometry.strokes.first().median)
            val last = meanX(geometry.strokes.last().median)
            assertTrue(
                "$hanzi reads left-to-right in its own data: first-stroke mean x $first " +
                    "is not left of last-stroke mean x $last",
                first < last,
            )
        }
    }

    @Test
    fun `upper strokes sit above lower strokes in the data`() {
        // 是: an upper vertical band (stroke 5) vs the lower horizontal band (stroke 1).
        // y grows downward in the dataset, so "above" is the smaller mean.
        val geometry = store.load("是")!!
        val upper = geometry.strokes[5].median.sumOf { it.y.toDouble() } / geometry.strokes[5].median.size
        val lower = geometry.strokes[1].median.sumOf { it.y.toDouble() } / geometry.strokes[1].median.size
        assertTrue(
            "是 reads top-to-bottom in its own data: upper mean y $upper is not above lower $lower",
            upper < lower,
        )
    }

    // ---- rendered pixels agree with an independent rasterizer --------------------------------------

    private data class Segment(val ax: Double, val ay: Double, val bx: Double, val by: Double)

    /** Own SVG subset parser: absolute M/L/Q/C/Z only - anything else throws, never guesses. */
    private fun flattenPath(data: String, steps: Int = 16): List<Segment> {
        val tokens = data.replace(",", " ")
            .split(Regex("(?<=[MLQCZmlqcz])|(?=[MLQCZmlqcz])|\\s+"))
            .filter { it.isNotBlank() }
        val segs = mutableListOf<Segment>()
        var cur = 0.0 to 0.0
        var start = cur
        var i = 0
        fun num(): Double = tokens[++i].toDouble()
        while (i < tokens.size) {
            when (tokens[i]) {
                "M" -> {
                    val x = num()
                    val y = num()
                    cur = x to y
                    start = cur
                }
                "L" -> {
                    val x = num()
                    val y = num()
                    val next = x to y
                    segs += Segment(cur.first, cur.second, next.first, next.second)
                    cur = next
                }
                "Q" -> {
                    val x1 = num()
                    val y1 = num()
                    val x = num()
                    val y = num()
                    val (px, py) = cur
                    for (s in 1..steps) {
                        val u = s.toDouble() / steps
                        val w0 = (1 - u) * (1 - u)
                        val w1 = 2 * (1 - u) * u
                        val w2 = u * u
                        val nx = w0 * px + w1 * x1 + w2 * x
                        val ny = w0 * py + w1 * y1 + w2 * y
                        segs += Segment(cur.first, cur.second, nx, ny)
                        cur = nx to ny
                    }
                    cur = x to y
                }
                "C" -> {
                    val x1 = num()
                    val y1 = num()
                    val x2 = num()
                    val y2 = num()
                    val x = num()
                    val y = num()
                    val (px, py) = cur
                    for (s in 1..steps) {
                        val u = s.toDouble() / steps
                        val v = 1 - u
                        val nx = v * v * v * px + 3 * v * v * u * x1 + 3 * v * u * u * x2 + u * u * u * x
                        val ny = v * v * v * py + 3 * v * v * u * y1 + 3 * v * u * u * y2 + u * u * u * y
                        segs += Segment(cur.first, cur.second, nx, ny)
                        cur = nx to ny
                    }
                    cur = x to y
                }
                "Z" -> {
                    segs += Segment(cur.first, cur.second, start.first, start.second)
                    cur = start
                }
                else -> throw IllegalArgumentException("unsupported path command: ${tokens[i]}")
            }
            i++
        }
        return segs
    }

    /** Even-odd scanline fill on a grid, in bitmap-pixel space. */
    private fun referenceInk(
        pathData: List<String>,
        grid: Int,
        bmpW: Int,
        bmpH: Int,
        padPx: Float,
    ): Array<BooleanArray> {
        val segs = pathData.flatMap { flattenPath(it) }
        val xs = segs.flatMap { listOf(it.ax, it.bx) }
        val ys = segs.flatMap { listOf(it.ay, it.by) }
        // The same fit rule the renderer uses, reimplemented in plain arithmetic: uniform
        // scale from the tighter axis, centred, padding respected. Sharing the rule is
        // correct here because orientation - not framing - is under test; a mirror in
        // either implementation still tanks agreement toward a coin flip.
        val spanX = (xs.max() - xs.min()).takeIf { it > 0 } ?: 1.0
        val spanY = (ys.max() - ys.min()).takeIf { it > 0 } ?: 1.0
        val scale = minOf((bmpW - padPx * 2f) / spanX, (bmpH - padPx * 2f) / spanY)
        val dx = (bmpW - spanX * scale) / 2.0 - xs.min() * scale
        val dy = (bmpH - spanY * scale) / 2.0 - ys.min() * scale
        fun px(x: Double): Double = x * scale + dx
        fun py(y: Double): Double = y * scale + dy
        return Array(grid) { r ->
            BooleanArray(grid) { c ->
                val x = bmpW * (c + 0.5) / grid
                val y = bmpH * (r + 0.5) / grid
                var inside = false
                for (s in segs) {
                    val ay = py(s.ay)
                    val by = py(s.by)
                    if ((ay > y) != (by > y)) {
                        val xin = px(s.ax) + (px(s.bx) - px(s.ax)) * (y - ay) / (by - ay)
                        if (x < xin) inside = !inside
                    }
                }
                inside
            }
        }
    }

    /** The real renderer, rasterized: full character, real view bitmap. */
    private fun renderedBitmap(hanzi: String): android.graphics.Bitmap {
        val geometry = store.load(hanzi)!!
        composeTestRule.setContent {
            MyApplicationTheme {
                StrokeGeometryCanvas(
                    geometry = geometry,
                    revealedSteps = geometry.strokeCount,
                    currentProgress = 0f,
                    colors = palette,
                    modifier = Modifier.testTag("stroke_geometry_canvas"),
                )
            }
        }
        composeTestRule.waitForIdle()
        // The whole production path: composition, Canvas, fit transform, drawPath -
        // rasterized by the framework itself, not replayed by this test.
        val content = composeTestRule.activity.findViewById<android.view.View>(
            android.R.id.content,
        )
        return content.drawToBitmap()
    }

    private fun bitmapInkGrid(
        bitmap: android.graphics.Bitmap,
        grid: Int,
    ): Array<BooleanArray> {
        return Array(grid) { r ->
            BooleanArray(grid) { c ->
                val x = (bitmap.width * (c + 0.5) / grid).toInt().coerceIn(0, bitmap.width - 1)
                val y = (bitmap.height * (r + 0.5) / grid).toInt().coerceIn(0, bitmap.height - 1)
                // Any real coverage counts: edges antialias on both sides, interiors agree.
                (bitmap.getPixel(x, y) ushr 24) > 16
            }
        }
    }

    private fun agreement(a: Array<BooleanArray>, b: Array<BooleanArray>): Double {
        var same = 0
        var total = 0
        for (r in a.indices) {
            for (c in a[r].indices) {
                total++
                if (a[r][c] == b[r][c]) same++
            }
        }
        return same.toDouble() / total
    }

    // One setContent per test - the rule from the exceeded-timeout school of hard
    // knocks - so each character gets its own test calling the shared checker once.
    @Test
    fun `ni renders upright`() = assertPixelsMatch("你")

    @Test
    fun `hao renders upright`() = assertPixelsMatch("好")

    @Test
    fun `wo renders upright`() = assertPixelsMatch("我")

    @Test
    fun `shi renders upright`() = assertPixelsMatch("是")

    @Test
    fun `zhong renders upright`() = assertPixelsMatch("中")

    private fun assertPixelsMatch(hanzi: String) {
        // 85%: interiors must agree cell-for-cell; only antialiased edges may differ.
        // A mirror or inversion anywhere in the pipeline lands near 50%.
        val geometry = store.load(hanzi)!!
        val bitmap = renderedBitmap(hanzi)
        val padPx = 24f * context.resources.displayMetrics.density
        val expected = referenceInk(
            geometry.strokes.map { it.pathData },
            48,
            bitmap.width,
            bitmap.height,
            padPx,
        )
        val actual = bitmapInkGrid(bitmap, 48)
        val score = agreement(expected, actual)
        assertTrue(
            "$hanzi renders at agreement=$score - a mirrored or inverted character " +
                "lands near a coin flip",
            score >= 0.85,
        )
    }

    @Test
    fun `rendered ink keeps the data left-right balance`() {
        // Belt and braces against a shared systematic error: the rendered bitmap of 你
        // must carry more ink right of centre (尔, five strokes) than left of it (亻).
        val geometry = store.load("你")!!
        val bitmap = renderedBitmap("你")
        val padPx = 24f * context.resources.displayMetrics.density
        val expected = referenceInk(
            geometry.strokes.map { it.pathData },
            48,
            bitmap.width,
            bitmap.height,
            padPx,
        )
        val grid = bitmapInkGrid(bitmap, 48)
        // The reference must show the same imbalance, or this check proves nothing.
        assertTrue("reference 你 is not right-heavy", inkRight(expected) > inkLeft(expected))
        var left = 0
        var right = 0
        for (row in grid) {
            for (c in row.indices) {
                if (row[c]) {
                    if (c < 24) left++ else right++
                }
            }
        }
        assertTrue(
            "mirrored 你 would put the five-stroke component on the left: left=$left right=$right",
            right > left,
        )
        assertTrue("rendered 你 has no ink at all", left + right > 0)
    }

    private fun inkLeft(grid: Array<BooleanArray>): Int {
        var n = 0
        for (row in grid) for (c in 0 until 24) if (row[c]) n++
        return n
    }

    private fun inkRight(grid: Array<BooleanArray>): Int {
        var n = 0
        for (row in grid) for (c in 24 until 48) if (row[c]) n++
        return n
    }

    @Test
    fun `fit viewport is a positive uniform scale with centring`() {
        val viewport = com.example.ui.components.fitCharViewport(
            contentLeft = 100f,
            contentTop = 50f,
            contentRight = 900f,
            contentBottom = 850f,
            canvasWidth = 400f,
            canvasHeight = 400f,
            padding = 24f,
        )
        assertTrue("scale must be positive, never a flip", viewport.scale > 0f)
        // Uniform: a unit square maps to a square.
        val (ax, ay) = viewport.map(100f, 50f)
        val (bx, by) = viewport.map(200f, 50f)
        val (cx, cy) = viewport.map(100f, 150f)
        assertEquals(bx - ax, cy - ay, 0.001f)
        assertEquals(0f, by - ay, 0.001f)
        assertEquals(0f, cx - ax, 0.001f)
        // Centred: content middle lands on canvas middle.
        val (mx, my) = viewport.map(500f, 450f)
        assertEquals(200f, mx, 0.01f)
        assertEquals(200f, my, 0.01f)
        // Order preserved: left stays left, above stays above - the no-mirror rule.
        val (lx, _) = viewport.map(100f, 450f)
        val (rx, _) = viewport.map(900f, 450f)
        assertTrue("left must stay left of right", lx < rx)
        val (_, ty) = viewport.map(500f, 50f)
        val (_, uy) = viewport.map(500f, 850f)
        assertTrue("above must stay above below", ty < uy)
        // Inside the canvas with padding respected.
        assertTrue("mapped content must respect padding", ax >= 24f - 0.01f && rx <= 400f - 24f + 0.01f)
    }

    @Test
    fun `fit viewport degrades to identity instead of exploding`() {
        val viewport = com.example.ui.components.fitCharViewport(
            contentLeft = 0f,
            contentTop = 0f,
            contentRight = 0f,
            contentBottom = 0f,
            canvasWidth = 0f,
            canvasHeight = 0f,
            padding = 24f,
        )
        assertTrue("degenerate input must still yield a finite positive scale", viewport.scale.isFinite() && viewport.scale > 0f)
        assertTrue("degenerate input must still yield finite offsets", viewport.dx.isFinite() && viewport.dy.isFinite())
    }

    private fun assertEquals(expected: Float, actual: Float, delta: Float) {
        assertTrue(
            "expected $expected but was $actual",
            abs(expected - actual) <= delta,
        )
    }

    private fun assertTrue(message: String, condition: Boolean) {
        if (!condition) throw AssertionError(message)
    }
}


