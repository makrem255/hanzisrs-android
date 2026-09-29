package com.example.audio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.components.IconTarget
import com.example.ui.theme.LilacPrimary
import com.example.ui.theme.SrsAgainDark
import com.example.ui.theme.TextMuted

/**
 * The one audio control in the app.
 *
 * Replaces the seven call sites that each held their own `IconButton` and fired a bare
 * `speak(hanzi)` at a helper which reported success by not failing. Everything those buttons
 * got wrong is a property of *not having a control that owns the state*:
 *
 *  - **No association with a word.** The deck lets a learner swipe mid-utterance, and with no
 *    `sourceId` there was nothing to stop a card that has already been dismissed from showing
 *    a playing indicator for audio that is still running. [PronunciationState] carries the id,
 *    and this checks it before drawing anything, so a button whose state belongs to another
 *    word renders as idle rather than lying about itself.
 *  - **No loading state.** The first tap after launch happened while the engine was still
 *    initialising. It was discarded, silently, which on a phone is indistinguishable from a
 *    mis-tap.
 *  - **No error state.** A device with no Mandarin voice produced silence, with no state to
 *    render and therefore nothing to say — so the learner could not distinguish a broken app
 *    from a phone missing a language pack, and those need opposite advice.
 *  - **No replay.** [PronunciationService.replay] exists; nothing exposed it.
 *
 * ## Why a device-level failure disables the button *and* explains itself
 *
 * A disabled control with no explanation is the same defect as a control that does nothing:
 * the learner is left deciding whether to report a bug. [PronunciationFailure.EngineUnavailable]
 * and [MandarinUnavailable] both render a caption, because the next move is a trip to system
 * settings and they need to know that.
 *
 * [PronunciationFailure.NothingToSay] is the exception and deliberately captions nothing. The
 * field beside the button is visibly empty; a message would be the app explaining the screen
 * to the learner, which is the kind of noise that teaches people to ignore captions.
 *
 * @param request what to pronounce and — through [PronunciationRequest.sourceId] — which word
 *   any resulting state belongs to.
 * @param contentDescription the name of the control, for screen readers. The icon inside is
 *   `null`-described so the button is announced once, as a control, rather than as a button
 *   with a redundant label attached to a decorative glyph.
 */
@Composable
fun PronunciationButton(
    service: PronunciationService,
    request: PronunciationRequest,
    contentDescription: String,
    modifier: Modifier = Modifier,
    variant: PronunciationButtonVariant = PronunciationButtonVariant.Icon,
    testTag: String? = null
) {
    val state by service.state.collectAsStateWithLifecycle()
    val availability by service.availability.collectAsStateWithLifecycle()

    // The state is about *this* button's word only if the ids agree. A state belonging to
    // another word — the previous card, the previous row — must render as idle here rather
    // than borrowing another word's spinner.
    val isMine = when (val current = state) {
        is PronunciationState.Loading -> current.sourceId == request.sourceId
        is PronunciationState.Playing -> current.sourceId == request.sourceId
        is PronunciationState.Failed -> current.sourceId == request.sourceId
        else -> false
    }

    // A device-level failure disables every button at once, and is reported by each of them.
    // That is a deliberate duplication: the message is short, and the alternative — a single
    // app-level banner — would mean the learner meets a silent speaker icon with no
    // explanation anywhere near the thing they tapped.
    val deviceFailure = (availability as? ProviderAvailability.Failed)?.failure

    val wordFailure = if (isMine) (state as? PronunciationState.Failed)?.failure else null
    val failure = wordFailure ?: deviceFailure

    val isLoading = isMine && state is PronunciationState.Loading
    val isPlaying = isMine && state is PronunciationState.Playing
    val nothingToSay = failure is PronunciationFailure.NothingToSay

    // A permanent device fault disables the control; an empty field disables it too, but
    // without a caption. A transient playback error leaves it enabled, because retrying is
    // exactly what the learner would do.
    val permanentlyDisabled = deviceFailure != null || nothingToSay
    val enabled = request.isSpeakable && !permanentlyDisabled

    // The icon reports the state; the caption reports the reason. Only a device-level fault
    // recolours the icon, because it is the one failure that will not resolve itself and so
    // is the one worth recognising at a glance instead of reading. A transient playback error
    // leaves the icon alone: a red button that means "try again" is a different message from
    // a red button that means "this will never work".
    val tint: Color = if (deviceFailure != null) SrsAgainDark else LilacPrimary
    val icon = if (deviceFailure != null) Icons.Default.ErrorOutline else Icons.Default.VolumeUp

    val caption = when {
        nothingToSay -> null
        failure != null -> failure.message
        else -> null
    }

    val tagged = if (testTag != null) modifier.testTag(testTag) else modifier

    Column(
        modifier = tagged,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        // The description goes on the *clickable*, not on the glyph. Two reasons, and the
        // second is the one that bites: the glyph is swapped for a spinner while loading, so a
        // description attached to it would make the control anonymous to a screen reader for
        // exactly as long as the audio is loading — which is the moment a learner is most
        // likely to be told what is happening. Attaching it to the clickable also merges into
        // one node, so the button is announced once, as a control, rather than as a button
        // with a redundant label hanging off a decorative glyph.
        val labelled = Modifier.semantics { this.contentDescription = contentDescription }

        if (variant == PronunciationButtonVariant.Tonal) {
            FilledTonalIconButton(
                onClick = { service.play(request) },
                enabled = enabled,
                modifier = labelled.size(48.dp)
            ) {
                AudioGlyph(icon, tint, isLoading)
            }
        } else {
            IconTarget(
                onClick = { service.play(request) },
                enabled = enabled,
                modifier = labelled
            ) {
                AudioGlyph(icon, tint, isLoading)
            }
        }

        if (caption != null) {
            // `labelSmall` rather than a raw `fontSize`: this is the one caption in the app
            // and it should move with the type scale like everything else.
            Text(
                text = caption,
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
                modifier = Modifier
                    // Wide enough for the longest message ("This device has no Mandarin
                    // voice installed…") without pushing a card's layout around, and no
                    // wider, so a card row does not reflow when audio fails.
                    .widthIn(max = 220.dp)
            )
        }
    }
}

@Composable
private fun AudioGlyph(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    isLoading: Boolean
) {
    if (isLoading) {
        // A spinner rather than a static icon: the request has been accepted and is not yet
        // audible, and showing the ordinary "play" glyph through that gap is what let a tap
        // look like it had done nothing.
        CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            strokeWidth = 2.dp,
            color = tint
        )
    } else {
        // Null: the control is named by the semantics on its clickable, not by the glyph.
        Icon(icon, contentDescription = null, tint = tint)
    }
}

/**
 * How prominent the control is.
 *
 * The deck and the library detail sheet put audio on a card already covered in chrome, and
 * want it to recede. The add-word preview is the one place a learner goes *deliberately* to
 * hear the character they just typed, and gets the more prominent control.
 *
 * Named in full rather than `Variant`, because a bare `Variant` exported from
 * `com.example.audio` is a name every future file in that package would have to be careful
 * about.
 */
enum class PronunciationButtonVariant { Icon, Tonal }
