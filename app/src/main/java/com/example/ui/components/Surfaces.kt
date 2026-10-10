package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ui.theme.AppTheme
import com.example.ui.theme.Dimens

/**
 * The standard card.
 *
 * One composable draws every panel in the app, so the border, radius and padding are
 * the same on the dashboard as they are in settings. A card may be clickable, may be
 * filled with a gradient instead of a flat colour, and may suppress its border when
 * the fill already separates it from the background.
 */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    containerColor: Color? = null,
    borderColor: Color? = null,
    brush: Brush? = null,
    contentPadding: PaddingValues = PaddingValues(Dimens.lg),
    cornerRadius: androidx.compose.ui.unit.Dp = 20.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = AppTheme.colors
    val fill = containerColor ?: colors.card
    val line = borderColor ?: colors.borderSubtle
    val shape = RoundedCornerShape(cornerRadius)
    Surface(
        modifier = modifier
            .clip(shape)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        shape = shape,
        color = if (brush == null) fill else Color.Transparent,
        border = if (brush == null && line != Color.Transparent) {
            BorderStroke(1.dp, line)
        } else {
            null
        }
    ) {
        Column(
            modifier = Modifier
                .then(if (brush != null) Modifier.background(brush) else Modifier)
                .padding(contentPadding),
            content = content
        )
    }
}

/**
 * A rounded square holding an icon.
 *
 * Used to lead a card or a row. The tint is passed in so the same shape can mark an
 * accent action, an AI surface or a neutral list row without a second component.
 */
@Composable
fun IconBadge(
    icon: ImageVector,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    tint: Color? = null,
    background: Color? = null,
    size: androidx.compose.ui.unit.Dp = 40.dp,
    cornerRadius: androidx.compose.ui.unit.Dp = 14.dp
) {
    val colors = AppTheme.colors
    val ink = tint ?: colors.textPrimary
    val wash = background ?: colors.textPrimary.copy(alpha = 0.10f)
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(cornerRadius))
            .background(wash),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = ink,
            modifier = Modifier.size(size * 0.5f)
        )
    }
}

/**
 * A section heading with an optional trailing action.
 *
 * Deliberately not a Material `TopAppBar`: sections inside a scrolling screen need a
 * lightweight heading that does not carry elevation or a navigation slot.
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = AppTheme.colors.textPrimary
            )
            if (subtitle != null) {
                Spacer(Modifier.height(Dimens.xxs))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTheme.colors.textSecondary
                )
            }
        }
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) {
                Text(
                    text = actionLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = AppTheme.colors.textPrimary
                )
            }
        }
    }
}

/**
 * The page title at the top of a screen: a large greeting or heading, an optional
 * supporting line, and an optional trailing slot for an avatar or action.
 */
@Composable
fun ScreenTitle(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                color = AppTheme.colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Spacer(Modifier.height(Dimens.xs))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppTheme.colors.textSecondary
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(Dimens.md))
            trailing()
        }
    }
}

/** A thin divider that reads as a line inside a card rather than a full-bleed rule. */
@Composable
fun CardDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(AppTheme.colors.borderSubtle)
    )
}

/** The empty spacer used to build vertical rhythm on the 4dp grid. */
@Composable
fun VSpace(height: androidx.compose.ui.unit.Dp) {
    Spacer(Modifier.height(height))
}

/** The empty spacer used to build horizontal rhythm on the 4dp grid. */
@Composable
fun HSpace(width: androidx.compose.ui.unit.Dp) {
    Spacer(Modifier.width(width))
}
