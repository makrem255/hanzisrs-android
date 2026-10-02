package com.example.ui.theme

import androidx.compose.ui.graphics.Color
import com.example.data.model.StorageValues

// ─────────────────────────────────────────────────────────────────────────────
// HanziSRS palette
//
// The surface ramp is a single midnight-navy family, taken from the product's
// visual reference, which is overwhelmingly deep navy (#001028 and neighbours).
// The steps below stay inside that hue family rather than drifting toward
// neutral grey, so every screen shares one atmosphere instead of looking like a
// set of unrelated panels.
//
// Accents are used to communicate function, never for decoration: blue means
// "interactive", cyan means "sound", violet means "AI", amber means "review
// outstanding", mint means "mastered / earned", red means "needs attention".
// A colour that carries no meaning does not belong on screen.
// ─────────────────────────────────────────────────────────────────────────────

// ── Surface ramp ────────────────────────────────────────────────────────────

/** The deepest step, painted behind everything. */
val DarkBg = Color(0xFF050B16)

/** Grouped sections sitting directly on the background. */
val DarkSurfaceContainer = Color(0xFF081324)

/** The standard card. */
val DarkSurfaceCard = Color(0xFF0D1B2F)

/** A raised card, a selected row, a text field. */
val DarkSurfaceElevated = Color(0xFF13253F)

/** The top of the ramp: chips, selected segments, small controls. */
val DarkSurfaceHighest = Color(0xFF1B3150)

// ── Accents ─────────────────────────────────────────────────────────────────

/** Primary accent. Electric blue: "this is interactive, this is now". */
val AccentPrimary = Color(0xFF2E7BF6)

/** Ink that sits on top of [AccentPrimary]. Near-white; the blue is mid-tone. */
val AccentPrimaryInk = Color(0xFFF4F8FF)

/** A dimmed blue for borders and tracks that should read as the accent. */
val AccentPrimaryDim = Color(0xFF1C4DA6)

/** Cyan: pronunciation and audio energy. */
val AccentCyan = Color(0xFF2BD4EE)
val AccentCyanContainer = Color(0xFF062A36)

/** Violet: the AI surface, kept in its own hue so the tutor never reads as chrome. */
val AccentViolet = Color(0xFF9B7BFF)
val AccentVioletContainer = Color(0xFF1E1745)

/** Amber: reviews outstanding, warnings, the "Hard" rating. */
val AccentAmber = Color(0xFFF5B942)
val AccentAmberContainer = Color(0xFF33240A)

/** Mint: success, mastery, streaks. */
val AccentMint = Color(0xFF2AD9A4)
val AccentMintContainer = Color(0xFF07301F)

/** Soft red: errors and the "Again" rating. Never decorative. */
val AccentRed = Color(0xFFFF7A86)
val AccentRedContainer = Color(0xFF3A1220)

// ── Text and lines ──────────────────────────────────────────────────────────

val TextLight = Color(0xFFEAF0FA)
val TextMuted = Color(0xFF8CA2C2)
val TextSubtle = Color(0xFFB8C8E0)
val TextFaint = Color(0xFF5C7396)
val OutlineBorder = Color(0xFF213551)
val OutlineSubtle = Color(0xFF16273E)

// ── Rice grid ───────────────────────────────────────────────────────────────
//
// Drawn behind a character in the stroke guide and the tracing canvas. Named for
// what the grid is, not for the theme it happens to sit on: the drawing code has
// no business knowing the grid is currently drawn dark.
val GridLineDark = Color(0xFF213551)
val GridCenterDark = Color(0xFF3F6BA8)
val GhostTextDark = Color(0x33B8C8E0)
val TianGridLine = GridLineDark
val TianGridCenter = GridCenterDark

// ── The four scheduling states ──────────────────────────────────────────────
//
// These carry meaning: a rating button, a badge dot, a bar segment and the legend
// beside it all have to agree, so the four are the four and they are not
// interchangeable with the accent colours.
val SrsAgainDark = Color(0xFFFF7A86)
val SrsAgainContainer = Color(0xFF3A1220)
val SrsHardDark = Color(0xFFF5B942)
val SrsHardContainer = Color(0xFF33240A)
val SrsGoodDark = Color(0xFF2AD9A4)
val SrsGoodContainer = Color(0xFF07301F)
val SrsEasyDark = Color(0xFF5B9DFF)
val SrsEasyContainer = Color(0xFF102A5C)

// ── Tone contours ───────────────────────────────────────────────────────────
//
// The names matter more than the colours. Mandarin tone 1 is high and level; the
// standard four names are used rather than "High Flat / Rising / Falling-Rising /
// Falling", which reads as though tone 1 falls.
val Tone1Color = Color(0xFFFF7A86) // Tone 1 — high level
val Tone2Color = Color(0xFFF5B942) // Tone 2 — rising
val Tone3Color = Color(0xFF2AD9A4) // Tone 3 — dipping
val Tone4Color = Color(0xFF5B9DFF) // Tone 4 — falling
val ToneNeutralColor = Color(0xFF8CA2C2) // Neutral, e.g. the particle "ma"

/**
 * The one mapping from a card's scheduling state to the colour that means it.
 *
 * The `when` below is over the enum and has no `else`. Adding a state is a compile
 * error here, which is the property the enum exists to provide. The unrecognised
 * case is handled at the boundary by [srsStateColorOrNull] returning `null` for a
 * value that is not a declared state, which the caller renders as [TextMuted] —
 * grey, "state not recognised", rather than a colour that asserts a scheduling
 * judgement the data does not support.
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
 * Returns `null` for a value that is not a declared state, so the caller has to
 * decide what an unknown state looks like rather than inheriting a default by
 * accident.
 */
fun srsStateColorOrNull(storedState: String?): Color? =
    StorageValues.CardState.fromStorage(storedState)?.let(::srsStateColor)
