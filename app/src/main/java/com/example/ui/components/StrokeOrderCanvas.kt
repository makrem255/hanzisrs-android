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
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.srs.StrokeNameParser
import com.example.ui.theme.DarkBg
import com.example.ui.theme.DarkSurfaceCard
import com.example.ui.theme.DarkSurfaceContainer
import com.example.ui.theme.DarkSurfaceElevated
import com.example.ui.theme.AccentPrimary
import com.example.ui.theme.AccentPrimaryInk
import com.example.ui.theme.OutlineBorder
import com.example.ui.theme.SrsGoodContainer
import com.example.ui.theme.SrsGoodDark
import com.example.ui.theme.TextLight
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextSubtle
import com.example.ui.theme.TianGridCenter
import com.example.ui.theme.TianGridLine
import kotlinx.coroutines.delay

/** One traced stroke, as the learner drew it. */
data class UserStroke(val points: List<Offset>)

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
    borderColor: Color = OutlineBorder,
    content: @Composable BoxScope.() -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val side = maxWidth.coerceIn(MinGridSize, MaxGridSize)
        Box(
            modifier = gridModifier
                .size(side)
                .clip(RoundedCornerShape(16.dp))
                .background(DarkSurfaceContainer)
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

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(DarkSurfaceCard)
            .border(1.dp, OutlineBorder, RoundedCornerShape(20.dp))
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (strokes.isEmpty()) {
            NoStrokeData(hanzi = hanzi)
            return@Column
        }

        SegmentedModeSwitch(selected = selectedMode, onSelect = { selectedMode = it })

        if (selectedMode == 0) {
            AnimatedStrokeOrderPlayer(hanzi = hanzi, strokes = strokes)
        } else {
            UserTracingCanvas(hanzi = hanzi, targetStrokeCount = strokes.size)
        }
    }
}

/** The two modes, as one set of tabs rather than two unrelated buttons. */
@Composable
private fun SegmentedModeSwitch(selected: Int, onSelect: (Int) -> Unit) {
    Surface(
        shape = CircleShape,
        color = DarkSurfaceContainer,
        border = androidx.compose.foundation.BorderStroke(1.dp, OutlineBorder),
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
                    .testTag("stroke_mode_guide")
            ) {
                Text(
                    text = "Stroke guide",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (selected == 0) AccentPrimaryInk else TextSubtle
                )
            }
            SegmentedOption(
                selected = selected == 1,
                onClick = { onSelect(1) },
                modifier = Modifier
                    .weight(1f)
                    .testTag("stroke_mode_practice")
            ) {
                Text(
                    text = "Practise tracing",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (selected == 1) AccentPrimaryInk else TextSubtle
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
            color = TextLight
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Stroke-by-stroke data is only available for the characters in the app's " +
                "built-in dictionary. $hanzi's pronunciation, meaning and example sentence are " +
                "still here, and it still works in your review deck.",
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = TextMuted,
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
    strokes: List<Pair<String, String>>
) {
    var currentStep by remember(hanzi, strokes) { mutableIntStateOf(0) }
    // Starts paused. It used to start playing, and a `LaunchedEffect` then advanced a step
    // every 1.3 seconds forever, redrawing two full-glyph canvases continuously on a phone
    // battery for as long as the tab was visible. The control is still there; it just no
    // longer starts itself.
    var isPlaying by remember(hanzi, strokes) { mutableStateOf(false) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        GridBox {
            GridBackdrop(hanzi)

            // The current stroke's name, in the corner, where it cannot be mistaken for part
            // of the character.
            Surface(
                color = AccentPrimary,
                shape = CircleShape,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .testTag("stroke_step_counter")
            ) {
                Text(
                    text = "${currentStep + 1}/${strokes.size}",
                    color = AccentPrimaryInk,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        val (name, pinyin) = strokes[currentStep]
        Surface(
            color = DarkSurfaceElevated,
            shape = RoundedCornerShape(20.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, OutlineBorder),
            modifier = Modifier.padding(vertical = 4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
            ) {
                Text(
                    text = "Stroke ${currentStep + 1} of ${strokes.size}",
                    fontWeight = FontWeight.Bold,
                    color = AccentPrimary,
                    fontSize = 13.sp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = name,
                    fontWeight = FontWeight.SemiBold,
                    color = TextLight,
                    fontSize = 15.sp
                )
                if (pinyin.isNotBlank()) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = pinyin, color = TextMuted, fontSize = 12.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconTarget(
                onClick = {
                    isPlaying = false
                    if (strokes.isNotEmpty()) {
                        currentStep = if (currentStep > 0) currentStep - 1 else strokes.lastIndex
                    }
                },
                modifier = Modifier.testTag("stroke_prev")
            ) {
                Icon(
                    Icons.Default.FastRewind,
                    contentDescription = "Previous stroke",
                    tint = AccentPrimary
                )
            }

            Button(
                onClick = { isPlaying = !isPlaying },
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentPrimary,
                    contentColor = AccentPrimaryInk
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
                    // Guarded: `strokes` is non-empty by the caller's contract, and a
                    // zero-length list here would be a modulo by zero. The contract is
                    // enforced in `InteractiveStrokeSection`, but the stepper does not rely
                    // on a caller to keep it alive.
                    if (strokes.isNotEmpty()) {
                        currentStep = (currentStep + 1) % strokes.size
                    }
                },
                modifier = Modifier.testTag("stroke_next")
            ) {
                Icon(
                    Icons.Default.FastForward,
                    contentDescription = "Next stroke",
                    tint = AccentPrimary
                )
            }
        }

        if (isPlaying && strokes.isNotEmpty()) {
            LaunchedEffect(currentStep) {
                delay(1100)
                if (isPlaying && strokes.isNotEmpty()) {
                    currentStep = (currentStep + 1) % strokes.size
                }
            }
        }
    }
}

/**
 * A canvas to trace a character on.
 *
 * The strokes drawn here are never compared to the character. Nothing in the app has the
 * geometry to do that comparison, and a "correct!" that came from counting scribbles rather
 * than from reading them would be the same kind of invented measurement as a fabricated
 * stroke order. So this is labelled as practice, it counts the learner's strokes against
 * the character's real stroke count, and it says plainly that the tracing is not being
 * scored.
 */
@Composable
fun UserTracingCanvas(
    hanzi: String,
    targetStrokeCount: Int
) {
    // Keyed on the character. These were `remember` with no key, and the pointer input was
    // keyed on `Unit`, so a second character kept the first one's strokes on the canvas and
    // kept the first character's stroke count as its target.
    val strokes = remember(hanzi) { mutableStateListOf<UserStroke>() }
    var currentPoints by remember(hanzi) { mutableStateOf<List<Offset>>(emptyList()) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        GridBox(
            gridModifier = Modifier
                .pointerInput(hanzi, targetStrokeCount) {
                    detectDragGestures(
                        onDragStart = { offset -> currentPoints = listOf(offset) },
                        onDrag = { change, _ ->
                            change.consume()
                            currentPoints = currentPoints + change.position
                        },
                        onDragEnd = {
                            if (currentPoints.size > 1) {
                                strokes.add(UserStroke(currentPoints))
                                currentPoints = emptyList()
                            }
                        },
                        onDragCancel = { currentPoints = emptyList() }
                    )
                }
        ) {
            GridBackdrop(hanzi)

            Canvas(modifier = Modifier.fillMaxSize()) {
                val finished = TextLight
                val drawing = AccentPrimary

                strokes.forEach { userStroke ->
                    drawTracedStroke(userStroke.points, finished)
                }
                if (currentPoints.size > 1) {
                    drawTracedStroke(currentPoints, drawing)
                }
            }

            Surface(
                color = DarkSurfaceElevated,
                shape = CircleShape,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .testTag("trace_stroke_counter")
            ) {
                Text(
                    text = "$targetStrokeCount strokes",
                    color = TextLight,
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
            color = TextMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp)
                .testTag("trace_not_scored_note")
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = {
                    strokes.clear()
                    currentPoints = emptyList()
                },
                enabled = strokes.isNotEmpty(),
                modifier = Modifier
                    .heightIn(min = MinTouchTarget)
                    .testTag("trace_clear"),
                shape = RoundedCornerShape(24.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, OutlineBorder),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextLight)
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
                        containerColor = DarkSurfaceElevated,
                        contentColor = TextLight
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
    }
}

/** The ghost character over the grid, in one place so both canvases sit on the same backdrop. */
@Composable
private fun GridBackdrop(hanzi: String) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        drawRiceGrid(size.width, size.height)
        drawGhostGlyph(hanzi)
    }
}

private fun DrawScope.drawTracedStroke(points: List<Offset>, color: Color) {
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
private fun DrawScope.drawRiceGrid(width: Float, height: Float) {
    val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)

    drawLine(
        color = TianGridCenter,
        start = Offset(0f, height / 2),
        end = Offset(width, height / 2),
        strokeWidth = 2f,
        pathEffect = dash
    )
    drawLine(
        color = TianGridCenter,
        start = Offset(width / 2, 0f),
        end = Offset(width / 2, height),
        strokeWidth = 2f,
        pathEffect = dash
    )
    drawLine(
        color = TianGridLine,
        start = Offset(0f, 0f),
        end = Offset(width, height),
        strokeWidth = 1.2f,
        pathEffect = dash
    )
    drawLine(
        color = TianGridLine,
        start = Offset(width, 0f),
        end = Offset(0f, height),
        strokeWidth = 1.2f,
        pathEffect = dash
    )
}
