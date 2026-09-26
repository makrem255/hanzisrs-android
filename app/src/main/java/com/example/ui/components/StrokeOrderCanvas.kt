package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.DarkBg
import com.example.ui.theme.DarkSurfaceCard
import com.example.ui.theme.DarkSurfaceContainer
import com.example.ui.theme.DarkSurfaceElevated
import com.example.ui.theme.GhostTextDark
import com.example.ui.theme.GridCenterDark
import com.example.ui.theme.GridLineDark
import com.example.ui.theme.LilacPrimary
import com.example.ui.theme.LilacPrimaryDark
import com.example.ui.theme.LilacSecondary
import com.example.ui.theme.OutlineBorder
import com.example.ui.theme.SrsGoodContainer
import com.example.ui.theme.SrsGoodDark
import com.example.ui.theme.TextLight
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextSubtle
import com.example.ui.theme.TianGridCenter
import com.example.ui.theme.TianGridLine
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class UserStroke(val points: List<Offset>)

@Composable
fun InteractiveStrokeSection(
    hanzi: String,
    strokeBreakdown: String,
    modifier: Modifier = Modifier
) {
    var selectedMode by remember { mutableIntStateOf(0) } // 0 = Stroke Animation Guide, 1 = User Tracing Canvas
    val strokeList = remember(strokeBreakdown) {
        if (strokeBreakdown.isBlank()) {
            listOf("横 (Héng)", "竖 (Shù)", "撇 (Piě)", "捺 (Nà)")
        } else {
            strokeBreakdown.split(",").map { it.trim() }.filter { it.isNotBlank() }
        }
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
        // Pill switcher
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
                    .padding(3.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp)
                        .clip(CircleShape)
                        .background(if (selectedMode == 0) LilacPrimary else Color.Transparent)
                        .clickable { selectedMode = 0 },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "🖌️ Stroke Guide",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (selectedMode == 0) LilacPrimaryDark else TextSubtle
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp)
                        .clip(CircleShape)
                        .background(if (selectedMode == 1) LilacPrimary else Color.Transparent)
                        .clickable { selectedMode = 1 },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "✍️ Practice Tracing",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (selectedMode == 1) LilacPrimaryDark else TextSubtle
                    )
                }
            }
        }

        if (selectedMode == 0) {
            AnimatedStrokeOrderPlayer(
                hanzi = hanzi,
                strokes = strokeList
            )
        } else {
            UserTracingCanvas(
                hanzi = hanzi,
                targetStrokeCount = strokeList.size
            )
        }
    }
}

@Composable
fun AnimatedStrokeOrderPlayer(
    hanzi: String,
    strokes: List<String>
) {
    var currentStep by remember { mutableIntStateOf(0) }
    var isPlaying by remember { mutableStateOf(true) }
    val animProgress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(currentStep, isPlaying) {
        if (isPlaying) {
            animProgress.snapTo(0f)
            animProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 900, easing = LinearEasing)
            )
            delay(400)
            if (currentStep < strokes.size - 1) {
                currentStep++
            } else {
                delay(800)
                currentStep = 0
            }
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        // Grid Box with Hanzi & Animation Guide
        Box(
            modifier = Modifier
                .size(230.dp)
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(DarkSurfaceContainer)
                .border(1.dp, OutlineBorder, RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center
        ) {
            // Draw 米字格 (Rice Grid)
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawRiceGrid(size.width, size.height)
            }

            // Draw Background Ghost Character & Progress
            Canvas(modifier = Modifier.fillMaxSize()) {
                val canvasWidth = size.width
                val canvasHeight = size.height
                val paint = android.graphics.Paint().apply {
                    textSize = canvasWidth * 0.70f
                    textAlign = android.graphics.Paint.Align.CENTER
                    isAntiAlias = true
                    color = android.graphics.Color.argb(45, 202, 196, 208) // Faint Ghost Guide
                    typeface = android.graphics.Typeface.SERIF
                }
                val yPos = (canvasHeight / 2) - ((paint.descent() + paint.ascent()) / 2)
                drawContext.canvas.nativeCanvas.drawText(
                    hanzi.firstOrNull()?.toString() ?: "字",
                    canvasWidth / 2,
                    yPos,
                    paint
                )

                // Active animated stroke representation indicator (Lilac)
                val activePaint = android.graphics.Paint().apply {
                    textSize = canvasWidth * 0.70f
                    textAlign = android.graphics.Paint.Align.CENTER
                    isAntiAlias = true
                    color = android.graphics.Color.argb(
                        (150 + (105 * animProgress.value)).toInt().coerceIn(0, 255),
                        208,
                        188,
                        255
                    )
                    typeface = android.graphics.Typeface.SERIF
                }
                drawContext.canvas.nativeCanvas.drawText(
                    hanzi.firstOrNull()?.toString() ?: "字",
                    canvasWidth / 2,
                    yPos,
                    activePaint
                )
            }

            // Step Indicator Badge
            Surface(
                color = LilacPrimary,
                shape = CircleShape,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(32.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "${currentStep + 1}/${strokes.size}",
                        color = LilacPrimaryDark,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Stroke Name Badge
        val currentStrokeName = strokes.getOrElse(currentStep) { "Stroke ${currentStep + 1}" }
        Surface(
            color = DarkSurfaceElevated,
            shape = RoundedCornerShape(20.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, OutlineBorder),
            modifier = Modifier.padding(vertical = 4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "Stroke ${currentStep + 1}: ",
                    fontWeight = FontWeight.Bold,
                    color = LilacPrimary,
                    fontSize = 13.sp
                )
                Text(
                    text = currentStrokeName,
                    fontWeight = FontWeight.Medium,
                    color = TextLight,
                    fontSize = 13.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Controls (Step Prev, Play/Pause, Step Next, Replay)
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = {
                    isPlaying = false
                    currentStep = if (currentStep > 0) currentStep - 1 else strokes.size - 1
                },
                modifier = Modifier.size(38.dp)
            ) {
                Icon(Icons.Default.FastRewind, contentDescription = "Previous stroke", tint = LilacPrimary)
            }

            ElevatedButton(
                onClick = { isPlaying = !isPlaying },
                colors = ButtonDefaults.elevatedButtonColors(
                    containerColor = LilacPrimary,
                    contentColor = LilacPrimaryDark
                ),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.height(38.dp)
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Refresh else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(if (isPlaying) "Playing" else "Play", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }

            IconButton(
                onClick = {
                    isPlaying = false
                    currentStep = (currentStep + 1) % strokes.size
                },
                modifier = Modifier.size(38.dp)
            ) {
                Icon(Icons.Default.FastForward, contentDescription = "Next stroke", tint = LilacPrimary)
            }
        }
    }
}

@Composable
fun UserTracingCanvas(
    hanzi: String,
    targetStrokeCount: Int
) {
    val strokes = remember { mutableStateListOf<UserStroke>() }
    var currentPoints by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var completed by remember { mutableStateOf(false) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .size(230.dp)
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(DarkSurfaceContainer)
                .border(2.dp, if (completed) SrsGoodDark else OutlineBorder, RoundedCornerShape(16.dp))
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            currentPoints = listOf(offset)
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            currentPoints = currentPoints + change.position
                        },
                        onDragEnd = {
                            if (currentPoints.isNotEmpty()) {
                                strokes.add(UserStroke(currentPoints))
                                currentPoints = emptyList()
                                if (strokes.size >= maxOf(1, targetStrokeCount)) {
                                    completed = true
                                }
                            }
                        },
                        onDragCancel = {
                            currentPoints = emptyList()
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            // Draw Rice Grid
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawRiceGrid(size.width, size.height)
            }

            // Draw Background Ghost Character to trace over
            Canvas(modifier = Modifier.fillMaxSize()) {
                val canvasWidth = size.width
                val canvasHeight = size.height
                val paint = android.graphics.Paint().apply {
                    textSize = canvasWidth * 0.70f
                    textAlign = android.graphics.Paint.Align.CENTER
                    isAntiAlias = true
                    color = android.graphics.Color.argb(45, 202, 196, 208) // Light Ghost Guide
                    typeface = android.graphics.Typeface.SERIF
                }
                val yPos = (canvasHeight / 2) - ((paint.descent() + paint.ascent()) / 2)
                drawContext.canvas.nativeCanvas.drawText(
                    hanzi.firstOrNull()?.toString() ?: "字",
                    canvasWidth / 2,
                    yPos,
                    paint
                )
            }

            // Draw User's active drawn strokes
            Canvas(modifier = Modifier.fillMaxSize()) {
                val strokeColor = if (completed) SrsGoodDark else TextLight

                // Finished strokes
                strokes.forEach { userStroke ->
                    if (userStroke.points.size > 1) {
                        val path = Path().apply {
                            moveTo(userStroke.points.first().x, userStroke.points.first().y)
                            for (i in 1 until userStroke.points.size) {
                                lineTo(userStroke.points[i].x, userStroke.points[i].y)
                            }
                        }
                        drawPath(
                            path = path,
                            color = strokeColor,
                            style = Stroke(
                                width = 18f,
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round
                            )
                        )
                    }
                }

                // Current dragging stroke
                if (currentPoints.size > 1) {
                    val currentPath = Path().apply {
                        moveTo(currentPoints.first().x, currentPoints.first().y)
                        for (i in 1 until currentPoints.size) {
                            lineTo(currentPoints[i].x, currentPoints[i].y)
                        }
                    }
                    drawPath(
                        path = currentPath,
                        color = LilacPrimary,
                        style = Stroke(
                            width = 18f,
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )
                }
            }

            // Progress indicator badge
            Surface(
                color = if (completed) SrsGoodDark else DarkSurfaceElevated,
                shape = CircleShape,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(32.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "${strokes.size}/$targetStrokeCount",
                        color = if (completed) SrsGoodContainer else TextLight,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        AnimatedVisibility(visible = completed) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .background(SrsGoodContainer, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = SrsGoodDark, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Great job! 很好!", color = SrsGoodDark, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = {
                    strokes.clear()
                    currentPoints = emptyList()
                    completed = false
                },
                modifier = Modifier.height(38.dp),
                shape = RoundedCornerShape(18.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, OutlineBorder),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextLight)
            ) {
                Icon(Icons.Default.Clear, contentDescription = "Clear", modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Clear Canvas", fontSize = 12.sp)
            }

            if (strokes.isNotEmpty()) {
                FilledTonalButton(
                    onClick = {
                        if (strokes.isNotEmpty()) {
                            strokes.removeAt(strokes.lastIndex)
                            completed = false
                        }
                    },
                    modifier = Modifier.height(38.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = DarkSurfaceElevated,
                        contentColor = TextLight
                    )
                ) {
                    Text("Undo Stroke", fontSize = 12.sp)
                }
            }
        }
    }
}

private fun DrawScope.drawRiceGrid(width: Float, height: Float) {
    val strokeStyle = Stroke(
        width = 1.5f,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
    )

    // Center Cross Lines
    drawLine(
        color = TianGridCenter,
        start = Offset(0f, height / 2),
        end = Offset(width, height / 2),
        strokeWidth = 2f,
        pathEffect = strokeStyle.pathEffect
    )
    drawLine(
        color = TianGridCenter,
        start = Offset(width / 2, 0f),
        end = Offset(width / 2, height),
        strokeWidth = 2f,
        pathEffect = strokeStyle.pathEffect
    )

    // Diagonal Lines (米字格)
    drawLine(
        color = TianGridLine,
        start = Offset(0f, 0f),
        end = Offset(width, height),
        strokeWidth = 1.2f,
        pathEffect = strokeStyle.pathEffect
    )
    drawLine(
        color = TianGridLine,
        start = Offset(width, 0f),
        end = Offset(0f, height),
        strokeWidth = 1.2f,
        pathEffect = strokeStyle.pathEffect
    )
}

