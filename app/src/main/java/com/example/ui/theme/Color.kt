package com.example.ui.theme

import androidx.compose.ui.graphics.Color

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
