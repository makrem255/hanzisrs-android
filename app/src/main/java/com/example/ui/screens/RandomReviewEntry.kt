package com.example.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.WordWithSrs
import com.example.data.review.HANZI_WHITE_ARGB
import com.example.data.review.RANDOM_REVIEW_HISTORY_SIZE
import com.example.data.review.RANDOM_REVIEW_MAX_ATTEMPTS
import com.example.data.review.RANDOM_REVIEW_MIN_VOCABULARY
import com.example.data.review.SELECTION_PALETTE
import com.example.data.review.selectionSchedule
import com.example.ui.components.AppCard
import com.example.ui.components.IconBadge
import com.example.ui.components.PrimaryButton
import com.example.ui.components.SecondaryButton
import com.example.ui.theme.AppTheme
import com.example.ui.theme.Dimens
import com.example.ui.theme.HanziLarge
import kotlinx.coroutines.delay

/**
 * The Random Review doorway: a marquee of the learner's own words, one START button, and honest
 * gating.
 *
 * The strip and the gate both read the live collection, so the doorway can never advertise words
 * the learner does not have or offer a spin it cannot pay for. The count is rechecked on every
 * START press rather than trusted from composition time: words added or deleted while this
 * screen sat open change the answer, and a cached count would start a wheel over a collection
 * that no longer qualifies.
 */
@Composable
fun RandomReviewEntry(
    words: List<WordWithSrs>,
    wordsLoaded: Boolean,
    onStart: () -> Unit,
    onAddWords: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showHelp by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Random Review",
            style = MaterialTheme.typography.headlineMedium,
            color = AppTheme.colors.textPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(Dimens.xs))
        Text(
            text = "A surprise word, out loud. Nothing gets rescheduled.",
            style = MaterialTheme.typography.bodyMedium,
            color = AppTheme.colors.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(Dimens.xl))

        // The learner's own words rolling past - a preview of what the wheel can land on, and
        // proof before a single tap that this mode only ever asks about their library.
        VocabStrip(
            hanzis = words.take(30).map { it.word.hanzi },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(Dimens.xxl))

        PrimaryButton(
            text = "START",
            onClick = onStart,
            enabled = wordsLoaded,
            leadingIcon = Icons.Default.PlayArrow,
            modifier = Modifier
                .fillMaxWidth(0.72f)
                .testTag("random_review_start"),
        )

        IconButton(
            onClick = { showHelp = true },
            modifier = Modifier
                .testTag("random_review_help")
                .size(48.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.HelpOutline,
                contentDescription = "How Random Review works",
                tint = AppTheme.colors.textSecondary,
                modifier = Modifier.size(26.dp),
            )
        }

        Spacer(Modifier.height(Dimens.md))

        when {
            !wordsLoaded -> Text(
                text = "Loading your words…",
                style = MaterialTheme.typography.bodySmall,
                color = AppTheme.colors.textSecondary,
            )

            words.size < RANDOM_REVIEW_MIN_VOCABULARY -> AppCard(
                containerColor = AppTheme.colors.card,
                borderColor = AppTheme.colors.textPrimary.copy(alpha = 0.4f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "You'll need at least $RANDOM_REVIEW_MIN_VOCABULARY saved words " +
                        "to start — randomness needs room. " +
                        "You have ${words.size} of $RANDOM_REVIEW_MIN_VOCABULARY.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppTheme.colors.textPrimary,
                )
                Spacer(Modifier.height(Dimens.md))
                SecondaryButton(
                    text = "Add words",
                    onClick = onAddWords,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("random_review_add_words"),
                )
            }

            else -> Text(
                text = "${words.size} words in the wheel · recent " +
                    "$RANDOM_REVIEW_HISTORY_SIZE never repeat back-to-back",
                style = MaterialTheme.typography.labelSmall,
                color = AppTheme.colors.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    if (showHelp) {
        RandomReviewHelpDialog(onDismiss = { showHelp = false })
    }
}

/**
 * The learner's words rolling past in a seamless loop.
 *
 * Two identical halves, one measured width, one linear infinite offset from 0 to exactly half:
 * when the first half has fully exited, the second sits precisely where it started, so the
 * wrap is invisible. The offset is applied in [graphicsLayer], so the animation runs on the
 * draw node and never recomposes the row. Items carry their own trailing padding rather than
 * `spacedBy`, because a separator *between* the halves would make half the width inexact and
 * the loop visibly jump.
 */
@Composable
private fun VocabStrip(
    hanzis: List<String>,
    modifier: Modifier = Modifier,
) {
    if (hanzis.isEmpty()) return

    var fullWidthPx by remember { mutableIntStateOf(0) }
    val loop by rememberInfiniteTransition(label = "vocabStrip").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 18_000, easing = LinearEasing),
        ),
        label = "vocabStripFraction",
    )

    Box(
        modifier = modifier
            .clipToBounds()
            .padding(vertical = Dimens.sm),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .onGloballyPositioned { fullWidthPx = it.size.width }
                .graphicsLayer { translationX = -loop * fullWidthPx / 2f },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            (hanzis + hanzis).forEach { hanzi ->
                Text(
                    text = hanzi,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppTheme.colors.textPrimary.copy(alpha = 0.85f),
                    modifier = Modifier.padding(end = 28.dp),
                    maxLines = 1,
                )
            }
        }
    }
}

/** What the mode does, how it picks, and what the three tries mean. */
@Composable
fun RandomReviewHelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            IconBadge(
                icon = Icons.Outlined.HelpOutline,
                tint = AppTheme.colors.textPrimary,
                background = AppTheme.colors.textPrimary.copy(alpha = 0.14f),
                size = 40.dp,
                cornerRadius = 13.dp,
            )
        },
        title = {
            Text(
                text = "How Random Review works",
                style = MaterialTheme.typography.titleLarge,
                color = AppTheme.colors.textPrimary,
            )
        },
        text = {
            Text(
                text = "Random Review is pronunciation practice. It draws only from words " +
                    "you saved, prefers words you have studied, and never touches your " +
                    "review schedule.\n\n" +
                    "Tap START and the wheel spins, then say the word into the microphone. " +
                    "If the recogniser hears your word, you move on. If not, the word " +
                    "glows red and you try again.\n\n" +
                    "After $RANDOM_REVIEW_MAX_ATTEMPTS unsuccessful tries the answer opens: " +
                    "meaning, Pinyin, and a button that plays the correct pronunciation.\n\n" +
                    "One honest note: the phone checks which words it heard, not how " +
                    "perfect your tones were. No score is shown because none is measured.",
                style = MaterialTheme.typography.bodyMedium,
                color = AppTheme.colors.textSecondary,
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "Got it", color = AppTheme.colors.textPrimary)
            }
        },
        containerColor = AppTheme.colors.card,
    )
}

/** "Are you sure?" before leaving a live sitting. */
@Composable
fun RandomReviewExitDialog(
    onConfirmExit: () -> Unit,
    onStay: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onStay,
        title = {
            Text(
                text = "Exit Random Review?",
                style = MaterialTheme.typography.titleLarge,
                color = AppTheme.colors.textPrimary,
            )
        },
        text = {
            Text(
                text = "Your vocabulary is untouched - but the word on screen and its " +
                    "attempt count will be gone.",
                style = MaterialTheme.typography.bodyMedium,
                color = AppTheme.colors.textSecondary,
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirmExit,
                modifier = Modifier.testTag("random_review_exit_yes"),
            ) {
                Text(text = "Yes, exit", color = AppTheme.colors.textPrimary)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onStay,
                modifier = Modifier.testTag("random_review_exit_no"),
            ) {
                Text(text = "Keep going", color = AppTheme.colors.textPrimary)
            }
        },
        containerColor = AppTheme.colors.card,
    )
}

/**
 * The word wheel: pool words flashing past with one tick each, slowing to the drawn word.
 *
 * Hanzi only, by contract - no Pinyin, meaning or hints appear until the pronunciation screen.
 * The deceleration lives in the [schedule]: each step holds longer than the last, and because
 * [onTick] fires once per displayed word, the audible pacing matches the visual one without any
 * rate tricks. The background retargets an [animateColorAsState] per step, so colours melt
 * rather than flash.
 *
 * Coroutine-safe by construction: the loop is a single [LaunchedEffect], so leaving the screen
 * cancels it - no second spin can start while one runs because the caller only runs one, and
 * stopping early simply never calls [onFinish].
 */
@Composable
fun SelectionAnimation(
    poolHanzis: List<String>,
    totalMs: Long,
    steps: Int,
    onTick: () -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val schedule = remember(totalMs, steps) { selectionSchedule(totalMs, steps) }
    var stepIndex by remember { mutableIntStateOf(0) }

    val targetBg = remember(stepIndex) {
        Color(SELECTION_PALETTE[stepIndex % SELECTION_PALETTE.size].argb)
    }
    val background by animateColorAsState(
        targetValue = targetBg,
        animationSpec = tween(durationMillis = 260),
        label = "wheelBackground",
    )

    LaunchedEffect(poolHanzis, totalMs, steps) {
        schedule.forEachIndexed { i, dwell ->
            stepIndex = i
            onTick()
            delay(dwell)
        }
        onFinish()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(background)
            .padding(Dimens.lg),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = stepIndex,
            transitionSpec = { fadeIn(tween(110)) togetherWith fadeOut(tween(110)) },
            label = "wheelWord",
        ) { index ->
            Text(
                text = poolHanzis.getOrElse(index % poolHanzis.size) { "…" },
                style = HanziLarge,
                color = Color(HANZI_WHITE_ARGB),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

