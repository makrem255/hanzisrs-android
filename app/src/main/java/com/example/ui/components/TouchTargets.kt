package com.example.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The minimum hit area for anything a finger has to find.
 *
 * 48dp is the Material accessibility minimum, and it is close to the contact patch of a
 * fingertip. It is a floor, not a target: a control whose *content* is smaller than this
 * still gets a 48dp hit area, while a control whose content is larger is not shrunk.
 *
 * This is a shared constant rather than a per-screen number because the mistake is easy to
 * make in a specific and silent way. `IconButton(Modifier.size(24.dp))` reads like it
 * makes the button small; what it actually does is set `maxWidth`/`maxHeight` to 24, and
 * the 48dp minimum that `IconButton` would otherwise apply is then coerced straight back
 * down to it. The icon gets smaller *and* the touch target gets smaller, with no compile
 * error and no warning. The fix is to size the icon and leave the container alone, which
 * is what [IconTarget] does in one place instead of six.
 */
val MinTouchTarget: Dp = 48.dp

/**
 * Grows this element's hit area to [MinTouchTarget] without changing its drawn size.
 *
 * A no-op on a control that is already at least 48dp in that dimension.
 */
fun Modifier.minimumTouchTarget(): Modifier = this.minimumInteractiveComponentSize()

/**
 * One option in a segmented row: a real touch height, and a selected state a screen
 * reader can hear.
 *
 * The three hand-rolled segmented rows in this app — the review card's pillars, the
 * stroke section's modes, the auth screen's Email/Phone switch — were all
 * `Box(Modifier.height(34.dp).clickable { ... })`. Two separate defects, one fix: 34dp is
 * under the floor, and a bare `clickable` presents three unrelated buttons to TalkBack
 * with no way to tell which is chosen. `selectable` carries the group role and the
 * selected state.
 *
 * The caller keeps its own colours and content; this owns only the target and the
 * semantics.
 */
@Composable
fun SegmentedOption(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .minimumTouchTarget()
            .selectable(selected = selected, onClick = onClick, role = Role.Tab),
        contentAlignment = Alignment.Center,
        content = content
    )
}

/**
 * A square icon button: small icon, full [MinTouchTarget] hit area.
 *
 * Replaces the `IconButton(Modifier.size(24.dp)) { Icon(Modifier.size(16.dp)) }` shape
 * that appeared on the recent-word cards, in the library detail sheet, and on the review
 * card's two audio controls.
 *
 * `contentDescription` goes on the [Icon] the caller passes in, not here — Compose has no
 * semantic slot on a bare `Box`, and attaching a description to the wrong node is how the
 * two library buttons ended up invisible to TalkBack in the first place.
 *
 * [enabled] exists because "this cannot be done right now" and "this can be done but will do
 * nothing" are different states, and only the second is a defect. A disabled control still
 * occupies its 48dp, still carries its label, and is still focusable in the sense that a
 * screen reader will describe it as disabled — so it stays legible as a thing that exists
 * rather than vanishing from the layout.
 */
@Composable
fun IconTarget(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .size(MinTouchTarget)
            // `Role.Button`, not the default. A bare `Box(Modifier.clickable)` is announced
            // by TalkBack as clickable *text* with no indication that activating it does
            // something, and the icon's own `contentDescription` — "Hear 学 pronounced" —
            // then reads as a label on a static element rather than as the name of a
            // control. The role is what makes it a control to a screen reader.
            .clickable(enabled = enabled, onClick = onClick, role = Role.Button),
        contentAlignment = Alignment.Center,
        content = content
    )
}

/**
 * A labelled switch whose *row* is the target.
 *
 * Settings had a 52x32dp `Switch` sitting in a full-width `Row` beside two lines of label
 * text that did nothing when tapped. The target was 48dp, but it was in the right-hand 40%
 * of the row — and a phone user aims at the words "Slow pronunciation". This puts the whole
 * row on the toggle and marks the row with `Role.Switch`, so a screen reader announces one
 * control rather than a label followed by an unlabelled switch.
 *
 * The switch itself is passed in with `onCheckedChange = null`. The row owns the
 * interaction; if the switch also handled the tap, one tap would toggle twice and land back
 * where it started — the kind of bug that gets filed as "the switch is broken".
 */
@Composable
fun SwitchRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    labelColor: Color = LocalContentColor.current,
    supportingColor: Color = labelColor,
    control: @Composable () -> Unit
) {
    Row(
        modifier = modifier
            .minimumTouchTarget()
            .toggleable(
                value = checked,
                onValueChange = onCheckedChange,
                role = Role.Switch
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).minimumTouchTarget()) {
            Text(text = label, fontSize = 13.sp, color = labelColor)
            if (supporting != null) {
                Text(text = supporting, fontSize = 11.sp, color = supportingColor)
            }
        }
        control()
    }
}
