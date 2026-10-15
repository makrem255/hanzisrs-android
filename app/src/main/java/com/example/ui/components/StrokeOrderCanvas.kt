package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalContext
import com.example.data.strokes.CharacterGeometry
import com.example.data.strokes.StrokeGeometryStore
import com.example.data.strokes.AssessmentGap
import com.example.data.strokes.AttemptAssessment
import com.example.data.strokes.DirectionVerdict
import com.example.data.strokes.ShapeVerdict
import com.example.data.strokes.StrokeVerdict
import com.example.data.strokes.TracePoint
import com.example.data.strokes.evaluateAttempt
import com.example.data.strokes.summarizeAssessment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.srs.StrokeNameParser
import com.example.ui.theme.AppTheme
import kotlinx.coroutines.delay

/** One traced stroke, as the learner drew it.
 *
 * Points are fractions of the canvas size (0..1 in each axis), not pixels. Pixel
 * values would pin the stroke to the canvas size at draw time, so rotating the
 * phone or resizing the sheet would shift old strokes away from the grid they
 * were drawn on. Fractions survive any size: drawing multiplies back out against
 * whatever the canvas measures when it draws.
 */
data class UserStroke(val points: List<Offset>)

/**
 * Converts a touch point to canvas fractions.
 *
 * Returns [Offset.Zero] when the canvas size is still unknown rather than
 * dividing by zero; a zero-size canvas cannot receive touches anyway, so the
 * point could never have been drawn.
 */
fun normalizeTracePoint(p: Offset, widthPx: Float, heightPx: Float): Offset {
    if (widthPx <= 0f || heightPx <= 0f) return Offset.Zero
    return Offset(
        (p.x / widthPx).coerceIn(0f, 1f),
        (p.y / heightPx).coerceIn(0f, 1f),
    )
}

/**
 * Converts stored fractions back to pixels for the canvas currently drawing.
 *
 * Returns an empty list when the size is unknown rather than drawing at a
 * degenerate scale; drawing nothing is honest, drawing collapsed is not.
 */
fun denormalizeTracePoints(
    points: List<Offset>,
    widthPx: Float,
    heightPx: Float,
): List<Offset> {
    if (widthPx <= 0f || heightPx <= 0f) return emptyList()
    return points.map { Offset(it.x * widthPx, it.y * heightPx) }
}

/**
 * The size of the drawing square.
 *
 * This was a fixed `230.dp`, which is fine on a phone and broken in the word detail sheet:
 * `AlertDialog` has a `widthIn(min = 280.dp)` plus its own padding, leaving about 204dp, so
 * a 230dp child was clipped. Sizing from the space actually available and capping it fixes
 * both the sheet and a landscape phone at the same time.
 */
private val MaxGridSize: Dp = 260.dp
private val MinGridSize: Dp = 180.dp

/**
 * The drawing square, sized from the width it is given.
 *
 * A square is the point: a 田字格 with a non-square cell misrepresents the character's
 * proportions to someone learning to write it, so the height is derived from the width
 * rather than constrained separately.
 *
 * [gridModifier] goes on the square itself, not on the wrapper, so that hit-testing matches
 * what is drawn. A drag area wider than the visible grid would mean the learner starts a
 * stroke in blank space beside the character and the stroke appears somewhere they were not
 * touching.
 */
@Composable
private fun GridBox(
    gridModifier: Modifier = Modifier,
    borderWidth: Dp = 1.dp,
    borderColor: Color = AppTheme.colors.border,
    content: @Composable BoxScope.() -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val side = maxWidth.coerceIn(MinGridSize, MaxGridSize)
        Box(
            modifier = gridModifier
                .size(side)
                .clip(RoundedCornerShape(16.dp))
                .background(AppTheme.colors.surfaceAlt)
                .border(borderWidth, borderColor, RoundedCornerShape(16.dp)),
            content = content
        )
    }
}

/**
 * The stroke section of a review card: a named-stroke guide, and a canvas to practise on.
 *
 * Both halves are driven by [StrokeNameParser], the same reader the v2 to v3 migration used
 * to populate `character_strokes`. There is deliberately no fallback list: when a character
 * has no stroke data the section says so and disables itself, because the previous fallback
 * (`横, 竖, 撇, 捺` for anything unreadable) was a fabricated four-stroke order presented
 * under a 田字格 as though it were the real one.
 */
@Composable
fun InteractiveStrokeSection(
    hanzi: String,
    strokeBreakdown: String,
    modifier: Modifier = Modifier
) {
    var selectedMode by remember(hanzi) { mutableIntStateOf(0) } // 0 = guide, 1 = practice
    val strokes = remember(strokeBreakdown) {
        StrokeNameParser.parse(strokeBreakdown).map { it.nameCn to it.namePinyin }
    }
    // Verified per-stroke paths, when this is one bundled character. Null for anything
    // else - words, unbundled characters, unreadable assets - and every branch below
    // treats null as "no geometry", never as a reason to guess.
    val context = LocalContext.current
    val geometryStore = remember(context) { StrokeGeometryStore(context) }
    val geometry = remember(hanzi, geometryStore) { geometryStore.load(hanzi) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(AppTheme.colors.card)
            .border(1.dp, AppTheme.colors.border, RoundedCornerShape(20.dp))
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (geometry == null && strokes.isEmpty()) {
            NoStrokeData(hanzi = hanzi)
            return@Column
        }

        SegmentedModeSwitch(selected = selectedMode, onSelect = { selectedMode = it })

        if (selectedMode == 0) {
            AnimatedStrokeOrderPlayer(hanzi = hanzi, strokes = strokes, geometry = geometry)
        } else {
            UserTracingCanvas(
                hanzi = hanzi,
                targetStrokeCount = geometry?.strokeCount ?: strokes.size,
                referenceGeometry = geometry
            )
        }

        if (geometry != null) {
            // Provenance on screen, not just in the repo: the paths are someone's
            // licensed work, and the learner deserves to know whose.
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Stroke paths: Make Me a Hanzi project · Arphic Public License",
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                color = AppTheme.colors.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/** The two modes, as one set of tabs rather than two unrelated buttons. */
@Composable
private fun SegmentedModeSwitch(selected: Int, onSelect: (Int) -> Unit) {
    Surface(
        shape = CircleShape,
        color = AppTheme.colors.surfaceAlt,
        border = androidx.compose.foundation.BorderStroke(1.dp, AppTheme.colors.border),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 3.dp, vertical = 2.dp)
        ) {
            SegmentedOption(
                selected = selected == 0,
                onClick = { onSelect(0) },
                modifier = Modifier
                    .weight(1f)
                    .testTag("stroke_mode_guide"),
                selectedFill = AppTheme.colors.button
            ) {
                Text(
                    text = "Stroke guide",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (selected == 0) AppTheme.colors.onButton else AppTheme.colors.textSecondary
                )
            }
            SegmentedOption(
                selected = selected == 1,
                onClick = { onSelect(1) },
                modifier = Modifier
                    .weight(1f)
                    .testTag("stroke_mode_practice"),
                selectedFill = AppTheme.colors.button
            ) {
                Text(
                    text = "Practise tracing",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (selected == 1) AppTheme.colors.onButton else AppTheme.colors.textSecondary
                )
            }
        }
    }
}

/**
 * A character with no stroke data on file.
 *
 * This is a real state, and it is most characters the learner has added themselves: the
 * dictionary has stroke breakdowns for its own entries and nothing for anything typed in
 * from outside. Showing a made-up sequence here would teach the wrong stroke order, so
 * instead the section states the absence and points at the two things that do work.
 */
@Composable
private fun NoStrokeData(hanzi: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("stroke_no_data"),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        GridBox {
            GridBackdrop(hanzi)
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "No stroke order for this character",
            style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
            color = AppTheme.colors.textPrimary
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Stroke-by-stroke data is only available for the characters in the app's " +
                "built-in dictionary. $hanzi's pronunciation, meaning and example sentence are " +
                "still here, and it still works in your review deck.",
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = AppTheme.colors.textSecondary,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * Draws the character centred in the current size, in the guide's faint colour.
 *
 * A 田字格 is only a writing aid if the glyph is centred in the cell, and `Paint` measures
 * by ascent and descent rather than by glyph bounds — which for a character like 一 or 丨
 * would put the visible stroke well off-centre. Measuring the actual bounds and centring on
 * those is what makes the guide usable for a beginner.
 */
private fun DrawScope.drawGhostGlyph(hanzi: String, alpha: Int = 45) {
    val glyph = hanzi.firstOrNull()?.toString() ?: return
    val paint = android.graphics.Paint().apply {
        isAntiAlias = true
        textAlign = android.graphics.Paint.Align.LEFT
        color = android.graphics.Color.argb(alpha, 202, 196, 208)
        typeface = android.graphics.Typeface.SERIF
    }

    val cell = minOf(size.width, size.height)
    paint.textSize = cell * 0.70f

    // Measure the glyph being drawn, not a reference character.
    //
    // `Paint` positions text by the *baseline*, and `getTextBounds` returns a box whose `top`
    // is the distance above that baseline and whose `bottom` the distance below it. A
    // character with no vertical extent at all - 一, or 丶 - therefore reports a box that
    // measures against nothing, and centring by font metrics puts the visible stroke well
    // off-centre in the cell.
    //
    // The earlier version worked around this by measuring 国 and adding its `bottom` to its
    // `height`, which double-counts the descender and pushes the glyph down, and which
    // measures a different character than the one being drawn. The bounds of the glyph
    // itself are the right thing to centre on: `bottom - top` is its true extent for any
    // character, including the flat ones.
    val bounds = android.graphics.Rect()
    paint.getTextBounds(glyph, 0, glyph.length, bounds)

    // No ink: a space, or a character this font does not carry. Draw nothing rather than
    // drawing a reference glyph in its place - a blank grid is honest, a wrong character is
    // not.
    if (bounds.width() <= 0 || bounds.height() <= 0) return

    val x = (size.width - bounds.width()) / 2f - bounds.left
    val y = (size.height - bounds.height()) / 2f - bounds.top
    drawContext.canvas.nativeCanvas.drawText(glyph, x, y, paint)
}

/**
 * Steps through a character's named strokes.
 *
 * This does not animate the writing of each stroke, and does not claim to. Drawing a
 * correct partial glyph per stroke needs the per-stroke geometry the schema does not carry
 * yet; what it does is walk the authored sequence - "stroke 3 of 9, 丿 (Piǎo)" - over the
 * character, which is the part a learner can act on and the part that was previously
 * missing entirely.
 */
@Composable
fun AnimatedStrokeOrderPlayer(
    hanzi: String,
    strokes: List<Pair<String, String>>,
    geometry: CharacterGeometry? = null,
) {
    // Steps are positions in the demonstrated order. With geometry, the count comes from
    // the verified paths; otherwise from the named sequence. Both are ordered lists, so
    // either way step `i` is genuinely the i-th stroke.
    val totalSteps = geometry?.strokeCount ?: strokes.size
    var currentStep by remember(hanzi, strokes, geometry) { mutableIntStateOf(0) }
    // Starts paused. It used to start playing, and a `LaunchedEffect` then advanced a step
    // every 1.3 seconds forever, redrawing two full-glyph canvases continuously on a phone
    // battery for as long as the tab was visible. The control is still there; it just no
    // longer starts itself.
    var isPlaying by remember(hanzi, strokes, geometry) { mutableStateOf(false) }

    // Draw-on progress of the current stroke. lives beside the step rather than inside
    // the canvas so pause, resume and manual stepping stay decisions, not accidents
    // (see the effects below). Unused without geometry.
    val drawProgress = remember(hanzi, geometry) { Animatable(0f) }
    var lastDrawnStep by remember(hanzi, geometry) { mutableIntStateOf(-1) }
    if (geometry != null) {
        LaunchedEffect(currentStep, isPlaying, geometry) {
            if (currentStep != lastDrawnStep) {
                lastDrawnStep = currentStep
                // A manually chosen step shows fully drawn; a played step draws on.
                drawProgress.snapTo(if (isPlaying) 0f else 1f)
            }
            if (isPlaying && drawProgress.value < 1f) {
                val remaining = ((1f - drawProgress.value) * STEP_DRAW_MS).toInt().coerceAtLeast(1)
                drawProgress.animateTo(1f, tween(remaining))
            } else {
                // Halted while paused. `stop` on an idle Animatable is a no-op, so
                // this branch is safe on every path that reaches it.
                drawProgress.stop()
            }
        }
    }

    if (totalSteps == 0) return

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        GridBox {
            if (geometry != null) {
                // Real paths, drawn in order: completed solid, the current one
                // drawing itself on, the rest as faint context. See
                // `StrokeGeometryCanvas` for the layer contract.
                val palette = AppTheme.colors
                StrokeGeometryCanvas(
                    geometry = geometry,
                    revealedSteps = currentStep,
                    currentProgress = drawProgress.value,
                    colors = StrokeGeometryColors(
                        guide = palette.ghostText,
                        completed = palette.textPrimary,
                        current = palette.button,
                    ),
                    modifier = Modifier.testTag("stroke_geometry_canvas"),
                )
            } else {
                GridBackdrop(hanzi)
            }

            // The current stroke's name, in the corner, where it cannot be mistaken for part
            // of the character.
            Surface(
                color = AppTheme.colors.button,
                shape = CircleShape,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .testTag("stroke_step_counter")
            ) {
                Text(
                    text = "${currentStep + 1}/$totalSteps",
                    color = AppTheme.colors.onButton,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // The current stroke's name, when the catalogue carries names. Geometry-only
        // characters (bundled paths without a named breakdown) skip this row rather
        // than show a blank label: the counter above already says where the demo is.
        val (name, pinyin) = strokes.getOrElse(currentStep) { "" to "" }
        if (name.isNotBlank()) {
        Surface(
            color = AppTheme.colors.elevated,
            shape = RoundedCornerShape(20.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, AppTheme.colors.border),
            modifier = Modifier.padding(vertical = 4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
            ) {
                Text(
                    text = "Stroke ${currentStep + 1} of $totalSteps",
                    fontWeight = FontWeight.Bold,
                    color = AppTheme.colors.button,
                    fontSize = 13.sp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = name,
                    fontWeight = FontWeight.SemiBold,
                    color = AppTheme.colors.textPrimary,
                    fontSize = 15.sp
                )
                if (pinyin.isNotBlank()) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = pinyin, color = AppTheme.colors.textSecondary, fontSize = 12.sp)
                }
            }
        }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Where the demonstration stands: one dot per stroke, filled through the
        // current one. The count and the order are authored data, so showing
        // position-in-sequence is honest in a way per-stroke geometry would not be -
        // the app has names, not paths, and a dot claims nothing about shapes.
        StepDots(
            total = totalSteps,
            current = currentStep,
            modifier = Modifier.testTag("stroke_progress")
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconTarget(
                onClick = {
                    isPlaying = false
                    currentStep = 0
                },
                modifier = Modifier.testTag("stroke_reset")
            ) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = "Restart demonstration",
                    tint = AppTheme.colors.button
                )
            }
            IconTarget(
                onClick = {
                    isPlaying = false
                    if (totalSteps > 0) {
                        currentStep = if (currentStep > 0) currentStep - 1 else totalSteps - 1
                    }
                },
                modifier = Modifier.testTag("stroke_prev")
            ) {
                Icon(
                    Icons.Default.FastRewind,
                    contentDescription = "Previous stroke",
                    tint = AppTheme.colors.button
                )
            }

            Button(
                onClick = { isPlaying = !isPlaying },
                colors = ButtonDefaults.buttonColors(
                    containerColor = AppTheme.colors.button,
                    contentColor = AppTheme.colors.onButton
                ),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .heightIn(min = MinTouchTarget)
                    .testTag("stroke_play_pause")
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Clear else Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (isPlaying) "Stop" else "Play",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            IconTarget(
                onClick = {
                    isPlaying = false
                    // Guarded: `totalSteps` is non-zero by the early return above, and a
                    // zero-length list here would be a modulo by zero. The contract is
                    // enforced before this composable runs, but the stepper does not rely
                    // on a caller to keep it alive.
                    if (totalSteps > 0) {
                        currentStep = (currentStep + 1) % totalSteps
                    }
                },
                modifier = Modifier.testTag("stroke_next")
            ) {
                Icon(
                    Icons.Default.FastForward,
                    contentDescription = "Next stroke",
                    tint = AppTheme.colors.button
                )
            }
        }

        if (isPlaying && totalSteps > 0) {
            LaunchedEffect(currentStep) {
                delay(1100)
                if (isPlaying && totalSteps > 0) {
                    currentStep = (currentStep + 1) % totalSteps
                }
            }
        }
    }
}

/**
 * Position dots for the demonstration: one per stroke, filled through the current one.
 *
 * Dots rather than a fraction bar because the unit being shown is discrete - stroke 3
 * of 9 is a place in a sequence, and a bar would imply a continuous quantity the app
 * does not have.
 */
@Composable
private fun StepDots(total: Int, current: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(total) { index ->
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            index < current -> AppTheme.colors.textPrimary
                            index == current -> AppTheme.colors.button
                            else -> AppTheme.colors.border
                        }
                    )
            )
        }
    }
}

/** How long one stroke takes to draw itself on during playback, in milliseconds. */
private const val STEP_DRAW_MS = 800

/** How long a Hint flash shows the reference glyph, in milliseconds. */
private const val HINT_FLASH_MS = 1500L

/**
 * A canvas to trace a character on, with an optional offline evaluation.
 *
 * The strokes drawn here are compared to the reference medians only when the learner
 * presses Check. The evaluation is purely geometric, offline, and makes no claims
 * about handwriting skill or mastery. It reports cautious descriptors such as
 * "close to the reference", "different from the reference", or "not enough information
 * to compare", and never grades, percentages, or SRS-affecting scores.
 */
@Composable
fun UserTracingCanvas(
    hanzi: String,
    targetStrokeCount: Int,
    referenceGeometry: CharacterGeometry? = null
) {
    // Keyed on the character. These were `remember` with no key, and the pointer input was
    // keyed on `Unit`, so a second character kept the first one's strokes on the canvas and
    // kept the first character's stroke count as its target.
    val strokes = remember(hanzi) { mutableStateListOf<UserStroke>() }
    var currentPoints by remember(hanzi) { mutableStateOf<List<Offset>>(emptyList()) }
    // Canvas size in pixels, tracked so touch points can be stored as fractions (see
    // [UserStroke]). Included in the gesture keys below so a resize or rotation never
    // leaves the handler normalizing against a stale size.
    var gridPx by remember(hanzi) { mutableStateOf(IntSize.Zero) }
    // Whether the reference glyph is drawn. Off means "write from memory": the grid
    // stays, the glyph goes. Keyed on the character like everything else here.
    var referenceVisible by remember(hanzi) { mutableStateOf(true) }
    // A momentary reveal while hidden. Timed, never latched: the `LaunchedEffect`
    // below always turns it back off, so a hint cannot get stuck on.
    var hinting by remember(hanzi) { mutableStateOf(false) }
    if (hinting) {
        LaunchedEffect(hanzi) {
            delay(HINT_FLASH_MS)
            hinting = false
        }
    }
    val ghostVisible = referenceVisible || hinting

    // Assessment result, shown after pressing Check. Keyed on hanzi so switching
    // characters clears the previous result.
    var assessmentResult by remember(hanzi) { mutableStateOf<AttemptAssessment?>(null) }

    // Convert reference geometry medians to evaluator format (TracePoint lists).
    // The dataset uses y-up coordinates; the evaluator handles the y-flip.
    val referenceMedians = remember(referenceGeometry) {
        referenceGeometry?.strokes?.map { stroke ->
            stroke.median.map { mp -> TracePoint(mp.x, mp.y) }
        }
    }

    // Convert user strokes (Offset fractions) to evaluator format (TracePoint lists).
    fun drawnStrokesForEvaluation(): List<List<TracePoint>> = strokes.map { userStroke ->
        userStroke.points.map { offset -> TracePoint(offset.x, offset.y) }
    }

    // Run evaluation and store the result.
    fun runEvaluation() {
        assessmentResult = evaluateAttempt(referenceMedians, drawnStrokesForEvaluation())
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        GridBox(
            gridModifier = Modifier
                .onSizeChanged { gridPx = it }
                .pointerInput(hanzi, targetStrokeCount, gridPx) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            currentPoints = listOf(
                                normalizeTracePoint(
                                    offset,
                                    gridPx.width.toFloat(),
                                    gridPx.height.toFloat(),
                                )
                            )
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            currentPoints = currentPoints + normalizeTracePoint(
                                change.position,
                                gridPx.width.toFloat(),
                                gridPx.height.toFloat(),
                            )
                        },
                        onDragEnd = {
                            if (currentPoints.size > 1) {
                                strokes.add(UserStroke(currentPoints))
                                currentPoints = emptyList()
                                // Clear previous assessment when new strokes are added
                                assessmentResult = null
                            }
                        },
                        onDragCancel = { currentPoints = emptyList() }
                    )
                }
                .testTag("trace_grid")
        ) {
            GridBackdrop(hanzi, showGlyph = ghostVisible)

            val finished = AppTheme.colors.textPrimary
            val drawing = AppTheme.colors.button
            Canvas(modifier = Modifier.fillMaxSize()) {
                strokes.forEach { userStroke ->
                    drawTracedStroke(userStroke.points, finished)
                }
                if (currentPoints.size > 1) {
                    drawTracedStroke(currentPoints, drawing)
                }
            }

            Surface(
                color = AppTheme.colors.elevated,
                shape = CircleShape,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .testTag("trace_stroke_counter")
            ) {
                Text(
                    text = "$targetStrokeCount strokes",
                    color = AppTheme.colors.textPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Not scored. Drawn strokes are never compared to $hanzi — this is for " +
                "practising the motion, and $hanzi has " +
                (if (targetStrokeCount == 1) "1 stroke." else "$targetStrokeCount strokes."),
            fontSize = 11.sp,
            lineHeight = 16.sp,
            color = AppTheme.colors.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp)
                .testTag("trace_not_scored_note")
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Reference visibility: hide the glyph to write from memory, flash it
        // briefly with Hint, bring it back with Show. All three only change what
        // is drawn; strokes, counter and the honesty note below are untouched.
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = { referenceVisible = !referenceVisible },
                modifier = Modifier
                    .heightIn(min = MinTouchTarget)
                    .testTag("trace_toggle_reference"),
                shape = RoundedCornerShape(24.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, AppTheme.colors.border),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = AppTheme.colors.textPrimary)
            ) {
                Icon(
                    if (referenceVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(if (referenceVisible) "Hide" else "Show", fontSize = 13.sp)
            }

            // Offered only while hidden: with the reference up, a hint would
            // reveal nothing, and a button that does nothing is a defect.
            if (!referenceVisible) {
                OutlinedButton(
                    onClick = { hinting = true },
                    modifier = Modifier
                        .heightIn(min = MinTouchTarget)
                        .testTag("trace_hint"),
                    shape = RoundedCornerShape(24.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, AppTheme.colors.border),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AppTheme.colors.textPrimary)
                ) {
                    Text("Hint", fontSize = 13.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = {
                    strokes.clear()
                    currentPoints = emptyList()
                    assessmentResult = null
                },
                enabled = strokes.isNotEmpty(),
                modifier = Modifier
                    .heightIn(min = MinTouchTarget)
                    .testTag("trace_clear"),
                shape = RoundedCornerShape(24.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, AppTheme.colors.border),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = AppTheme.colors.textPrimary)
            ) {
                Icon(
                    Icons.Default.Clear,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Clear", fontSize = 13.sp)
            }

            if (strokes.isNotEmpty()) {
                Button(
                    onClick = { strokes.removeAt(strokes.lastIndex) },
                    modifier = Modifier
                        .heightIn(min = MinTouchTarget)
                        .testTag("trace_undo"),
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AppTheme.colors.elevated,
                        contentColor = AppTheme.colors.textPrimary
                    )
                ) {
                    Icon(
                        Icons.Default.Undo,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Undo stroke", fontSize = 13.sp)
                }
            }
        }

        // Check button: only enabled when reference geometry exists and at least one stroke is drawn.
        val canCheck = referenceMedians != null && strokes.isNotEmpty()
        if (canCheck) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { runEvaluation() },
                    modifier = Modifier
                        .heightIn(min = MinTouchTarget)
                        .testTag("trace_check"),
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AppTheme.colors.button,
                        contentColor = AppTheme.colors.onButton
                    )
                ) {
                    Text("Check", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        // Display assessment result if available.
        assessmentResult?.let { result ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = summarizeAssessment(result),
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = AppTheme.colors.textPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .testTag("trace_assessment_result")
            )

            // Per-stroke indicators for assessed attempts.
            if (result is AttemptAssessment.Assessed) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .testTag("trace_stroke_indicators")
                ) {
                    result.results.forEachIndexed { index, verdict ->
                        val indicatorColor = when (verdict.shape) {
                            ShapeVerdict.CloseToReference -> AppTheme.colors.success
                            ShapeVerdict.DifferentFromReference -> AppTheme.colors.error
                            ShapeVerdict.NotEnoughInfo -> AppTheme.colors.textSecondary
                        }
                        val directionMark = when (verdict.direction) {
                            DirectionVerdict.Forward -> " →"
                            DirectionVerdict.Backward -> " ←"
                            DirectionVerdict.Unclear -> " ?"
                        }
                        Surface(
                            color = indicatorColor.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(12.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, indicatorColor),
                            modifier = Modifier
                                .padding(2.dp)
                                .testTag("stroke_indicator_$index")
                        ) {
                            Text(
                                text = "Stroke ${index + 1}$directionMark",
                                fontSize = 11.sp,
                                color = indicatorColor,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The ghost character over the grid, in one place so both canvases sit on the same backdrop.
 *
 * @param showGlyph whether the reference glyph itself is drawn. Hiding the glyph
 *   is the "write from memory" mode: the grid stays as an
 *   orientation aid, but there is nothing left to trace over.
 */
@Composable
private fun GridBackdrop(hanzi: String, showGlyph: Boolean = true) {
    val center = AppTheme.colors.gridCenter
    val line = AppTheme.colors.gridLine
    Canvas(modifier = Modifier.fillMaxSize()) {
        drawRiceGrid(size.width, size.height, center, line)
        if (showGlyph) drawGhostGlyph(hanzi)
    }
}

private fun DrawScope.drawTracedStroke(normalized: List<Offset>, color: Color) {
    val points = denormalizeTracePoints(normalized, size.width, size.height)
    if (points.size < 2) return
    val path = Path().apply {
        moveTo(points.first().x, points.first().y)
        for (i in 1 until points.size) {
            lineTo(points[i].x, points[i].y)
        }
    }
    drawPath(
        path = path,
        color = color,
        style = Stroke(width = 18f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    )
}

/** The 田字格: a centre cross plus the diagonals, dashed. */
private fun DrawScope.drawRiceGrid(width: Float, height: Float, center: Color, line: Color) {
    val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)

    drawLine(
        color = center,
        start = Offset(0f, height / 2),
        end = Offset(width, height / 2),
        strokeWidth = 2f,
        pathEffect = dash
    )
    drawLine(
        color = center,
        start = Offset(width / 2, 0f),
        end = Offset(width / 2, height),
        strokeWidth = 2f,
        pathEffect = dash
    )
    drawLine(
        color = line,
        start = Offset(0f, 0f),
        end = Offset(width, height),
        strokeWidth = 1.2f,
        pathEffect = dash
    )
    drawLine(
        color = line,
        start = Offset(width, 0f),
        end = Offset(0f, height),
        strokeWidth = 1.2f,
        pathEffect = dash
    )
}

