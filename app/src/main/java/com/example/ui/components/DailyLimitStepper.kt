package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.AppTheme
import com.example.util.plural

/**
 * One daily cap: a label, the current number, and a pair of buttons that move it in steps.
 *
 * ## Why a stepper and not a slider or a text field
 *
 * These settings decide what the app recommends the learner study, so they need to be *legible*
 * as much as adjustable — a learner should be able to see "10 new words a day" and understand
 * what they will be asked to do. A slider hides the actual number behind a thumb; a text field
 * invites a typo that the clamp then silently corrects, so the field would show `1000` while the
 * app stored `50`, which is worse than not offering it.
 *
 * A stepper shows the number, cannot be typed into, and cannot produce a value the app will
 * refuse. The buttons are disabled at the ends of the range rather than clamped at the ends,
 * because a button that looks live and does nothing is the defect this audit has been removing
 * from one screen at a time.
 *
 * ## Why the state lives above this
 *
 * [value] is a parameter and not remembered here, so the row always renders what was actually
 * persisted. A stepper holding its own copy would show the new number immediately and keep it
 * there if the write failed.
 *
 * Both buttons carry explicit `contentDescription`s naming the thing being changed and the
 * resulting value. "Increase" alone is announced out of context when two steppers sit on the
 * same screen, and this is the setting where being sure which number you are changing matters
 * most.
 */
@Composable
fun DailyLimitStepper(
    label: String,
    supporting: String,
    value: Int,
    step: Int = 1,
    minValue: Int,
    maxValue: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    valueColor: Color? = null
) {
    val colors = AppTheme.colors
    val ink = valueColor ?: colors.textPrimary
    val canDecrease = value > minValue
    val canIncrease = value < maxValue

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = colors.textPrimary
            )
            Text(
                text = supporting,
                fontSize = 11.sp,
                color = colors.textSecondary
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { onValueChange((value - step).coerceAtLeast(minValue)) },
                enabled = canDecrease,
                modifier = Modifier
                    .minimumTouchTarget()
                    .semantics {
                        contentDescription =
                            "Decrease $label to ${(value - step).coerceAtLeast(minValue)} " +
                            plural((value - step).coerceAtLeast(minValue), "word", "words")
                    }
            ) {
                Icon(
                    imageVector = Icons.Default.Remove,
                    contentDescription = null,
                    tint = if (canDecrease) colors.textPrimary else colors.textSecondary.copy(alpha = 0.4f),
                    modifier = Modifier.size(18.dp)
                )
            }

            Text(
                // The number is the setting, so it is given the weight and the room. It is also
                // padded to a minimum width so the row does not jitter as the digits change -
                // a value jumping sideways under a finger makes a stepper feel broken.
                text = "$value",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = ink,
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .semantics { contentDescription = "$value" }
            )

            IconButton(
                onClick = { onValueChange((value + step).coerceAtMost(maxValue)) },
                enabled = canIncrease,
                modifier = Modifier
                    .minimumTouchTarget()
                    .semantics {
                        contentDescription =
                            "Increase $label to ${(value + step).coerceAtMost(maxValue)} " +
                            plural((value + step).coerceAtMost(maxValue), "word", "words")
                    }
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    tint = if (canIncrease) colors.textPrimary else colors.textSecondary.copy(alpha = 0.4f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

