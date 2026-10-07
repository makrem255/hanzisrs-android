package com.example.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.AccentPrimary
import com.example.ui.theme.DarkSurfaceHighest
import com.example.ui.theme.Dimens
import com.example.ui.theme.StatNumber
import com.example.ui.theme.TextLight
import com.example.ui.theme.TextMuted

/**
 * A single metric.
 *
 * The number is the largest thing in the tile and is set in the tabular figure style,
 * so a row of these aligns and does not jitter as values change.
 */
@Composable
fun StatTile(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    caption: String? = null
) {
    AppCard(
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(Dimens.md)
    ) {
        if (icon != null) {
            IconBadge(
                icon = icon,
                tint = accent,
                background = accent.copy(alpha = 0.14f),
                size = 32.dp,
                cornerRadius = 10.dp
            )
            Spacer(Modifier.height(Dimens.sm))
        }
        Text(
            text = value,
            style = StatNumber,
            color = TextLight
        )
        Spacer(Modifier.height(Dimens.xxs))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = TextMuted
        )
        if (caption != null) {
            Spacer(Modifier.height(Dimens.xxs))
            Text(
                text = caption,
                style = MaterialTheme.typography.labelSmall,
                color = accent
            )
        }
    }
}

/**
 * A progress ring with content in the middle.
 *
 * Used for the daily goal, where the number belongs *inside* the shape that measures
 * it rather than beside it.
 *
 * The sweep eases to its new value rather than jumping. A ring that jumps reads as a number
 * changing; a ring that travels reads as progress being made, which is the entire thing the
 * shape is there to communicate. Long enough to be legible (600ms), short enough that a learner
 * waiting on the next screen does not notice it.
 */
@Composable
fun ProgressRing(
    progress: Float,
    modifier: Modifier = Modifier,
    accent: Color = AccentPrimary,
    diameter: Dp = Dimens.ring,
    strokeWidth: Dp = 8.dp,
    content: @Composable () -> Unit
) {
    val sweep by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 600, easing = EaseOutQuint),
        label = "progressRing"
    )

    Box(
        modifier = modifier.size(diameter),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(
            progress = { sweep },
            modifier = Modifier.size(diameter),
            color = accent,
            trackColor = DarkSurfaceHighest,
            strokeWidth = strokeWidth,
            strokeCap = StrokeCap.Round
        )
        content()
    }
}

/**
 * A rounded progress bar. Used for goals and per-topic completion.
 *
 * Animated for the same reason as [ProgressRing], and because the bar is usually one of several
 * that arrive together, they share one curve so a screen of them settles as a group instead of
 * each stopping at a different moment.
 */
@Composable
fun GoalBar(
    progress: Float,
    modifier: Modifier = Modifier,
    accent: Color = AccentPrimary,
    trackColor: Color = DarkSurfaceHighest,
    height: Dp = 10.dp
) {
    val sweep by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 600, easing = EaseOutQuint),
        label = "goalBar"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(50))
            .background(trackColor)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(sweep)
                .fillMaxHeight()
                .clip(RoundedCornerShape(50))
                .background(accent)
        )
    }
}

/**
 * The shared settle for every measured quantity on screen.
 *
 * Declared once so the ring and the bars agree: `0.25f, 0.1f, 0.25f, 1f` starts quickly, holds
 * through the middle where the value is actually being read, and lands without an overshoot.
 * An overshoot on a progress bar would briefly report more progress than exists.
 */
private val EaseOutQuint = androidx.compose.animation.core.CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)

/** A label/value row used in compact stat groups. */
@Composable
fun MetricRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    accent: Color = TextLight
) {
    androidx.compose.foundation.layout.Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = TextMuted,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = accent
        )
    }
}
