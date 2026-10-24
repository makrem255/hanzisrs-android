# Random Review — Visual Design Specification

**Status:** D1 — Design specification (documentation only, no code changes)
**Applies to:** Random Review feature (Practice) in the HanziSRS Android app
**Date:** 2026-10-23
**Baseline commit:** `904162460c670e51ea2b8e7013f3fcd30694d4df`

This document defines the shared visual contract for all future Random Review UI tasks (D2–D6). Every implementation task must reference this specification. Deviations require explicit approval.

---

## A. Design principles

1. **One focal point per state.**
   The entry screen's focal point is the START button. The selection state's focal point is the wheel. The word-practice state's focal point is the Hanzi character. Only one element should dominate the visual hierarchy at any time.
   *Example:* In the word-practice state, the Hanzi sits on a circular backdrop and uses `HanziHero` (88sp). The pinyin and meaning are smaller and muted. The microphone is prominent but secondary.

2. **Color communicates state, not decoration.**
   Every color must answer "what state is this?" or "what should I do next?" Blue means "action available." Green means "correct." Red means "wrong." Purple means "informational/level." Orange means "pronunciation/reading." Grey means "secondary/inactive."
   *Example:* The WordCard border is `primaryAction` (blue) when unanswered and `success` (green) when revealed. The state pill changes from "SAY IT OUT LOUD" (blue) to "ANSWER" (green).

3. **The arcade moment is contained in a card.**
   The dark selection wheel sits inside a large, visually distinctive card. The app's normal background remains visible around the card, so the wheel reads as a contained "arcade portal" rather than a full-screen takeover. The transition into and out of the wheel state should be smooth and intentional.
   *Example:* The wheel sits inside a card on the app's normal background. The card's dark interior reads as a "portal" into the arcade. The frame border and selection pointer use theme-aware tokens. The surrounding app UI remains visible and anchored.

4. **Calm during practice, energetic during selection.**
   The entry screen and word-practice state should be calm and focused (neutral surfaces, restrained accents). The selection wheel is the one moment of energy (dark background, animated deceleration, tick sounds). Do not spread arcade energy across the whole screen.
   *Example:* The WordCard uses `card` (white/dark) background with a subtle accent stripe. The wheel uses dark `WheelColor` backgrounds with white hanzi. These are visually distinct moments.

5. **Theme consistency over theme identity.**
   Every component must work in both light and dark themes. Semantic tokens (`AppTheme.colors.*`) are used throughout. The only theme-independent element is the wheel palette (intentional arcade aesthetic — dark on both themes).
   *Example:* The mic button idle state uses `primaryActionContainer` (light blue in light theme, dark blue in dark theme) with `primaryAction` icon. Both pass contrast requirements.

6. **Generous touch targets, compact information.**
   Interactive elements must meet the 48dp minimum touch target. Information density should be high (no excessive empty space) but readable (minimum 11sp text).
   *Example:* The mic button core is 96dp. The START button is full-width. The attempt counter is 11sp label text.

7. **Honest feedback.**
   The app never claims to grade pronunciation (no scores). Feedback states are: "Recognised" (green, with caveat about tones), "Different" (neutral, with retry count), "Nothing heard" (no attempt counted). This honesty is a design principle, not just a technical constraint.
   *Example:* The `duelStatusText` always includes "Recognition confirms the words, not your tones." when recognised.

8. **No decorative-only elements.**
   Every visual element must communicate state, hierarchy, or interaction. Accent stripes, backdrops, and animations must have a semantic purpose. If an element doesn't communicate, remove it.
   *Example:* The WordCard accent stripe changes color (primaryAction → success) to signal the revealed state. The circular backdrop behind the hanzi gives it a "stage" but could be removed if it doesn't improve readability.

---

## B. Color semantics

All colors are sourced from `AppTheme.colors` (defined in `Mono.kt`). No new `Color(0x...)` constants should be introduced in Random Review UI except for the wheel palette (see below).

### Token mapping table

| Purpose | Token | Light value | Dark value | Usage in Random Review |
|---|---|---|---|---|
| Screen background | `background` | `#FFFFFF` | `#000000` | Scaffold, TopAppBar, entry screen |
| Card surface | `card` | `#FFFFFF` | `#0A0A0A` | WordCard, empty-state card, MicRationaleCard, dialogs |
| Elevated surface | `elevated` | `#F0F0F0` | `#161616` | MetaChip background (on card), mic rationale card |
| Primary text | `textPrimary` | `#111111` | `#FFFFFF` | Hanzi, titles, primary labels, status text (Different/Failed) |
| Secondary text | `textSecondary` | `#6B6B6B` | `#B3B3B3` | Subtitles, hints, attempt counter, example English |
| Border | `border` | `#E5E5E5` | `#262626` | Card borders (default), wheel frame (theme-aware) |
| Border subtle | `borderSubtle` | `#EFEFEF` | `#1A1A1A` | CardRule, mic button border (pre-C2.7) |
| Primary action | `primaryAction` | `#1D6FD8` | `#60A5FA` | State pill (unrevealed), mic icon (idle), accent stripe (unrevealed), wheel frame border |
| Primary action container | `primaryActionContainer` | `#DCEBFF` | `#1E3A5F` | Mic button idle background |
| On primary action | `onPrimaryAction` | `#FFFFFF` | `#0B1220` | Mic icon when listening |
| Success | `success` | `#15803D` | `#4ADE80` | State pill (revealed), hanzi color (correct), RecognisedBadge, accent stripe (revealed) |
| Error | `error` | `#DC2626` | `#F87171` | Hanzi color (wrong), exit dialog "Yes, exit" |
| Vocabulary | `vocabulary` | `#B4530E` | `#FB923C` | Pinyin text in AnswerLine |
| Learning focus | `learningFocus` | `#6D28D9` | `#A78BFA` | HSK badge, help dialog icon |

### Wheel palette (theme-independent)
| Element | Value | Justification |
|---|---|---|
| Wheel backgrounds | `SELECTION_PALETTE` (15 ARGB values, `#101010`–`#3C3C3C` range with semantic tints) | The wheel sits inside a card on the app's normal background. Its dark interior is an intentional arcade aesthetic -- a "portal" into the practice moment. Dark backgrounds provide contrast for white hanzi (≥3.0 ratio, verified by `RandomArcadeTest`). Theme-independent by design. |
| Wheel hanzi text | `HANZI_WHITE_ARGB` (`#FFFFFFFF`) | White on dark = maximum contrast. Consistent with arcade aesthetic. |
| Wheel frame background | `textPrimary.copy(alpha = 0.06f)` | Theme-aware: subtle highlight in both themes. |
| Wheel frame border | `textPrimary.copy(alpha = 0.25f–0.55f)` (animated) | Theme-aware: visible on the dark wheel background in both themes. |

### Color rules
1. **No hard-coded `Color(0x...)` in UI composables** except `SELECTION_PALETTE` (which is a compile-time constant in `RandomArcade.kt`).
2. **Alpha variants use `.copy(alpha = ...)`** on existing tokens, never new hex values.
3. **The wheel palette is the only theme-independent color.** Justified: it's an immersive arcade moment. All other colors are theme-aware.
4. **State colors must not be the only signal.** Text labels accompany color changes (e.g., "SAY IT OUT LOUD" vs "ANSWER", attempt counter).
5. **New tokens:** If a color is needed that doesn't exist in `AppColors`, it must be proposed as an addition to `Mono.kt` (app-wide) or as a `copy(alpha)` variant of an existing token. Do not create local `val` colors in Random Review files.

### Missing tokens (proposals)
| Proposed token | Purpose | Current workaround | Approval needed |
|---|---|---|---|
| `wheelFrame` | Theme-aware wheel frame border color | `textPrimary.copy(alpha = 0.35f)` | Yes — should this be a named token? |
| `hanziBackdrop` | Circular backdrop behind hanzi | `primaryAction.copy(alpha = 0.08f)` | Yes — should this be a named token? |

---

## C. Typography and Chinese-character hierarchy

All typography is defined in `Type.kt`. Random Review uses these existing styles:

### Hierarchy table

| Element | Style | Size | Weight | Color | Notes |
|---|---|---|---|---|---|
| Screen title ("Random Review") | `headlineLarge` | 28sp | SemiBold | `textPrimary` | Centered. One accent underline (4dp, `primaryAction`). |
| Subtitle ("A surprise word, out loud...") | `bodyMedium` | 14sp | Normal | `textSecondary` | Centered, max 2 lines. |
| VocabStrip hanzi | 30sp (inline) | 30sp | SemiBold | `textPrimary` at 85% alpha | Marquee animation. Consider using `HanziSmall` (24sp) for consistency. |
| Hero Hanzi (WordCard) | `HanziHero` | 88sp | Bold | `hanziColor` (animated) | Centered on 120dp circular backdrop. |
| State pill text ("SAY IT OUT LOUD" / "ANSWER") | `labelSmall` | 11sp | Medium | `primaryAction` / `success` | Pill-shaped Surface. |
| HSK badge text | `labelSmall` | 11sp | Medium | `learningFocus` | Pill-shaped Surface. |
| Pinyin (AnswerLine) | `PinyinLarge` | 22sp | Medium | `vocabulary` | Orange. Letter-spaced. |
| Meaning (AnswerLine) | `titleMedium` | 16sp | SemiBold | `textPrimary` | Default AnswerLine style. |
| Example (AnswerLine) | `bodyMedium` | 14sp | Normal | `textSecondary` | Multi-line with English translation. |
| Status text (duel) | `bodyMedium` | 14sp | Normal | `duelStatusColor()` | Centered, fills width. |
| Attempt counter | `labelSmall` | 11sp | Medium | `textSecondary` | Centered. |
| "No example" fallback | `bodySmall` | 13sp | Normal | `textSecondary` | Italic not used (keep simple). |
| Help dialog title | `titleLarge` | 19sp | SemiBold | `textPrimary` | Standard dialog title. |
| Help dialog body | `bodyMedium` | 14sp | Normal | `textSecondary` | Multi-paragraph. |
| TopAppBar title | `titleLarge` | 19sp | SemiBold | `textPrimary` | "Random Review". |
| TopAppBar subtitle | `labelSmall` | 11sp | Medium | `textSecondary` | "Word N · practice only, nothing scheduled". |
| Exit dialog title | `titleLarge` | 19sp | SemiBold | `textPrimary` | "Exit Random Review?" |
| Mic button icon | 36dp (icon size) | — | — | `primaryAction` / `onPrimaryAction` | Inside 96dp circle. |
| Recognised badge | 56dp (IconBadge size) | — | — | `success` on `success.copy(alpha = 0.18f)` | CheckCircle icon. |

### Chinese character prominence rules
1. **Hanzi is always the largest text element** on the word-practice screen. `HanziHero` (88sp) is 4× the size of the next largest text (`PinyinLarge` at 22sp).
2. **Hanzi sits on a circular backdrop** (120dp, 8% alpha of the state color) to give it a "stage."
3. **Hanzi color changes with state:** `textPrimary` (idle) → `success` (correct) → `error` (wrong) → `textPrimary` (revealed). Animated via `animateColorAsState(tween(180))`.
4. **Minimum Hanzi size:** At 200% font scale, `HanziHero` becomes 176sp. The circular backdrop (120dp) may clip. **Proposal:** Use `HanziHero` but constrain the backdrop with `Modifier.sizeIn(maxWidth = 120.dp, maxHeight = 120.dp)` and let the text overflow gracefully (overflow = visible, not clip).

### Typography proposals (need approval)
| Proposed change | Current | Proposed | Justification |
|---|---|---|---|
| VocabStrip hanzi size | 30sp (inline) | `HanziSmall` (24sp) | Consistency with design system. 30sp is an outlier. |
| Attempt counter | `labelSmall` (11sp) | `labelSmall` (11sp) | Keep as-is (11sp floor). |

---

## D. Spacing, sizing, and shapes

### Existing tokens (from Dimens.kt)
| Token | Value | Usage in Random Review |
|---|---|---|
| `xxs` | 2dp | AnswerLine label-to-value gap |
| `xs` | 4dp | Title-to-subtitle gap, accent underline height |
| `sm` | 8dp | Mic button bottom margin, inline notice spacing |
| `md` | 12dp | Card internal spacers, padding adjustments |
| `lg` | 16dp | Major section spacers (header→hanzi, hanzi→reveal) |
| `xl` | 20dp | Card vertical padding, screen vertical padding |
| `xxl` | 24dp | (reduced from xxl to xl in C2.8-C.2) |
| `screenH` | 20dp | Horizontal screen gutter |
| `contentBottom` | 32dp | Bottom padding for scrollable content |

### Shape conventions
| Element | Shape | Radius | Source |
|---|---|---|---|
| WordCard | `RoundedCornerShape` | 20dp (AppCard default) | `Surfaces.kt:AppCard` |
| State pill / HSK badge | `RoundedCornerShape(50)` | Pill | `RandomReviewScreen.kt` |
| MetaChip | `RoundedCornerShape(50)` | Pill | `RandomReviewComponents.kt` |
| Mic button orb | `CircleShape` | — | `RandomReviewComponents.kt` |
| Wheel frame | `RoundedCornerShape(24.dp)` | 24dp | `RandomReviewEntry.kt` |
| Accent stripe | `RoundedCornerShape(2.dp)` | 2dp | `RandomReviewScreen.kt` |
| Accent underline | `RoundedCornerShape(2.dp)` | 2dp | `RandomReviewEntry.kt` |
| Dialogs | `RoundedCornerShape(28.dp)` (Material default) | 28dp | `AlertDialog` default |
| Buttons | `RoundedCornerShape(16.dp)` | 16dp | `PrimaryButton`/`SecondaryButton` |

### Sizing rules
| Element | Size | Token/Value | Notes |
|---|---|---|---|
| Mic button orb | 148dp container, 96dp core | `MIC_ORB_SIZE`, `MIC_CORE_SIZE` | Exceeds 48dp touch target minimum |
| Wheel frame | 72% width × 160dp height | `Modifier.fillMaxWidth(0.72f).height(160.dp)` | Consider making height responsive: `min(160.dp, 40% of screen height)` |
| Hanzi backdrop circle | 120dp | `Modifier.size(120.dp)` | May clip at 200% font scale; needs `sizeIn(max = 120.dp)` |
| RecognisedBadge | 56dp | `size = 56.dp` | IconBadge with CircleShape |
| Icon in mic button | 36dp | `Modifier.size(36.dp)` | Inside 96dp core |
| TopAppBar height | Default (64dp) | Material default | Standard |

### Spacing principles
1. **Screen edge padding:** `Dimens.screenH` (20dp) horizontal, `Dimens.lg` (16dp) vertical (inside Scaffold padding).
2. **Section spacing:** `Dimens.lg` (16dp) between major sections (header→hanzi, hanzi→reveal).
3. **Content spacing:** `Dimens.md` (12dp) between related items (reveal items, status text).
4. **Card internal padding:** `Dimens.xl` (20dp) horizontal, `Dimens.xl` (20dp) vertical (reduced from xxl in C2.8-C.2).
5. **Touch target minimum:** 48dp (`Dimens.touchTarget`).
6. **No excessive empty space:** Avoid `Spacer(Modifier.height(Dimens.xxl))` (24dp) or larger between content sections. Use `Dimens.lg` (16dp) as the maximum section spacer.

### Spacing proposals (need approval)
| Proposed change | Current | Proposed | Justification |
|---|---|---|---|
| Screen vertical padding | `Dimens.lg` (16dp) | `Dimens.lg` (16dp) | Keep as-is. |
| Card vertical padding | `Dimens.xl` (20dp) | `Dimens.xl` (20dp) | Already reduced from xxl. Keep. |
| Header-to-hanzi spacer | `Dimens.lg` (16dp) | `Dimens.lg` (16dp) | Already reduced from xl. Keep. |
| VocabStrip height | Implicit (text size + padding) | Explicit `height(48.dp)` with vertical centering | Prevents layout shift during animation. |

---

## E. Component composition

### 1. Entry screen (RandomReviewEntry)
**Hierarchy:**
```
Column (centered, vertical scroll)
├── Text "Random Review" (headlineLarge, textPrimary)
├── Spacer (xs)
├── Text subtitle (bodyMedium, textSecondary)
├── Spacer (xs)
├── Box (accent underline, 64×4dp, primaryAction)
├── Spacer (xl)
├── VocabStrip (marquee, textPrimary @85% alpha)
├── Spacer (xl)
├── PrimaryButton "START" (full-width, primaryAction)
├── Spacer (md)
├── IconButton help (48dp, textSecondary)
├── Spacer (md)
├── [if not loaded] Text "Loading your words…"
├── [if < 5 words] AppCard (empty state) + "Add words" button
└── [if >= 5 words] Text "N words in the wheel · recent 5 never repeat"
```

**Visual dominance:** START button is the focal point (full-width, primaryAction fill). Title is secondary. VocabStrip is decorative (shows the learner's own words).

**What should be removed if no functional value:** The `MetaChip`-style count label at the bottom could be simplified to a single `Text` (it's already a Text, not a MetaChip). The help IconButton could be a text link "How it works" for better discoverability.

**Issues:**
- No visual container for the main action area. The START button floats in the Column.
- The empty-state card uses `card` on `background` (low contrast in light theme).

### 2. Vocabulary preview strip (VocabStrip)
**Hierarchy:**
```
Box (clipToBounds, vertical padding sm)
└── Row (infinite horizontal scroll, graphicsLayer translationX)
    └── (hanzis + hanzis).forEach { Text(hanzi, 30sp, SemiBold, textPrimary @85%) }
```

**Visual dominance:** Decorative. Shows the learner's own words scrolling past.

**Issues:**
- No edge fade (text clips abruptly). **Confirmed missing** (C2.8-C.3 claimed it but didn't implement).
- Text size (30sp inline) is inconsistent with `HanziSmall` (24sp). **Proposal:** Use `HanziSmall`.
- No vertical centering constraint (height is implicit).

### 3. Primary start action and help action
**Hierarchy:**
```
PrimaryButton "START" (full-width, leading PlayArrow icon)
IconButton help (48dp, HelpOutline icon, textSecondary)
```

**Visual dominance:** START button is primary. Help is secondary (small icon).

**Issues:**
- Help is a small icon with no label. Consider a text link.
- START button has no visual "arcade" framing. Could add a subtle gradient or shadow.

### 4. Selection wheel (SelectionAnimation)

**Approved decision (D1.1):** The wheel sits inside a large, visually distinctive card. The app's normal background remains visible around the card. The wheel retains its dark arcade interior.

**Hierarchy:**
```
Box (fillMaxSize, background = app background, padding lg, centered)
+-- Card (large, visually distinctive, rounded corners, elevation)
    +-- Card surface (light: card/elevated; dark: card/elevated — theme-aware)
    +-- Selection pointer (fixed, above or beside the wheel, clearly distinct from rotating content)
    +-- Wheel container (dark interior, rounded, framed)
        +-- AnimatedContent (stepIndex -> Text hanzi, HanziLarge, white)
```

**Visual dominance:** The wheel card is the focal point. The hanzi inside is the focal point within the card. The selection pointer is a fixed visual anchor.

**Wheel card specification:**
- **Role:** A contained "arcade portal" — the one moment of energy on the screen. The card anchors the wheel in the app's visual context while the dark interior provides the arcade feel.
- **Padding:** `Dimens.lg` (16dp) inside the card, around the wheel frame.
- **Corner radius:** 24dp (consistent with `RoundedCornerShape(24.dp)` already used for the wheel frame).
- **Elevation:** 4dp in light theme (subtle shadow), 0dp in dark theme (avoid glow on OLED). Use `Surface` with `shadowElevation`.
- **Border:** `AppTheme.colors.border` (1dp) in light theme for definition; `AppTheme.colors.border` (1dp) in dark theme. No heavy outline.
- **Background:** `AppTheme.colors.card` (theme-aware). The card sits on `AppTheme.colors.background`.
- **Size:** The card should be large enough to dominate the selection state. Suggested: `fillMaxWidth(0.85f)` with `heightIn(min = 200.dp, max = 280.dp)`. The wheel frame inside is ~72% of screen width.
- **Selection pointer:** A fixed indicator (e.g., a small triangle or chevron) positioned at the top-center of the wheel, pointing downward. Uses `AppTheme.colors.primaryAction` (blue). Must be visually distinct from the rotating hanzi. The pointer does not move; only the wheel content moves beneath it.
- **Transition:** When entering the Selecting phase, the wheel card fades in (200ms) and scales from 0.95 to 1.0. When exiting (word confirmed), the card fades out (150ms) and the word card fades in.

**Issues:**
- No selection indicator (arrow/pointer) showing where the word lands. **Must be added in D3.**
- Frame border pulse (0.25 to 0.55) is barely perceptible. **Increase to 0.30 to 0.70 or replace with a static, clearly visible border.**
- Hanzi size (56sp) could be larger (72sp) to fill the frame.
- The wheel background is a flat color (no gradient or depth). **Consider a subtle radial gradient for depth.**
- The full-screen dark rectangle must be replaced with the card container. **This is the core of D3.**

### 5. Word card (WordCard)
**Hierarchy:**
```
AppCard (card bg, state-tinted border, xl padding)
├── Accent stripe (4dp, primaryAction/success)
├── Spacer (md)
├── Row: state pill (left) + HSK badge (right)
├── Spacer (lg)
├── Box (centered): circular backdrop (120dp) → Hanzi (HanziHero 88sp)
├── Spacer (lg)
├── RevealItem 0: AnswerLine PINYIN (PinyinLarge, vocabulary orange)
├── RevealItem 1: AnswerLine MEANING (titleMedium, textPrimary)
├── RevealItem 2: AnswerLine EXAMPLE (bodyMedium, textSecondary) or fallback text
└── [if !revealed] Text hint (bodySmall, textSecondary, centered)
```

**Visual dominance:** Hanzi on circular backdrop. State pill and HSK badge are secondary. Pinyin/meaning/example are revealed in sequence.

**Issues:**
- The circular backdrop (120dp) may clip the hanzi at large font scales.
- The accent stripe is a nice touch but could be more prominent (6dp instead of 4dp).
- No elevation/shadow on the card (relies on border for definition).
- The header row (state pill + HSK badge) could have better vertical alignment.

### 6. Microphone and recognition feedback
**Hierarchy:**
```
DuelControls
├── RecognisedBadge (if recognised)
├── Status text (bodyMedium, duelStatusColor)
├── Spacer (lg)
├── AnimatedMicButton (148dp container, 96dp core, CircleShape)
├── Spacer (md)
└── Attempt counter (labelSmall, textSecondary)
```

**Visual dominance:** Mic button is the primary action (large, centered, elevated). Status text is secondary (above). Attempt counter is tertiary (below).

**Issues:**
- The mic button has no visual distinction for "processing" state (looks same as idle).
- The listening ripple rings could be more prominent.
- The status text above the mic could be closer to the button.

### 7. Answer reveal
**Hierarchy:**
```
WordCard (revealed = true)
├── Accent stripe (success green)
├── Header: "ANSWER" pill (success) + HSK badge
├── Spacer (lg)
├── Hanzi (textPrimary, on circular backdrop with success tint)
├── Spacer (lg)
├── RevealItem 0: AnswerLine PINYIN (PinyinLarge, vocabulary)
├── RevealItem 1: AnswerLine MEANING (titleMedium, textPrimary)
├── RevealItem 2: AnswerLine EXAMPLE (bodyMedium, textSecondary)
└── (no hint text when revealed)
```

**Visual dominance:** Hanzi remains the focal point. Pinyin is the first revealed (orange, large). Meaning and example follow.

**Issues:**
- The reveal animation (320ms fade + slide + scale) is good but could be more dramatic.
- The "ANSWER" pill could be more prominent (larger, with an icon).

### 8. Help and exit dialogs
**Help dialog:** Standard `AlertDialog` with `IconBadge` (purple learningFocus), title, body, "Got it" button.
**Exit dialog:** Standard `AlertDialog` with title, body, "Yes, exit" (error color) + "Keep going" (primaryAction).

**Issues:**
- Both dialogs use `containerColor = AppTheme.colors.card`. Consistent. No issues.
- The help dialog body is a single `Text` (no structured formatting). Could use bullet points for the three sections.

### 9. Future timed-round and results screens (future scope)
**Not implemented.** These are planned for D7-D8. The design spec should define:
- Timer bar: horizontal progress bar at the top of the screen, green→yellow→red.
- Numeric countdown: always visible, large, tabular figures.
- Results screen: card-based layout with metrics (unique attempted, correct, incorrect, accuracy, unresolved).
- "Play again" and "Exit" buttons.

---

## F. Motion and feedback

### Motion table

| Animation | Purpose | Duration | Easing | Location | Should not run when |
|---|---|---|---|---|---|---|
| VocabStrip marquee | Show the learner's words scrolling past | 18s (infinite) | LinearEasing | `RandomReviewEntry:VocabStrip` | Empty vocabulary |
| Selection wheel deceleration | Communicate the random draw | 3.2s (full) / 1.5s (short) | Linear ramp (schedule-based) | `RandomReviewEntry:SelectionAnimation` | — |
| Wheel frame pulse | Draw attention to the selection slot | 1.6s (infinite, reverse) | LinearEasing | `RandomReviewEntry:SelectionAnimation` | During idle/entry |
| Wheel word transition | Show each candidate word | 110ms fade | tween(110) | `SelectionAnimation:AnimatedContent` | — |
| Mic idle pulse | Invite the first tap | 1.4s (infinite, reverse) | LinearEasing | `AnimatedMicButton` | When listening or disabled |
| Mic listening ripple | Show that the mic is active | 1.8s (infinite) | LinearEasing | `RippleRings` | When not listening |
| Mic scale on listen | Tactile feedback on tap | Spring (damping 0.6, stiffness 220) | Spring | `AnimatedMicButton` | — |
| Hanzi color on attempt | Show correct/wrong verdict | 180ms | tween(180) | `WordPhase:hanziColor` | — |
| Wrong-answer flash | Show wrong answer | 600ms | Delay then reset | `WordPhase` (LaunchedEffect) | When help is open |
| Correct-answer hold | Pause before next word | 500ms | Delay | `WordPhase` (LaunchedEffect) | — |
| WordCard entrance | Smooth word transition | 360ms (slide+scale+fade) | tween(360) with NavEase | `WordPhase:AnimatedContent` | Same word (no transition) |
| Answer reveal | Uncover answer progressively | 320ms per item, 70ms stagger | CubicBezierEasing(0.25,1,0.5,1) | `RevealItem` | — |
| RecognisedBadge | Celebrate correct answer | Spring (damping 0.45, stiffness 320) | Spring | `RecognisedBadge` | — |

### Motion rules
1. **Animations must correspond to real state changes.** No idle animations on screens where no interaction is expected (except the mic idle pulse, which invites interaction).
2. **Reduced motion:** The app has no global reduced-motion setting. **Proposal:** Add a `UiPreferencesStore.reducedMotion` boolean (default: follow system `ANIMATOR_DURATION_SCALE`). If enabled, disable infinite animations (mic pulse, frame pulse, VocabStrip marquee) and reduce transition durations to 0ms.
3. **No animation should delay user input.** All animations are non-blocking (no `delay` that prevents interaction, except the 500ms correct-answer hold which is intentional).
4. **The wheel animation must not be skippable.** The learner should see the wheel spin. No "skip" button.

---

## G. Light and dark themes

### Theme behavior table

| Component | Light theme | Dark theme | Notes |
|---|---|---|---|
| Screen background | `#FFFFFF` | `#000000` | Standard |
| TopAppBar | `background` (white/black) | Same | Blends with screen |
| WordCard | `card` (white) on `background` (white) | `card` (`#0A0A0A`) on `background` (`#000000`) | Card has border for definition (borderSubtle in light, border in dark) |
| WordCard accent stripe | `primaryAction` (`#1D6FD8`) / `success` (`#15803D`) | `primaryAction` (`#60A5FA`) / `success` (`#4ADE80`) | Theme-aware via AppTheme.colors |
| Hanzi backdrop circle | `primaryAction.copy(0.08f)` (light blue wash) | `primaryAction.copy(0.08f)` (dark blue wash) | Subtle in both |
| Hanzi text | `textPrimary` (`#111111`) | `textPrimary` (`#FFFFFF`) | High contrast |
| Pinyin | `vocabulary` (`#B4530E`) | `vocabulary` (`#FB923C`) | Orange, readable on both |
| Meaning | `textPrimary` | `textPrimary` | High contrast |
| Example | `textSecondary` | `textSecondary` | Muted |
| State pill (unrevealed) | `primaryAction` text on `primaryAction.copy(0.12f)` bg | Same tokens, dark values | Pill-shaped |
| State pill (revealed) | `success` text on `success.copy(0.14f)` bg | Same tokens, dark values | Pill-shaped |
| HSK badge | `learningFocus` text on `learningFocus.copy(0.14f)` bg | Same tokens, dark values | Pill-shaped |
| Mic button (idle) | `primaryActionContainer` (`#DCEBFF`) bg, `primaryAction` (`#1D6FD8`) icon | `primaryActionContainer` (`#1E3A5F`) bg, `primaryAction` (`#60A5FA`) icon | Elevated (6dp shadow in light, 0 in dark) |
| Mic button (listening) | `primaryAction` fill, `onPrimaryAction` icon | Same | No shadow |
| RecognisedBadge | `success` icon on `success.copy(0.18f)` bg | Same tokens, dark values | 56dp |
| Status text (correct) | `success` | `success` | Green |
| Status text (different/failed) | `textPrimary` | `textPrimary` | High contrast |
| Status text (idle) | `textSecondary` | `textSecondary` | Muted |
| Wheel (SelectionAnimation) | Dark `WheelColor` backgrounds (fixed) | Same (always dark) | Intentional arcade aesthetic |
| Wheel frame border | `textPrimary.copy(0.25f–0.55f)` | `textPrimary.copy(0.25f–0.55f)` | Theme-aware: white on dark wheel in both themes |
| Wheel frame background | `textPrimary.copy(0.06f)` | Same | Subtle highlight |
| Wheel hanzi | `HANZI_WHITE_ARGB` (`#FFFFFFFF`) | Same | Always white on dark |
| Entry title | `textPrimary` | `textPrimary` | High contrast |
| Accent underline | `primaryAction` | `primaryAction` | Blue |
| VocabStrip hanzi | `textPrimary @85%` | `textPrimary @85%` | Semi-transparent |
| Empty-state card | `card` on `background` | `card` on `background` | Low contrast in light (white on white) — border provides definition |
| Help dialog | `card` container, `textPrimary` title, `textSecondary` body | Same tokens, dark values | Standard AlertDialog |
| Exit dialog | `card` container, `error` for "Yes, exit" | Same tokens, dark values | Standard AlertDialog |

### Wheel card in light and dark themes (resolved)

**Approved decision (D1.1):** The wheel sits inside a card. The app's normal background remains visible around the card.

**Light theme:**
- App background: `background` (`#FFFFFF`).
- Wheel card: `card` (`#FFFFFF`) with `border` (`#E5E5E5`, 1dp) and 4dp shadow.
- Wheel interior: dark `WheelColor` backgrounds (unchanged — arcade aesthetic). The dark interior is now contained within the card, so it reads as a "portal" rather than a full-screen flash.
- Selection pointer: `primaryAction` (`#1D6FD8`) — visible on both the card surface and the dark wheel interior.
- Transition: card fades in (200ms) + scales from 0.95 to 1.0. Wheel interior fades in slightly later (100ms delay) for a layered entrance.

**Dark theme:**
- App background: `background` (`#000000`).
- Wheel card: `card` (`#0A0A0A`) with `border` (`#262626`, 1dp), 0dp shadow (avoid OLED glow).
- Wheel interior: dark `WheelColor` backgrounds (same as light — the arcade aesthetic is theme-independent).
- Selection pointer: `primaryAction` (`#60A5FA`).
- Transition: same as light theme. The dark-on-dark transition is naturally smoother.

**Key principle:** The card container adapts to the theme. The wheel interior does not (intentional arcade aesthetic). The selection pointer is theme-aware (`primaryAction` token).

### Dark-theme considerations
- In dark theme, the wheel's dark background is less jarring (dark on dark). The transition is smoother.
- The WordCard's `card` (`#0A0A0A`) on `background` (`#000000`) has very low contrast. The border (`border` = `#262626`) provides definition but is subtle. Consider increasing border opacity in dark theme.
- The mic button idle state (`primaryActionContainer` = `#1E3A5F` on `#000000`) has adequate contrast. The 6dp shadow is disabled in dark (0dp) to avoid glow artifacts on OLED.

---

## H. Responsive and accessible design

### Requirements for 360dp-wide phones
1. **Screen gutter:** `Dimens.screenH` (20dp) on each side → 320dp content width.
2. **Bottom nav:** 5 items at ~64dp each (320/5). Labels at 11sp. Tight but fits. Consider reducing label to 10sp if needed.
3. **WordCard:** `fillMaxWidth()` with `Dimens.xl` (20dp) horizontal padding → 280dp card width. Hanzi backdrop (120dp) fits with 80dp on each side.
4. **Mic button:** 148dp orb + 96dp core. Fits within 280dp card width with 66dp on each side.
5. **Wheel frame:** 72% of screen width = ~259dp on 360dp screen. 160dp height. Hanzi at 56sp fits with ~52dp vertical padding.
6. **VocabStrip:** Uses `fillMaxWidth()` with `clipToBounds()`. Text at 30sp (or 24sp if changed). No horizontal padding on the Row itself (items have trailing padding).

### Requirements for short screens (~640dp tall)
1. **Vertical scrolling:** The main `Column` uses `verticalScroll(rememberScrollState())`. Content scrolls if it exceeds screen height.
2. **Wheel is full-screen height.** `SelectionAnimation` uses `fillMaxSize()`. On a 640dp screen, the wheel takes the full height (minus TopAppBar ~64dp and padding). The 160dp frame is centered. Adequate.
3. **WordCard + controls may exceed screen height.** The card + mic + status + spacers could be ~500dp. On a 640dp screen (minus 64dp TopAppBar, 16dp padding = ~560dp available), this fits. On shorter screens (560dp), it scrolls.
4. **Bottom padding:** `Dimens.contentBottom` (32dp) ensures the last card clears the bottom nav bar.

### Requirements for large font scales (200%)
1. **Hanzi (`HanziHero` 88sp):** At 200% scale = 176sp. The circular backdrop (120dp) will clip. **Mitigation:** Use `Modifier.sizeIn(maxWidth = 120.dp, maxHeight = 120.dp)` on the backdrop and allow the text to overflow with `overflow = TextOverflow.Visible` (default). The text will extend beyond the circle, which is acceptable.
2. **Pinyin (`PinyinLarge` 22sp):** At 200% = 44sp. The AnswerLine uses `fillMaxWidth()`. Long pinyin strings may wrap. OK.
3. **Meaning (`titleMedium` 16sp):** At 200% = 32sp. Long meanings may take 3-4 lines. The card scrolls, so OK.
4. **Status text (`bodyMedium` 14sp):** At 200% = 28sp. The status text fills width and wraps. OK.
5. **Attempt counter (`labelSmall` 11sp):** At 200% = 22sp. Still readable. OK.
6. **Buttons:** `PrimaryButton` and `SecondaryButton` use fixed heights (`Dimens.buttonHeight = 54dp`). At 200% font scale, text may overflow the button. **Mitigation:** Use `Modifier.heightIn(min = 54.dp)` instead of fixed height, or add `contentPadding` to accommodate larger text.

### Screen-reader and accessibility requirements
1. **Mic button:** Must announce its current state (idle/listening/processing) via `contentDescription` or `semantics`. Currently always says "Say the word out loud." **Proposal:** Change to "Say the word out loud" (idle), "Listening" (listening), "Processing" (processing).
2. **Wheel frame:** Should announce "Selecting a word" when spinning. Currently no semantics.
3. **Word card:** The hanzi should have `contentDescription = "Chinese character: $hanzi"`. Currently `contentDescription = null` (decorative, since the text is visible). For screen readers, the visible text is automatically read, so this is OK.
4. **Recognised badge:** Has `contentDescription = "Recognised"`. OK.
5. **Status text:** Readable by screen readers (it's a `Text` composable). OK.
6. **Touch targets:** All interactive elements exceed 48dp. OK.
7. **Contrast:** All text colors are from `AppTheme.colors` which are contrast-tested (MonoContrastTest). Wheel hanzi (white on dark) passes ≥3.0. OK.

### Reduced-motion proposal (not implemented)
The app has no global reduced-motion setting. **Proposal:** Add `UiPreferencesStore.reducedMotion: Boolean` (default: follow system `Settings.Global.ANIMATOR_DURATION_SCALE`). If enabled:
- Disable infinite animations: mic idle pulse, wheel frame pulse, VocabStrip marquee, wheel ripple rings.
- Reduce transition durations to 0ms: word card entrance, answer reveal, hanzi color change.
- Keep the wheel spin (it's the core interaction) but reduce to 1 step (instant selection).

This requires:
- `UiPreferencesStore` change (new key).
- `MainViewModel` change (new StateFlow).
- All Random Review composables to check the setting.

**This is a proposal for a future task (D10), not implemented in D1.**

---

## I. Non-goals and protected behavior

### What visual redesign tasks must NOT change
1. **Word-selection algorithm** (`RandomSelection.kt:pickRandomWord`) — selection policy is correct and tested.
2. **No-repeat behavior** (`RANDOM_REVIEW_HISTORY_SIZE = 5`, `updateRecentHistory`) — working as designed.
3. **Speech-recognition semantics** (`SpeechRecognitionManager`, `matchesTarget`, `verdictFor`) — do not change matching rules, language, or error handling.
4. **Existing vocabulary and dictionary data** — do not modify `pilot.json` or `OfflineDictionary`.
5. **AI/offline fallback behavior** — do not change `GeminiAiService` or `GenerationResolve`.
6. **SRS scheduling and review writes** — Random Review must NOT call `submitRating`, `processReview`, or any scheduler. This is a hard invariant.
7. **Existing navigation outside approved changes** — only the Random Review route and bottom nav are in scope.
8. **Database schema and persistence** — no changes unless a future approved task (D9) covers them.
9. **`Mono.kt` tokens** — do not modify app-wide color tokens. Use existing tokens or propose new ones as additions.
10. **`Theme.kt`** — do not modify the theme resolution logic.
11. **`Dimens.kt`** — do not modify the spacing scale. Use existing tokens.
12. **`Type.kt`** — do not modify typography styles. Use existing styles.

### What visual redesign tasks MAY change
1. Layout structure (Column, Row, Box arrangements).
2. Spacing (which Dimens tokens are used where).
3. Surface treatments (colors, borders, elevation, shapes).
4. Animation parameters (durations, easing, repeat modes).
5. Component composition (adding/removing wrappers, changing hierarchy).
6. New composables within Random Review files.
7. New `@Composable` helper functions in the Random Review package.
8. Import additions.

### What requires separate approval
1. New color tokens in `Mono.kt`.
2. New dimension tokens in `Dimens.kt`.
3. New typography styles in `Type.kt`.
4. New dependencies in `build.gradle.kts`.
5. Changes to `MainViewModel` or any repository.
6. Changes to navigation routes or destinations.
7. Changes to `SpeechRecognitionManager` or audio code.
8. Database schema changes.

---

## J. Component hierarchy table

| Component | Parent | Visual dominance | Key tokens | Theme behavior |
|---|---|---|---|---|
| `RandomReviewScreen` | NavHost composable | Screen container | `background`, `textPrimary` | Follows theme |
| `TopAppBar` | Scaffold | Header (secondary) | `background`, `textPrimary`, `textSecondary` | Follows theme |
| `RandomReviewEntry` | Column (scrollable) | Entry container | `textPrimary`, `textSecondary`, `primaryAction` | Follows theme |
| `VocabStrip` | `RandomReviewEntry` | Decorative | `textPrimary @85%` | Follows theme |
| `PrimaryButton "START"` | `RandomReviewEntry` | **Primary action** | `primaryAction`, `onPrimaryAction` | Follows theme |
| `SelectionAnimation` | Wheel card (contained) | **Focal** (arcade moment) | `WheelColor` (fixed dark), `textPrimary` (frame), `primaryAction` (pointer) | Wheel interior always dark; card container is theme-aware |
| `WordCard` | `AnimatedContent` | **Primary content** | `card`, `border`, `primaryAction`/`success` (accent) | Follows theme |
| `WordCard` accent stripe | `WordCard` (first child) | Visual accent | `primaryAction` / `success` | Follows theme |
| Hanzi backdrop circle | `WordCard` (centered Box) | Focal point | `primaryAction`/`success` at 8% alpha | Follows theme |
| Hanzi text | Circular backdrop | **Hero** (88sp) | `hanziColor` (animated) | Follows theme |
| State pill | `WordCard` header | Secondary | `primaryAction`/`success` | Follows theme |
| HSK badge | `WordCard` header | Secondary | `learningFocus` | Follows theme |
| `AnswerLine` (Pinyin) | `WordCard` | Secondary | `vocabulary` (orange) | Follows theme |
| `AnswerLine` (Meaning) | `WordCard` | Secondary | `textPrimary` | Follows theme |
| `AnswerLine` (Example) | `WordCard` | Tertiary | `textSecondary` | Follows theme |
| `CardRule` | `WordCard` | Divider | `borderSubtle` | Follows theme |
| `MetaChip` | `WordCard` (not currently used) | Tertiary | `elevated`, `textSecondary` | Follows theme |
| `AnimatedMicButton` | `DuelControls` | **Primary action** | `primaryActionContainer`, `primaryAction`, `onPrimaryAction` | Follows theme |
| `RippleRings` | `AnimatedMicButton` | Visual feedback | `tint` (primaryAction) | Follows theme |
| `RecognisedBadge` | `DuelControls` | Feedback | `success` | Follows theme |
| Status text | `DuelControls` | Secondary | `success`/`textPrimary`/`textSecondary` | Follows theme |
| Attempt counter | `DuelControls` | Tertiary | `textSecondary` | Follows theme |
| `HelpControls` | `WordPhase` | Secondary area | `textSecondary`, `primaryAction` | Follows theme |
| `MicRationaleCard` | `WordPhase` | Overlay | `elevated`, `border`, `textSecondary` | Follows theme |
| `RandomReviewHelpDialog` | Dialog | Overlay | `card`, `learningFocus`, `textPrimary`, `textSecondary` | Follows theme |
| `RandomReviewExitDialog` | Dialog | Overlay | `card`, `error`, `primaryAction` | Follows theme |

---

## K. Motion/feedback table

(See section F above for the full motion table.)

### Reduced-motion proposal (for D10)
If `UiPreferencesStore.reducedMotion` is enabled:
- Disable: mic idle pulse, wheel frame pulse, VocabStrip marquee, wheel ripple rings
- Reduce to 0ms: word card entrance, answer reveal, hanzi color change, mic scale
- Keep: wheel spin (core interaction) but reduce to minimum steps (1 step = instant)
- Keep: correct-answer hold (500ms) — this is functional, not decorative

---

## L. Light/dark behavior

### Key design decisions
1. **The wheel is always dark.** This is an intentional "arcade portal" aesthetic. The transition from light UI to dark wheel should be smooth (fade-in, vignette). In dark theme, the transition is naturally smoother.
2. **The WordCard uses `card` on `background`.** In light theme, both are white (border provides definition). In dark theme, `card` (`#0A0A0A`) on `background` (`#000000`) is nearly identical (border provides definition). Consider increasing border opacity in dark theme.
3. **The mic button uses `primaryActionContainer`.** Light: light blue (`#DCEBFF`) on white. Dark: dark blue (`#1E3A5F`) on black. Both provide good contrast for the `primaryAction` icon.
4. **The accent stripe uses `primaryAction`/`success`.** These are theme-aware and provide consistent visual signaling.
5. **The circular backdrop uses `primaryAction`/`success` at 8% alpha.** Subtle in both themes. May be too subtle in light theme. Consider 10% if needed.

### Contrast verification (from MonoContrastTest)
All `AppTheme.colors` tokens are contrast-tested. The wheel hanzi (white on dark) passes ≥3.0 (verified by `RandomArcadeTest`). Other text uses tokens that pass 4.5:1.

---

## M. Responsive and accessibility requirements

### Testable via source/unit tests
- [ ] All interactive elements have `contentDescription` or visible text.
- [ ] Touch targets ≥ 48dp (`Dimens.touchTarget`).
- [ ] No `Color(0x...)` in UI composables except `SELECTION_PALETTE` (verified by grep).
- [ ] All colors from `AppTheme.colors` (verified by grep).
- [ ] `RandomArcadeTest` palette contrast ≥ 3.0 (existing test).
- [ ] `RandomSelectionTest` selection policy (existing test).
- [ ] `SpeechMatchTest` matching logic (existing test).

### Requires device/emulator verification
- [ ] Text at 200% font scale doesn't clip.
- [ ] Layout works on 360dp-wide screens.
- [ ] Wheel frame border is visible in light theme.
- [ ] MetaChip is distinguishable from card in both themes.
- [ ] Mic button contrast passes in dark theme.
- [ ] VocabStrip edge fade is visible (once implemented).
- [ ] Reduced-motion behavior (once implemented).
- [ ] Screen-reader announcements for mic state changes.

---

## N. Non-goals and protected behavior

(See section I above.)

---

## O. Review checklist for future UI tasks

Before submitting any Random Review UI change, verify:

- [ ] All colors from `AppTheme.colors` (no hard-coded `Color(0x...)`).
- [ ] All spacing from `Dimens` tokens (no raw `dp` values except for specific component sizes).
- [ ] All text from `Typography` styles or `Hanzi*`/`Pinyin*` scales (no inline `TextStyle`).
- [ ] Touch targets ≥ 48dp.
- [ ] Content descriptions present for all interactive elements.
- [ ] Both light and dark themes considered (no theme-specific assumptions).
- [ ] No behavior changes (state machine, selection, speech, navigation untouched).
- [ ] No SRS writes introduced.
- [ ] No new dependencies added.
- [ ] `git diff --check` passes.
- [ ] All existing tests still pass.
- [ ] No files outside the intended scope were modified.

---

## P. Assumptions and unresolved decisions

### Assumptions
1. The wheel's dark palette is intentional and should be preserved (arcade aesthetic).
2. The 45-second timer, results dashboard, and session history are NOT implemented yet (confirmed by code inspection).
3. No reduced-motion support exists anywhere in the app (confirmed by grep).
4. No Random Review screenshot tests exist (confirmed by code inspection).
5. `MetaChip` is not currently used in `WordCard` (confirmed by code inspection).

### Resolved and remaining decisions
1. **RESOLVED (D1.1): The wheel is contained in a card.** The app's normal background remains visible around the card. The wheel retains its dark arcade interior. The card has a theme-aware surface, border, and elevation. A fixed selection pointer sits above the wheel. See section E.4 for full specification.
2. **Should the VocabStrip be replaced with a more informative preview?** Current: scrolling marquee of hanzi. Alternative: a grid or list of recent words. *(Still open -- evaluate during D2 entry screen redesign.)*
3. **Should the entry screen have a hero illustration?** Current: no illustration. Alternative: a small themed illustration (like the welcome screen). Requires an asset.
4. **Should the mic button be even larger (120dp core)?** Current: 96dp. Larger would be more prominent but might crowd the card.
5. **Should the help dialog use bullet points instead of a paragraph?** Current: single `Text`. Alternative: structured list.
6. **Should the circular backdrop behind hanzi be larger (140dp)?** Current: 120dp. Larger would give more breathing room but might overflow on small screens.

### Proposals requiring approval
1. `wheelFrame` token in `Mono.kt` — a named token for the wheel frame border color (currently `textPrimary.copy(alpha = 0.35f)`).
2. `hanziBackdrop` token in `Mono.kt` — a named token for the circular backdrop (currently `primaryAction.copy(alpha = 0.08f)`).
3. `UiPreferencesStore.reducedMotion` — a new preference for reduced-motion support.
4. `HanziSmall` for VocabStrip — replace inline 30sp with `HanziSmall` (24sp) for consistency.

---

## Q. Source files inspected

| File | Purpose |
|---|---|
| `app/src/main/java/com/example/ui/screens/RandomReviewScreen.kt` | Main screen, WordCard, WordPhase, DuelControls, HelpControls |
| `app/src/main/java/com/example/ui/screens/RandomReviewEntry.kt` | Entry screen, VocabStrip, SelectionAnimation, dialogs |
| `app/src/main/java/com/example/ui/screens/RandomReviewComponents.kt` | RevealItem, AnimatedMicButton, RippleRings, RecognisedBadge, AnswerLine, CardRule, MetaChip |
| `app/src/main/java/com/example/data/review/RandomArcade.kt` | Wheel palette, timing, attempt counting, contrast |
| `app/src/main/java/com/example/data/review/RandomSelection.kt` | Selection policy |
| `app/src/main/java/com/example/ui/theme/Mono.kt` | AppColors, DarkAppColors, LightAppColors, LocalAppColors, AppTheme, srsStateColor |
| `app/src/main/java/com/example/ui/theme/Theme.kt` | MyApplicationTheme, lightScheme, darkScheme, AppShapes |
| `app/src/main/java/com/example/ui/theme/Dimens.kt` | Spacing and size tokens |
| `app/src/main/java/com/example/ui/theme/Type.kt` | Typography, Hanzi*, Pinyin* styles |
| `app/src/main/java/com/example/ui/components/Surfaces.kt` | AppCard, IconBadge |
| `app/src/main/java/com/example/ui/viewmodel/MainViewModel.kt` | Random Review state, session, selection |
| `app/src/main/java/com/example/MainActivity.kt` | Navigation, bottom nav, RandomReview route |
| `app/src/main/java/com/example/ui/screens/HomeScreen.kt` | (confirmed no Random Review entry after C2.8-B.1) |
| `app/src/test/java/com/example/RandomArcadeTest.kt` | Wheel palette, timing, attempt tests |
| `app/src/test/java/com/example/RandomSelectionTest.kt` | Selection policy tests |
| `app/src/test/java/com/example/SpeechMatchTest.kt` | Speech matching tests |
| `app/src/test/java/com/example/DesignSystemBoardTest.kt` | Screenshot tests (no Random Review golden) |

---

*This document is the visual contract for all future Random Review UI tasks (D2–D6). Deviations require explicit approval and an update to this document.*