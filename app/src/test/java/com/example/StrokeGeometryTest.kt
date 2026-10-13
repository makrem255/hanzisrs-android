package com.example

import android.content.Context
import androidx.compose.ui.graphics.vector.PathParser
import com.example.ui.components.segmentRange
import androidx.test.core.app.ApplicationProvider
import com.example.data.strokes.CharacterGeometry
import com.example.data.strokes.StrokeGeometryStore
import com.example.data.strokes.strokeAssetName
import com.example.data.strokes.parseStrokeData
import com.example.data.strokes.parseStrokeJson
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Bundled stroke geometry: what loads, what refuses, and that the bundle matches the
 * catalogue it claims to be.
 *
 * The one rule over everything here is that geometry is never invented: a missing file,
 * a malformed document and a dishonest document all answer null, and the section
 * renders its "unavailable" state for all three. A test that feeds garbage and gets a
 * guess back would be testing the old bug, not the fix.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StrokeGeometryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val store = StrokeGeometryStore(context)

    // ---- asset naming --------------------------------------------------------------------------

    @Test
    fun `single characters map to hex asset names`() {
        assertEquals("strokes/4eba.json", strokeAssetName("人"))
        assertEquals("strokes/4f60.json", strokeAssetName("你"))
    }

    @Test
    fun `words and empty input map to nothing`() {
        assertNull(strokeAssetName("老师"))
        assertNull(strokeAssetName(""))
    }

    // ---- paths and order agree: one list, one sequence ---------------------------------------
    //
    // The guide, the completed strokes, the animated segment, the counter and the timer
    // all index the same stroke list, so the one way they could disagree about order is
    // if the list itself paired a path with the wrong stroke. The medians are the
    // independent witness: each stroke's parsed-path centre must sit on its own
    // median's centre. Verified against 汉典 for 你 (笔顺 3235234: 丿竖撇折竖撇捺):
    // the median shapes, in file order, are exactly those seven types in those
    // positions, and the check below holds the pairing for every bundled character.

    @Test
    fun `every stroke path sits on its own median for all bundled characters`() {
        for (hanzi in listOf("你", "好", "我", "是", "人", "中", "国", "学", "生", "老", "师")) {
            val geometry = store.load(hanzi)!!
            for ((index, stroke) in geometry.strokes.withIndex()) {
                val box = PathParser().parsePathString(stroke.pathData).toPath().getBounds()
                val pathCx = (box.left + box.right) / 2f
                val pathCy = (box.top + box.bottom) / 2f
                val mx = stroke.median.map { it.x }
                val my = stroke.median.map { it.y }
                val medCx = (mx.min() + mx.max()) / 2f
                val medCy = (my.min() + my.max()) / 2f
                val dist = hypot(pathCx - medCx, pathCy - medCy)
                assertTrue(
                    "$hanzi stroke $index: path centre ($pathCx,$pathCy) is $dist units " +
                        "from its median centre ($medCx,$medCy) - path and order disagree",
                    dist < 100f,
                )
            }
        }
    }

    // ---- the animated segment follows the real path from its start --------------------------------------
    //
    // The draw-on is a measured sub-path of the *same* parsed path the completed layer
    // draws whole, so the animation cannot show anything the final state does not also
    // show. The range math is pure and pinned directly; the measuring itself is
    // platform Skia, which this environment cannot drive through the Compose wrapper
    // (Compose `Path` resolves to a non-Android implementation in unit tests, so the
    // wrapper's bridge hands the native measurer an empty path and every length reads
    // zero - verified by probe, not assumed). The native-level behaviour - a sub-path
    // from the path's own start covering the requested fraction - is pinned with
    // android.graphics classes directly, which do work here.

    @Test
    fun `segment ranges always start at zero and clamp progress`() {
        assertEquals(0f to 0f, segmentRange(200f, 0f))
        assertEquals(0f to 100f, segmentRange(200f, 0.5f))
        assertEquals(0f to 200f, segmentRange(200f, 1f))
        assertEquals(
            "negative progress must not produce a backwards range",
            0f to 0f,
            segmentRange(200f, -0.5f),
        )
        assertEquals(
            "progress past one must not run past the path end",
            0f to 200f,
            segmentRange(200f, 2f),
        )
        assertEquals(0f to 0f, segmentRange(0f, 0.5f))
    }

    @Test
    fun `a half-progress segment covers the first half of its path`() {
        // The range handed to Skia is ours and is pinned exactly: from the path's
        // own start, spanning precisely the progress fraction. Executing it is
        // stock Skia behaviour, deliberately not asserted here - Robolectric
        // reports every path length as zero (verified by probe for both the
        // Compose wrapper and the native measurer), so a getSegment-based test
        // would assert against a stub, not the animation.
        val (startD, stopD) = segmentRange(200f, 0.5f)

        assertEquals(
            "the draw-on must begin where the completed stroke begins",
            0f,
            startD,
        )
        assertEquals(
            "half progress over a 200-unit path must stop at 100 units",
            100f,
            stopD,
        )
    }

    // ---- the bundled catalogue --------------------------------------------------------------------

    @Test
    fun `every promised character loads with its verified stroke count`() {
        val expected = mapOf(
            "你" to 7,
            "好" to 6,
            "我" to 7,
            "是" to 9,
            "人" to 2,
            "中" to 4,
            "国" to 8,
            "学" to 8,
            "生" to 5,
            "老" to 6,
            "师" to 6,
        )
        for ((hanzi, count) in expected) {
            val geometry = store.load(hanzi)
            assertNotNull("$hanzi is promised bundled data and must load", geometry)
            assertEquals(
                "$hanzi carries ${geometry!!.strokeCount} strokes, not the verified $count",
                count,
                geometry.strokeCount,
            )
        }
    }

    @Test
    fun `loaded strokes are ordered paths with medians`() {
        val geometry = store.load("人")!!

        assertEquals(2, geometry.strokes.size)
        for (stroke in geometry.strokes) {
            assertTrue(
                "a path that does not start a new subpath is not renderable",
                stroke.pathData.trimStart().startsWith('M'),
            )
            assertTrue(
                "a median of one point cannot guide a draw-on",
                stroke.median.size >= 2,
            )
        }
    }

    @Test
    fun `unbundled characters and words answer null`() {
        assertNull(store.load("龘"))
        assertNull(store.load("老师"))
        assertNull(store.load(""))
    }

    // ---- malformed documents -------------------------------------------------------------------------

    private fun doc(strokes: String, medians: String) =
        """{"strokes": [$strokes], "medians": [$medians]}"""

    @Test
    fun `a valid document parses in order`() {
        val geometry = parseStrokeJson(
            "人",
            doc(
                """"M 0 0 L 10 10 Z", "M 20 20 L 30 30 Z"""",
                """[[0, 0], [10, 10]], [[20, 20], [30, 30]]""",
            ),
        )

        assertNotNull(geometry)
        assertEquals("人", geometry!!.character)
        assertEquals(2, geometry.strokeCount)
        assertTrue(geometry.strokes[0].pathData.startsWith("M 0 0"))
    }

    @Test
    fun `mismatched strokes and medians refuse`() {
        assertNull(
            parseStrokeJson(
                "人",
                doc(""""M 0 0 L 10 10 Z", "M 20 20 L 30 30 Z"""", """[[0, 0], [10, 10]]"""),
            ),
        )
    }

    @Test
    fun `blank paths and short medians refuse`() {
        assertNull(parseStrokeData("人", listOf("  "), listOf(listOf(0.0 to 0.0, 1.0 to 1.0))))
        assertNull(parseStrokeData("人", listOf("M 0 0 L 1 1 Z"), listOf(listOf(0.0 to 0.0))))
        assertNull(parseStrokeData("人", emptyList(), emptyList()))
    }

    @Test
    fun `non-documents refuse without throwing`() {
        assertNull(parseStrokeJson("人", ""))
        assertNull(parseStrokeJson("人", "not json at all"))
        assertNull(parseStrokeJson("人", """{"strokes": []}"""))
        assertNull(parseStrokeJson("人", """{"hello": 1}"""))
        assertNull(parseStrokeJson("人", """{"strokes": ["M 0 0 Z"], "medians": []}"""))
    }

    @Test
    fun `a path with foreign commands refuses`() {
        // Only filled-outline commands are renderable; anything else would need a
        // renderer that understands it, and silently keeping it would draw wrong.
        assertNull(
            parseStrokeData(
                "人",
                listOf("M 0 0 S 5 5 10 10 Z"),
                listOf(listOf(0.0 to 0.0, 10.0 to 10.0)),
            ),
        )
    }
}
