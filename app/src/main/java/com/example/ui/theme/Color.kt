package com.example.ui.theme

import androidx.compose.ui.graphics.Color
import com.example.data.model.StorageValues

// Dark surfaces. A neutral ramp: Material 3's own surface roles are purple-tinted, and
// these greys are what the cards, containers and elevations in the app were drawn against.
val DarkBg = Color(0xFF1C1B1F)
val DarkSurfaceCard = Color(0xFF2B2930)
val DarkSurfaceContainer = Color(0xFF211F26)
val DarkSurfaceElevated = Color(0xFF36343B)

// Primary accent
val LilacPrimary = Color(0xFFD0BCFF)
val LilacPrimaryDark = Color(0xFF381E72)
val LilacPrimaryVariant = Color(0xFF4F378B)
val LilacSecondary = Color(0xFFCCC2DC)
val LilacSecondaryContainer = Color(0xFF4A4458)
val LilacTertiary = Color(0xFFEFB8C8)

// Text and outlines
val TextLight = Color(0xFFE6E1E5)
val TextMuted = Color(0xFF938F99)
val TextSubtle = Color(0xFFCAC4D0)
val OutlineBorder = Color(0xFF49454F)

// Rice grid, drawn behind a character in the stroke guide and the tracing canvas.
val GridLineDark = Color(0xFF49454F)
val GridCenterDark = Color(0xFF6750A4)
val GhostTextDark = Color(0x33CAC4D0)
// Named for what the grid is, not for the theme it happens to sit on. The drawing code
// imports these; it has no business knowing that the grid is currently drawn dark.
val TianGridLine = GridLineDark
val TianGridCenter = GridCenterDark

// The four scheduling states. These carry meaning: a rating button, a badge dot, a bar
// segment and the legend beside it all have to agree, so the four are the four and they
// are not interchangeable with the accent colours.
val SrsAgainDark = Color(0xFFF2B8B5)
val SrsAgainContainer = Color(0xFF601410)
val SrsHardDark = Color(0xFFFFB786)
val SrsHardContainer = Color(0xFF4F2500)
val SrsGoodDark = Color(0xFFA6D3A0)
val SrsGoodContainer = Color(0xFF0D3B18)
val SrsEasyDark = Color(0xFFD0BCFF)
val SrsEasyContainer = Color(0xFF381E72)

// Tone contours.
//
// The names matter more than the colours here. These were labelled "High Flat", "Rising",
// "Falling-Rising" and "Falling", which reads as though tone 1 is a falling tone; Mandarin
// tone 1 is high and level. A learner shown "High Flat" next to a flat contour has been
// given a term that describes something else. The names below are the standard four.
val Tone1Color = Color(0xFFF2B8B5) // Tone 1 — high level
val Tone2Color = Color(0xFFFFB786) // Tone 2 — rising
val Tone3Color = Color(0xFFA6D3A0) // Tone 3 — dipping
val Tone4Color = Color(0xFFB5C4FF) // Tone 4 — falling
val ToneNeutralColor = Color(0xFF938F99) // Neutral, e.g. the particle "ma"

/**
 * The one mapping from a card's scheduling state to the colour that means it.
 *
 * This existed twice. The library screen compared a raw `String` with an `else` branch, and the
 * dashboard compared the parsed enum with no `else` — so the two answers could not both be
 * exhaustive, and only the dashboard's would fail to compile when a state was added. The copy
 * the compiler does *not* check is the dangerous one: a fifth state would have rendered as
 * [SrsAgainDark], which is the colour that means "you failed this word again", on every row in
 * the library. It would look like data, not like a missing branch.
 *
 * So the `when` below is over the enum and has no `else`. Adding a state is now a compile error
 * here, which is the property the enum exists to provide. The unrecognised case is handled at
 * the boundary by [srsStateColor] returning `null` for a value that is not a declared state,
 * which the caller renders as [TextMuted] — grey, "state not recognised", rather than a colour
 * that asserts a scheduling judgement the data does not support.
 *
 * That is the same policy `StorageValues.fromStorage` states for itself: an unrecognised stored
 * value is reported, not disguised as a valid one.
 */
fun srsStateColor(state: StorageValues.CardState): Color = when (state) {
    StorageValues.CardState.NEW -> SrsAgainDark
    StorageValues.CardState.LEARNING -> SrsHardDark
    StorageValues.CardState.REVIEW -> SrsGoodDark
    StorageValues.CardState.MASTERED -> SrsEasyDark
}

/**
 * As [srsStateColor], for a state still held as its stored `String`.
 *
 * Returns `null` for a value that is not a declared state, so the caller has to decide what an
 * unknown state looks like rather than inheriting a default by accident.
 */
fun srsStateColorOrNull(storedState: String?): Color? =
    StorageValues.CardState.fromStorage(storedState)?.let(::srsStateColor)
