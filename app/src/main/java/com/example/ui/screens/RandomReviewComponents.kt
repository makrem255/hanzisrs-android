package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.ui.components.IconBadge
import com.example.ui.theme.AccentMint
import com.example.ui.theme.AccentPrimary
import com.example.ui.theme.AccentPrimaryInk
import com.example.ui.theme.DarkSurfaceCard
import com.example.ui.theme.DarkSurfaceElevated
import com.example.ui.theme.Dimens
import com.example.ui.theme.OutlineSubtle
import com.example.ui.theme.TextLight
import com.example.ui.theme.TextMuted

/** Milliseconds between successive items of a staggered reveal. */
private const val STAGGER_MILLIS = 70

/**
 * An easing that arrives quickly and settles.
 *
 * Chosen over the default spring for the reveal because the stagger already supplies the sense of
 * movement: a bouncy curve on top of a 70ms cascade reads as four separate little arrivals
 * rather than as one answer uncovering itself.
 */
private val RevealEase = CubicBezierEasing(0.25f, 1f, 0.5f, 1f)

/**
 * Adds one line of the staggered answer reveal.
 *
 * The delay is [order] * [STAGGER_MILLIS], so pinyin lands before the meaning, the meaning before
 * the example, and the example before the audio control. Staggered rather than simultaneous
 * because a reveal arriving as a block reads as a page loading; arriving in reading order reads
 * as the answer being uncovered one fact at a time, which is what the learner asked for when
 * they tapped it.
 *
 * The exit is a short plain fade with no delay: hiding something the learner has already read
 * should be instant. The anticipation belongs to showing, not to taking away.
 */
@Composable
fun RevealItem(
    visible: Boolean,
    order: Int,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val delay = order * STAGGER_MILLIS
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(320, delayMillis = delay, easing = RevealEase)) +
            slideInVertically(tween(380, delayMillis = delay, easing = RevealEase)) { it / 4 } +
            scaleIn(
                initialScale = 0.97f,
                animationSpec = tween(380, delayMillis = delay, easing = RevealEase)
            ),
        exit = fadeOut(tween(140)),
        modifier = modifier
    ) { content() }
}

private val MIC_ORB_SIZE = 132.dp
private val MIC_CORE_SIZE = 84.dp

/**
 * The microphone, and the ripple it makes while listening.
 *
 * Two rings driven by one animation offset by half a cycle, so the effect is continuous outward
 * travel rather than a single pulse that has to be watched for. The rings are composed only while
 * [listening]: an infinite transition left running on an idle screen is a permanently scheduled
 * frame callback, which is exactly the kind of cost that turns a smooth app into a warm one.
 *
 * The button itself never resizes. A control that grows under a finger is a control that can be
 * missed, so the animation lives in the rings behind it and in a small scale on the orb - both of
 * which leave its hit target exactly where it was.
 */
@Composable
fun AnimatedMicButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    listening: Boolean = false,
    enabled: Boolean = true,
    icon: ImageVector,
    contentDescription: String,
    tint: Color = AccentPrimary
) {
    val scale by animateFloatAsState(
        targetValue = if (listening) 1.05f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 220f),
        label = "micScale"
    )

    Box(
        modifier = modifier.size(MIC_ORB_SIZE),
        contentAlignment = Alignment.Center
    ) {
        if (listening && enabled) {
            RippleRings(tint = tint)
        }
        Surface(
            shape = CircleShape,
            color = if (listening) tint else DarkSurfaceElevated,
            border = BorderStroke(1.dp, if (listening) tint else OutlineSubtle),
            modifier = Modifier
                .size(MIC_CORE_SIZE)
                .scale(scale)
                .clip(CircleShape)
                .clickable(enabled = enabled, onClick = onClick)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    tint = if (listening) AccentPrimaryInk else tint,
                    modifier = Modifier.size(30.dp)
                )
            }
        }
    }
}

/**
 * The expanding rings behind the microphone.
 *
 * One [Canvas] rather than three animated `Box`es: three separately animated composables are
 * three state writes per frame against one, and the rings are a single idea rather than three.
 */
@Composable
private fun RippleRings(tint: Color) {
    val transition = rememberInfiniteTransition(label = "micRipple")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "micRippleProgress"
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        val maxRadius = size.minDimension / 2f
        // Two rings from one clock. The second is the first delayed by half a cycle, which keeps
        // the travel continuous without running a second animation to get there.
        listOf(progress, (progress + 0.5f) % 1f).forEach { p ->
            drawCircle(
                color = tint.copy(alpha = (1f - p) * 0.34f),
                radius = maxRadius * (0.38f + 0.62f * p),
                style = Stroke(width = 2.dp.toPx())
            )
        }
        // A soft halo hugging the button, so the rings read as emanating from it rather than
        // floating near it.
        drawCircle(color = tint.copy(alpha = 0.10f), radius = maxRadius * 0.42f)
    }
}

/**
 * The confirmation that a word was recognised: a check that lands with a little weight.
 *
 * [androidx.compose.animation.scaleIn] would start the mark from zero, so it is composed already
 * visible and given its motion by an overshooting spring instead - a symbol meaning "yes" should
 * arrive with a definite click of scale, not fade up out of nothing.
 */
@Composable
fun RecognisedBadge(modifier: Modifier = Modifier) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val overshoot by animateFloatAsState(
        targetValue = if (shown) 1f else 0.6f,
        animationSpec = spring(dampingRatio = 0.45f, stiffness = 320f),
        label = "recognisedOvershoot"
    )
    Box(
        modifier = modifier
            .scale(overshoot)
            .graphicsLayer { alpha = overshoot.coerceIn(0f, 1f) },
        contentAlignment = Alignment.Center
    ) {
        IconBadge(
            icon = Icons.Default.CheckCircle,
            contentDescription = "Recognised",
            tint = AccentMint,
            background = AccentMint.copy(alpha = 0.16f),
            size = 48.dp,
            cornerRadius = 24.dp
        )
    }
}

/**
 * One labelled line of the answer.
 *
 * The label is its own muted line so pinyin, meaning and example scan as three facts rather than
 * as a run-on sentence, and so a long example never has to compete with the meaning for weight.
 */
@Composable
fun AnswerLine(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueStyle: TextStyle = MaterialTheme.typography.titleMedium,
    valueColor: Color = TextLight
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted
        )
        Spacer(Modifier.height(Dimens.xxs))
        Text(
            text = value,
            style = valueStyle,
            color = valueColor,
            textAlign = TextAlign.Start
        )
    }
}

/** A quiet horizontal rule separating the character from the answer beneath it. */
@Composable
fun CardRule(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(OutlineSubtle)
    )
}

/** A small chip, used for the word counter and for the ungraded-words note. */
@Composable
fun MetaChip(
    text: String,
    modifier: Modifier = Modifier,
    tint: Color = TextMuted,
    leading: ImageVector? = null
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(DarkSurfaceCard)
            .padding(horizontal = Dimens.md, vertical = Dimens.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leading != null) {
            Icon(
                imageVector = leading,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(Dimens.xs))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = tint
        )
    }
}
