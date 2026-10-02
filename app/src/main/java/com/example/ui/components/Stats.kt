package com.example.ui.components

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
    Box(
        modifier = modifier.size(diameter),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier.size(diameter),
            color = accent,
            trackColor = DarkSurfaceHighest,
            strokeWidth = strokeWidth,
            strokeCap = StrokeCap.Round
        )
        content()
    }
}

/** A rounded progress bar. Used for goals and per-topic completion. */
@Composable
fun GoalBar(
    progress: Float,
    modifier: Modifier = Modifier,
    accent: Color = AccentPrimary,
    trackColor: Color = DarkSurfaceHighest,
    height: Dp = 10.dp
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(50))
            .background(trackColor)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .fillMaxHeight()
                .clip(RoundedCornerShape(50))
                .background(accent)
        )
    }
}

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
