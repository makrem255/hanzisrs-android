package com.example.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.AppTheme
import com.example.ui.theme.Dimens

/**
 * The press feedback every button in the app shares: a short compress and release.
 *
 * The interaction source is *passed into* [clickable] rather than detected alongside it, so the
 * scale and the ripple read from one gesture stream and cannot disagree about when a press began
 * or ended - a second detector would lag the ripple by a frame and the two would drift apart
 * under a fast double tap. `LocalIndication` is precisely what `clickable` would have used on its
 * own, so the ripple that is there now is the ripple that stays.
 *
 * The scale runs in a graphics layer, so nothing here re-lays-out: only the layer's matrix
 * changes for the ~120ms a press lasts.
 */
@Composable
private fun rememberPressState(): Pair<MutableInteractionSource, Boolean> {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    return source to pressed
}

/** The shared press scale, driven by [rememberPressState]'s flag. */
@Composable
private fun pressScale(pressed: Boolean): State<Float> =
    animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 700f),
        label = "buttonPress"
    )

/**
 * The primary action.
 *
 * A full-width, 54dp-tall pill in the theme's button fill. When disabled it falls
 * back to a flat surface step so it still reads as a button rather than vanishing.
 *
 * The `brush` override exists for the rare surface that needs a gradient rather than
 * the flat fill; every ordinary call leaves it null and gets the monochrome button.
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
    brush: Brush? = null,
    cornerRadius: Dp = 18.dp
) {
    val colors = AppTheme.colors
    val fill: Brush = when {
        !enabled -> SolidColor(colors.highest)
        brush != null -> brush
        else -> SolidColor(colors.button)
    }
    val ink = if (enabled) colors.onButton else colors.textSecondary
    val (pressSource, isPressed) = rememberPressState()
    val scale = pressScale(isPressed)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(Dimens.buttonHeight)
            .graphicsLayer {
                // Read inside the layer, not in composition: the spring runs on the draw node
                // instead of recomposing the button and its children on every frame of it.
                scaleX = scale.value
                scaleY = scale.value
            }
            .clip(RoundedCornerShape(cornerRadius))
            .background(fill)
            .clickable(
                interactionSource = pressSource,
                indication = LocalIndication.current,
                enabled = enabled,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (leadingIcon != null) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    tint = ink,
                    modifier = Modifier.size(Dimens.icon)
                )
                Spacer(Modifier.width(Dimens.sm))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = ink
            )
        }
    }
}

/** The secondary action: an outlined pill the same size as [PrimaryButton]. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
    accent: Color? = null,
    cornerRadius: Dp = 18.dp
) {
    val colors = AppTheme.colors
    val tone = accent ?: colors.textPrimary
    val (pressSource, isPressed) = rememberPressState()
    val scale = pressScale(isPressed)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(Dimens.buttonHeight)
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            }
            .clip(RoundedCornerShape(cornerRadius))
            .border(BorderStroke(1.dp, tone.copy(alpha = if (enabled) 0.55f else 0.25f)), RoundedCornerShape(cornerRadius))
            .clickable(
                interactionSource = pressSource,
                indication = LocalIndication.current,
                enabled = enabled,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (leadingIcon != null) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    tint = tone,
                    modifier = Modifier.size(Dimens.icon)
                )
                Spacer(Modifier.width(Dimens.sm))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = tone
            )
        }
    }
}

/** A compact, rounded button for inline actions. */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color? = null,
    contentColor: Color? = null,
    leadingIcon: ImageVector? = null
) {
    val colors = AppTheme.colors
    val fill = containerColor ?: colors.highest
    val ink = contentColor ?: colors.textPrimary
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(fill)
            .clickable(onClick = onClick)
            .padding(horizontal = Dimens.md, vertical = Dimens.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = ink,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(Dimens.xs))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = ink
        )
    }
}

/**
 * A status badge: a tinted pill whose text carries the meaning.
 *
 * The colour is passed in because the caller is the one that knows whether "Review"
 * is amber and "Mastered" is mint; this component only guarantees they all look like
 * they belong to the same family.
 */
@Composable
fun StatusPill(
    text: String,
    accent: Color,
    modifier: Modifier = Modifier,
    dot: Boolean = true
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(accent.copy(alpha = 0.14f))
            .padding(horizontal = Dimens.sm, vertical = Dimens.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (dot) {
            Box(
                modifier = Modifier
                    .size(Dimens.dot)
                    .clip(CircleShape)
                    .background(accent)
            )
            Spacer(Modifier.width(Dimens.xs))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = accent
        )
    }
}

/**
 * A horizontal segmented control.
 *
 * Used where a screen has a small, fixed set of views over the same data (the
 * dashboard's range, the library's filters). The selected segment is the accent; the
 * rest are quiet.
 */
@Composable
fun <T> SegmentedSelector(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier
) {
    val colors = AppTheme.colors
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(colors.highest.copy(alpha = 0.6f))
            .padding(Dimens.xs),
        horizontalArrangement = Arrangement.spacedBy(Dimens.xs)
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(50))
                    .background(if (isSelected) colors.button else Color.Transparent)
                    .clickable { onSelect(option) }
                    .padding(vertical = Dimens.sm),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label(option),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isSelected) colors.onButton else colors.textSecondary
                )
            }
        }
    }
}

/**
 * A round, tappable icon control.
 *
 * The pronunciation control is the one place in the app where a single button is the
 * focal point of the screen, so it gets its own component rather than a Material
 * `IconButton` that would render it small and generic.
 */
@Composable
fun CircleIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    diameter: Dp = Dimens.audioButton,
    containerColor: Color? = null,
    contentColor: Color? = null,
    borderColor: Color? = null
) {
    val colors = AppTheme.colors
    val fill = containerColor ?: colors.textPrimary.copy(alpha = 0.10f)
    val ink = contentColor ?: colors.textPrimary
    val line = borderColor ?: colors.textPrimary.copy(alpha = 0.25f)
    Box(
        modifier = modifier
            .size(diameter)
            .clip(CircleShape)
            .background(fill)
            .then(
                Modifier.border(BorderStroke(1.dp, line), CircleShape)
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = ink,
            modifier = Modifier.size(diameter * 0.42f)
        )
    }
}

/** Padding used by inline content that should align to the screen gutter. */
val ScreenPadding = PaddingValues(horizontal = Dimens.screenH)
