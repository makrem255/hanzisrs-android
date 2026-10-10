package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.AppTheme
import com.example.ui.theme.Dimens

/**
 * The one loading treatment.
 *
 * Every screen that waits shows this, so the app has a single answer to "it is
 * working" instead of several bespoke spinners. The label is required to name what is
 * being waited for — a spinner with no noun is not information.
 */
@Composable
fun LoadingState(
    label: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Dimens.xxxl),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(32.dp),
            color = AppTheme.colors.textPrimary,
            trackColor = AppTheme.colors.highest,
            strokeWidth = 3.dp
        )
        Spacer(Modifier.height(Dimens.md))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = AppTheme.colors.textSecondary
        )
    }
}

/**
 * The one empty treatment.
 *
 * An empty screen is a state, not a failure: it gets an icon, a short headline, one
 * sentence of explanation, and — where there is something the learner can do — a
 * button. It is never left blank.
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    accent: Color? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Dimens.xxxl, horizontal = Dimens.lg),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        IconBadge(
            icon = icon,
            tint = accent,
            background = accent?.copy(alpha = 0.12f),
            size = 64.dp,
            cornerRadius = 20.dp
        )
        Spacer(Modifier.height(Dimens.lg))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = AppTheme.colors.textPrimary,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(Dimens.sm))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = AppTheme.colors.textSecondary,
            textAlign = TextAlign.Center
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(Dimens.xl))
            PrimaryButton(
                text = actionLabel,
                onClick = onAction,
                modifier = Modifier.fillMaxWidth(0.8f)
            )
        }
    }
}

/**
 * The one error treatment.
 *
 * Messages are written for the learner, not read from an exception: the callers pass
 * prose, and a retry is offered only where retrying can actually help.
 */
@Composable
fun ErrorState(
    message: String,
    modifier: Modifier = Modifier,
    title: String = "Something went wrong",
    onRetry: (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Dimens.xxxl, horizontal = Dimens.lg),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        IconBadge(
            icon = Icons.Default.Warning,
            tint = AppTheme.colors.error,
            background = AppTheme.colors.error.copy(alpha = 0.12f),
            size = 56.dp,
            cornerRadius = 18.dp
        )
        Spacer(Modifier.height(Dimens.lg))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = AppTheme.colors.textPrimary,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(Dimens.sm))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = AppTheme.colors.textSecondary,
            textAlign = TextAlign.Center
        )
        if (onRetry != null) {
            Spacer(Modifier.height(Dimens.xl))
            SecondaryButton(
                text = "Try again",
                onClick = onRetry,
                modifier = Modifier.fillMaxWidth(0.8f)
            )
        }
    }
}

/**
 * An inline notice for a condition that is not fatal: AI unavailable, audio
 * unavailable, offline. Tinted by meaning and never red unless something is wrong.
 */
@Composable
fun InlineNotice(
    text: String,
    modifier: Modifier = Modifier,
    tone: Color? = null,
    icon: ImageVector? = null
) {
    val colors = AppTheme.colors
    val ink = tone ?: colors.textSecondary
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(ink.copy(alpha = 0.10f))
            .padding(horizontal = Dimens.md, vertical = Dimens.sm)
    ) {
        androidx.compose.foundation.layout.Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = ink,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(Dimens.sm))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = ink,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** A small dot used as a status light beside a label. */
@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier, diameter: Dp = Dimens.dot) {
    Box(
        modifier = modifier
            .size(diameter)
            .clip(CircleShape)
            .background(color)
    )
}
