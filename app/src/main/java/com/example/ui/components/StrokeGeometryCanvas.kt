package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import com.example.data.strokes.CharacterGeometry
import kotlin.math.max
import kotlin.math.min

/**
 * Colours for the geometry renderer, read from the theme by the caller.
 *
 * Passed in rather than read here so this renderer stays a pure function of data +
 * palette: the same geometry must render on the pure-black dark theme and on white,
 * and tests assert the mapping instead of the theme.
 */
data class StrokeGeometryColors(
    /** Every stroke, faintly: the character's full shape as context. */
    val guide: Color,
    /** Strokes already demonstrated, solid. */
    val completed: Color,
    /** The stroke being drawn right now, solid and distinct from [completed]. */
    val current: Color,
)

/**
 * A character drawn from its real stroke paths, revealing them in writing order.
 *
 * @param geometry verified per-stroke paths, in writing order. List position *is* the
 *   order; nothing here re-sorts, skips or invents.
 * @param revealedSteps how many leading strokes are fully drawn.
 * @param currentProgress 0..1 draw-on progress of the stroke at index [revealedSteps].
 *   Values outside the range are clamped, never trusted.
 * @param colors theme-resolved palette.
 *
 * ## Coordinate handling
 *
 * The dataset lives in its own space (roughly x 0..900, y -124..900) where larger
 * y is HIGHER - its documentation states the y-axis decreases downward and mandates
 * a `scale(1, -1)` flip for display. Fitting is delegated to [fitCharViewport],
 * which applies exactly that reflection about the content midline and then fits
 * normally: aspect preserved, no stretching, no clipping, centred. A degenerate
 * (empty) bounds falls back to identity rather than dividing by zero.
 *
 * ## What each layer means
 *
 * Guide first (all strokes, faint): the character's shape as context. Completed
 * strokes solid over it. The current stroke as a measured sub-path from its own
 * start, so the draw-on follows the real path instead of fading the whole stroke
 * in. Strokes past the current one stay guide-only: present as context, never
 * presented as demonstrated.
 */
@Composable
fun StrokeGeometryCanvas(
    geometry: CharacterGeometry,
    revealedSteps: Int,
    currentProgress: Float,
    colors: StrokeGeometryColors,
    modifier: Modifier = Modifier,
) {
    val paths = remember(geometry) {
        geometry.strokes.map { stroke ->
            runCatching { PathParser().parsePathString(stroke.pathData).toPath() }
                .getOrDefault(Path())
        }
    }
    val bounds = remember(paths) {
        var united: Rect? = null
        for (path in paths) {
            val box = path.getBounds()
            if (box.isEmpty) continue
            united = united?.expandToInclude(box) ?: box
        }
        united ?: Rect(0f, 0f, 1024f, 1024f)
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val pad = 24.dp.toPx()
        // One mapping for every layer: the guide, the completed strokes and the animated
        // segment all go through the same viewport, so they cannot disagree about where
        // a stroke lives. See `fitCharViewport` for the orientation contract.
        val viewport = fitCharViewport(
            contentLeft = bounds.left,
            contentTop = bounds.top,
            contentRight = bounds.right,
            contentBottom = bounds.bottom,
            canvasWidth = size.width,
            canvasHeight = size.height,
            padding = pad,
        )

        translate(left = viewport.dx, top = viewport.dy) {
            // Negative y scale: the single documented reflection, matching
            // `CharViewport.map`. Positive x scale: left stays left.
            scale(viewport.scale, -viewport.scale, pivot = Offset.Zero) {
                val done = revealedSteps.coerceIn(0, paths.size)
                paths.forEach { drawPath(path = it, color = colors.guide) }
                for (i in 0 until done) {
                    drawPath(path = paths[i], color = colors.completed)
                }
                if (done < paths.size) {
                    val progress = currentProgress.coerceIn(0f, 1f)
                    if (progress > 0f) {
                        val measure = PathMeasure()
                        measure.setPath(paths[done], false)
                        val (startD, stopD) = segmentRange(measure.length, progress)
                        val segment = Path()
                        measure.getSegment(startD, stopD, segment, startWithMoveTo = true)
                        drawPath(path = segment, color = colors.current)
                    }
                }
            }
        }
    }
}

/** Unions two rects. Hand-rolled so empty bounds never skew the result. */
private fun Rect.expandToInclude(outer: Rect): Rect {
    val left = minOf(left, outer.left)
    val top = minOf(top, outer.top)
    val right = maxOf(right, outer.right)
    val bottom = maxOf(bottom, outer.bottom)
    return Rect(left, top, right, bottom)
}

/**
 * The measured sub-range a draw-on progress covers: from the path's own start to
 * `progress` of its total length.
 *
 * Pure so the contract is unit-tested without a measurer: untrusted progress is
 * clamped into range here rather than at the draw call, so no caller can ask for
 * a negative start, a stop past the end, or a backwards range, and the animation
 * always begins exactly where the completed stroke begins.
 */
internal fun segmentRange(totalLength: Float, progress: Float): Pair<Float, Float> {
    val clamped = progress.coerceIn(0f, 1f)
    return 0f to (totalLength * clamped).coerceAtLeast(0f)
}

/**
 * How a character's data bounds are fitted into a canvas.
 *
 * Pure float math, no Canvas, so the mapping itself is unit-tested.
 *
 * ## Orientation contract
 *
 * The dataset is y-UP: larger y is higher on screen (its own documentation states
 * the y-axis decreases downward and mandates a `scale(1, -1)` flip for display).
 * Compose Canvas is y-down, so the mapping reflects once about the content's
 * horizontal midline and then fits as usual. The reflection lives HERE and nowhere
 * else - one place, applied exactly once - so guide, completed and animated layers,
 * which all map through this function, cannot disagree about orientation. The scale
 * magnitude stays uniform and positive (aspect preserved, no stretching); only the
 * y direction is mirrored, which is precisely what upright display requires.
 *
 * @param contentLeft contentTop contentRight contentBottom union bounds of the paths,
 *   in dataset coordinates.
 * @param canvasWidth canvasHeight size of the drawing surface, in the same units.
 * @param padding breathing room kept on every side, in the same units.
 */
data class CharViewport(
    val scale: Float,
    val dx: Float,
    val dy: Float,
) {
    /** Maps one dataset point into canvas space, reflecting y exactly once. */
    fun map(x: Float, y: Float): Pair<Float, Float> = (x * scale + dx) to (-y * scale + dy)
}

fun fitCharViewport(
    contentLeft: Float,
    contentTop: Float,
    contentRight: Float,
    contentBottom: Float,
    canvasWidth: Float,
    canvasHeight: Float,
    padding: Float,
): CharViewport {
    val spanX = (contentRight - contentLeft).takeIf { it > 0f } ?: 1f
    val spanY = (contentBottom - contentTop).takeIf { it > 0f } ?: 1f
    val scale = minOf(
        (canvasWidth - padding * 2f) / spanX,
        (canvasHeight - padding * 2f) / spanY,
    ).takeIf { it.isFinite() && it > 0f } ?: 1f
    // Dataset-maximum y is the visual top and must land on the canvas top margin;
    // dataset-minimum y is the visual bottom and lands on the bottom margin:
    // canvas_y = -scale*y + dy with dy = padTop + scale*contentBottom.
    val spanYPx = spanY * scale
    val padTop = (canvasHeight - spanYPx) / 2f
    return CharViewport(
        scale = scale,
        dx = (canvasWidth - (contentRight - contentLeft) * scale) / 2f - contentLeft * scale,
        dy = padTop + contentBottom * scale,
    )
}
