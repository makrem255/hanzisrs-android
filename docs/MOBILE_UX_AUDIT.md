# Mobile UX & Responsive-Design Audit

Scope: the whole app as it presents on a phone held in one hand, in portrait and in
landscape, with a soft keyboard up, with TalkBack on, and with a thumb rather than a
cursor. Target reference device: 360dp wide (a common small phone). Tablet and desktop
are checked for regressions, not as the primary case.

Status legend: **FIXED** = changed in this pass. **OPEN** = found, not yet changed.

---

## 0. What the app is today

There is no responsive layer. There is no type scale. There is no spacing scale. Nine
screens were laid out against an implicit 360x640 canvas and hard-coded, and
`enableEdgeToEdge()` was switched on without a single `WindowInsets` consumer being added
anywhere in the app. That combination is what produces the class of problem this audit is
about: on a small phone the content does not reflow, it clips, and it sits under the
keyboard.

Good news first, so the work is additive rather than a rewrite:

* `SwipeDeckReviewScreen` and `DashboardContent` already carry real design thinking. The
  deck snapshots its cards in the view model, refuses to re-grade an in-flight write, and
  refuses a swipe that has not revealed the answer. The dashboard has an explicit
  Loading/Ready/Failed state machine, refuses to print `0%` for an unmeasured accuracy,
  and already has a `columnsFor(width)` breakpoint helper.
* The destructive-action path is correct: Library asks before deleting, and the wording
  says it cannot be undone.
* Every primary action has a test tag, so the fixes below are verifiable rather than
  hopeful.
* Navigation is real `navigation-compose` with a bottom bar. It was not hand-rolled.

---

## 1. Blocking: content that a phone user cannot reach

### 1.1 `AuthScreen` — the sign-in card cannot be scrolled · **FIXED**

`AuthScreen.kt:89-104`. The `verticalScroll` was on the `Card`, whose height is its own
content height. A scroll modifier whose viewport is already as tall as its content has
nothing to scroll, so it never scrolls. The `Box` around it is `contentAlignment = Center`,
so when the card is taller than the screen the top and the bottom are pushed *off* it.

The consequence: on a 640dp-tall screen the logo and the "Continue as Guest Learner"
button are both off-screen and unreachable, and there is no keyboard shortcut to them. On
any phone in landscape this is guaranteed. Register mode makes the card ~90dp taller and
turns it from "unreachable" into "always unreachable".

Fixed by moving the scroll onto the viewport that can actually scroll, and adding IME
padding so the focused field rises above the keyboard.

### 1.2 `StrokeOrderCanvas` — `ArithmeticException` on a reachable input · **FIXED**

`StrokeOrderCanvas.kt:352`: `currentStep = (currentStep + 1) % strokes.size`. `strokes` is
built at `:98-104` by splitting `strokeBreakdown` on commas and dropping blanks. A value
of `",,,"` — or any all-separator string — yields an **empty list**, and `% 0` throws.

This is reachable by a user, not a theoretical: `AddWordScreen`'s "Stroke Breakdown Names"
field is free text, it is passed straight through `approveAndSaveWord`, and there is no
validation. Type `,,,`, save, open the card's Writing tab, press "next stroke", crash.

Fixed: a blank stroke list is a real state with its own message, and the stepper is
guarded.

### 1.3 `StrokeOrderCanvas` — invented stroke data · **FIXED**

`StrokeOrderCanvas.kt:99-100`. When a character has no stroke data the app substituted
`横 (Héng), 竖 (Shù), 撇 (Piě), 捺 (Nà)`. For any character that is not four strokes that
is a fabricated stroke order, shown under a 米字格 grid as though it were the character's
real one. The "4/4" counter and the "Great job! 很好!" award that follows are then
computed from it.

This is the same class of defect the rest of this codebase was built to avoid: a number
the app did not measure, presented as a measurement.

Fixed: no stroke data now renders an explicit "no stroke data for this character" state,
with the guide and the tracing canvas both disabled. The name "字" is no longer used as a
silent stand-in glyph either.

### 1.4 `StrokeOrderCanvas` — "completion" that checks nothing · **FIXED**

`StrokeOrderCanvas.kt:395-397` set `completed = true` when the number of scribbles
reached the stroke count. Nothing compared the drawing to the character. The green border
and the congratulation were awarded for `targetStrokeCount` arbitrary lines.

Fixed: the tracing canvas is now explicitly practice, states that it is not scored, and
says how many strokes the character has rather than claiming the learner's attempt was
correct.

### 1.5 `AddWordScreen` — the HSK selector overflows a 360dp phone · **FIXED**

`AddWordScreen.kt:442-465`. Six `FilterChip`s in a fixed `Row` with `spacedBy(6.dp)`.
Measured: ~354dp of chips in ~326dp of available width. Compose does not clip or scroll a
`Row` of fixed-width children; it squeezes each label into an ellipsis, so on a small phone
"HSK 4" renders as "HSK…". The learner cannot tell which level is selected.

Fixed: `FlowRow` (the `ExperimentalLayoutApi` that ships in Compose foundation 1.7, so no
new dependency) so the chips wrap instead of compressing, at a real touch height.

### 1.6 The soft keyboard covers every primary action · **FIXED**

`imePadding` appeared **zero** times in the app. `enableEdgeToEdge()` sets
`decorFitsSystemWindows = false`, so the window does not resize for the IME and no content
is inset above it. Consequences:

* `LibraryScreen` — typing in the search field pushes results behind the keyboard. The
  search box is the first thing on the screen, so the *result* of typing is the thing you
  cannot see.
* `AddWordScreen` — the "Approve & Add to SRS Deck" button is at the very bottom of a long
  scroll. Approving a word is the point of the screen and it is the least reachable thing
  on it.
* `AuthScreen` — the password field is mid-card.

`AndroidManifest.xml` also declared no `windowSoftInputMode`, so on API < 30 the window does
not resize at all and there is no manual scroll-out.

Fixed with `Modifier.imePadding()` on the scrolling root of each of the three, plus
`adjustResize` in the manifest as a floor for older platforms.

---

## 2. Touch targets: 29 of them are under 48dp

The Material minimum is 48x48dp. Every entry below is an interactive control whose
measured hit area is smaller than that — 29 controls across the 13 patterns listed. The
worst are 24dp, a quarter of the guideline and below the 9mm threshold at which a
fingertip reliably lands on the intended target.

| Site | Control | Current |
|---|---|---|
| `HomeScreen.kt` audio on a recent-word card | `IconButton(Modifier.size(24.dp))` | 24dp |
| `HomeScreen.kt` "View all (N)" | clickable `Text` | ~18dp |
| `LibraryScreen.kt` sentence audio in the detail sheet | `IconButton(Modifier.size(24.dp))` | 24dp |
| `AddWordScreen.kt` quick-suggestion chips | `Surface.clickable`, 5dp vertical padding | ~23dp |
| `LibraryScreen.kt` ×4 / `AddWordScreen.kt` ×6 filters | `FilterChip` default | 32dp |
| `SwipeDeckReviewScreen.kt` ×3 pillar tabs | `Box.clickable(height(34.dp))` | 34dp |
| `AuthScreen.kt` ×2 Email/Phone tabs | `Box.clickable(height(38.dp))` | 38dp |
| `StrokeOrderCanvas.kt` ×2 mode tabs | `Box.clickable(height(36.dp))` | 36dp |
| `StrokeOrderCanvas.kt` ×5 playback/tracing controls | `IconButton`/`Button(height(38.dp))` | 38dp |
| `SwipeDeckReviewScreen.kt` card audio | `FilledTonalButton(Modifier.size(42.dp))` | 42dp |
| `SwipeDeckReviewScreen.kt` "Review Entire Deck Again" | `OutlinedButton(height(44.dp))` | 44dp |
| `SettingsScreen.kt` sign out | `OutlinedButton(height(46.dp))` | 46dp |
| `SwipeDeckReviewScreen.kt` sentence audio | `IconButton(Modifier.size(32.dp))` | 32dp |

The recurring mistake is the same everywhere: `Modifier.size(n.dp)` applied to the
*touch container* instead of to the icon inside it. `IconButton(Modifier.size(24.dp))`
silently defeats `minimumInteractiveComponentSize`, because `.size()` sets `maxHeight` to
24 and the 48dp minimum is then coerced back down to it. The fix is to size the `Icon`,
not the button.

**FIXED** across all of the above. `ui/components/TouchTargets.kt` now owns the floor as
`MinTouchTarget` plus four composables, so the mistake has one place to be made instead of
thirteen:

* `Modifier.minimumTouchTarget()` — wraps `minimumInteractiveComponentSize()`.
* `IconTarget` — a 48dp box with a `Role.Button` `clickable`, for "small icon, full
  target". The role matters: a bare `Box(Modifier.clickable)` is announced by TalkBack as
  clickable *text*, and the icon's own `contentDescription` then reads as a label on a
  static element rather than as the name of a control.
* `SegmentedOption` — a real touch height *and* `selectable` with `Role.Tab`, so the
  three deck pillars, the two stroke modes and the two auth tabs each announce which
  option is chosen instead of reading as three unrelated buttons.
* `SwitchRow` — the whole row is the toggle, in §2.1.

The one row in the table that is now moot: the `AddWordScreen` quick-suggestion chips were
hardcoded linguistic data in client code (§13), so the chips are gone rather than resized.

### 2.1 `SettingsScreen` — the switch rows are not tappable on their label · **FIXED**

The `Switch` is 52x32dp with a 48dp target of its own, but it occupied a corner of a
full-width `Row` whose `Column` of label text was not part of any clickable. A phone user
aims at the words "Slow pronunciation", which do nothing. The target was 48dp but it was in
the wrong 40% of the row.

Fixed: `SwitchRow` puts the whole row on the toggle, so the switch and its text are one
control. Three details matter and all three were wrong at least once on the way:

* The row is a `Row`, not a `Box` with an overlapping `Column` — a `Box` stacks the label
  and the control on top of each other, which is how the first attempt rendered it.
* The `Switch` is passed `onCheckedChange = null`. The row owns the interaction; if the
  switch also handled the tap, one tap would toggle twice and land back where it started —
  a bug that gets filed as "the switch is broken".
* The row is `toggleable` with `Role.Switch`, so a screen reader announces *one* control
  rather than a label followed by an unlabelled switch.

---

## 3. Fully usable without a mouse

This was the explicit requirement, so it is worth being precise about what "no mouse"
means on a phone.

**Good news:** the app is already touch-first in the way that matters. Every action has a
button. The review deck's four rating buttons are 56dp tall and `weight(1f)`-wide, so they
are reachable with either thumb. Nothing depends on hover, right-click, scroll-wheel, a
`Ctrl`-modifier, or long-press. The swipe is a *shortcut* to a choice the buttons also
make — which is the correct relationship between a gesture and its buttons, and it was
deliberately built that way.

**Gaps found:**

1. **Unlabelled controls are invisible to TalkBack, and therefore to everyone using
   screen reading.** The word detail view's two audio `IconButton`s carried
   `contentDescription = null` on *interactive* controls. Every other icon in the app either
   has a description or is decorative. **FIXED** — they now announce what they play, and
   they are `IconTarget`s, whose `clickable` carries `Role.Button`. A bare
   `Box(Modifier.clickable)` is announced as clickable *text* with no indication that
   activating it does anything, and the icon's own description then reads as a label on a
   static element rather than as the name of a control.
2. **The hand-rolled segmented controls announce nothing.** A `Box.clickable` is a plain
   click, so TalkBack read the three deck tabs as three unrelated buttons with no
   "selected" state, and the same for the two stroke-mode tabs and the two auth tabs.
   **FIXED** — they are `SegmentedOption`, which is `selectable` with `Role.Tab`.
3. **The deck's pillar tabs were emoji, not icons.** Three emoji glyphs as tab labels is
   unreadable to a screen reader (the description has to be the *text* label, and the
   emoji then fights it for the same 34dp) and ambiguous even with the label, since there
   is no universally agreed pictogram for "writing" versus "meaning" versus "context".
   **FIXED** — real Material icons plus the text label, at 48dp.
4. **The swipe has no accessible equivalent beyond the four buttons**, which is
   acceptable because the buttons do exist and are 56dp. Left as found.
5. **Nothing anywhere sets a `contentDescription` on a `Spacer`**, so the layout does not
   produce phantom swipe targets. Clean.

---

## 4. Keyboard and IME

1. No field on any screen set `imeAction` or `KeyboardActions`, so the action key was the
   platform default and useless. The Add-Word form is ten fields, and every one of them
   cost the learner a round trip through the keyboard's own "next field" control. **FIXED**
   across Auth, Library search and every Add-Word field.

   Two distinct defects were in there, and the second is the interesting one:

   * The free-text fields — Meaning, Radical, both Sentences, Stroke names — are not
     `singleLine`, so they showed a literal **newline** key. For a definition or an example
     sentence a newline is defensible; for ten fields in a row it means the only way to the
     next one is a different key entirely.
   * The `singleLine` fields — Hanzi, Pinyin, the identifier, the password — showed Enter.
     `KeyboardOptions.Unspecified` lets the platform decide, and on a single-line field the
     platform picks Enter, which is a *submit* key. There is nothing to submit, so it
     dismissed the keyboard and threw away the learner's place in the form.

   Worth recording how this was fixed, because the obvious edit does not compile. The
   `imeAction` was originally passed as a top-level `OutlinedTextField` argument; **Material
   3 removed that parameter in 1.3.0**, so the only place an action key can be declared is
   `keyboardOptions`. Moving the argument is not enough on its own either — a field that
   declares `ImeAction.Next` and has no `KeyboardActions` behind it renders the right glyph
   on the keyboard and then does nothing, which is *worse* than no action key, because it
   teaches the learner the key is not worth pressing. So every field now has both, wired to
   `focusManager.moveFocus(FocusDirection.Next)` or `clearFocus()` on Done.

   Library's search is the one that is not "run a search": the list below is filtered live
   from the query, so the results already exist while the keyboard is up. Its action key
   dismisses the keyboard so the results can be seen, which is what the key is for.
2. No `autofill` hints. The login form cannot be filled by the user's password manager, so
   a phone user types their password by hand every time. **OPEN — not done.** There are
   zero occurrences of `autofill` or `contentType` in the source. The fix is
   `Modifier.autofill(AutofillType.Username)` on the identifier field and
   `AutofillType.Password` on the password, plus a `ContentType.Password` visual
   transformation. It was left out because the identifier field is polymorphic — it takes
   an email *or* a phone number depending on the mode tab — and guessing the autofill type
   from a tab index is worse than declaring none. It wants a decision on whether the
   password manager should be offered the email variant only in email mode.

---

## 5. Orientation and configuration change

1. **Landscape breaks the deck.** `SwipeDeckReviewScreen`'s root is a `Column` of
   progress bar → card `weight(1f)` → rating row. A Pixel-class phone in landscape leaves
   ~330dp of content height. The old fixed costs took ~62dp of that before the card was
   drawn, and the rating block took another ~97dp once the card was revealed — leaving
   roughly 170dp of viewport for a card whose content is about 420dp, headed by a 56sp
   character. The learner saw the glyph and had to scroll for the answer they were being
   asked to rate.

   **FIXED**, by shedding what is duplicated elsewhere rather than by restructuring the
   screen. The deck now measures the height it actually has (via `BoxWithConstraints`, with
   the scaffold's inset applied *outside* so the measurement is of real content, not of
   content plus an un-subtracted inset) and below 520dp: the progress bar goes, because
   the counter row directly beneath already prints "Card 3 of 12"; the rating label goes,
   because each of the four buttons already prints the interval it will apply; and the
   character comes down from 56sp to 36sp, which is still larger than any other glyph in
   the app. That is roughly 110dp returned to the card. The card still scrolls, so nothing
   is unreachable at any height.

   What this deliberately is *not*: a separate landscape layout. A second layout would have
   two sets of the four rating buttons and two sets of the three pillar tabs to keep in
   agreement, for a difference that only the measurement-driven shedding above does not
   already cover.
2. **Rotation loses in-progress deck state.** `offsetX` and `selectedPillarTab` were
   `remember`, not `rememberSaveable`, so rotating mid-drag jumped the card back to centre
   and dropped the selected tab. `isFlipped` and the index live in the `ViewModel` and
   correctly survived. **FIXED** — both are now `rememberSaveable`.
3. **Rotation resets scroll position** on the five scrolling screens, because
   `rememberScrollState()` is not saveable. **OPEN.** A long form scrolled to the bottom
   returns to the top on rotation. The fix is mechanical (`rememberSaveable` holds a
   `ScrollState` via a `Saver`), and it was left rather than done because the honest
   version of it needs a decision about which screens should restore a position: restoring
   on a results screen would scroll the summary out of view on the way in.
4. **The manifest declares no `configChanges`**, so the activity is recreated on rotation.
   That is the right default for Compose and is safe now that state is either in the
   `ViewModel` or saveable. Left as found, deliberately.

---

## 6. Screen sizes and responsive behaviour

1. `DashboardContent.columnsFor` is a real breakpoint helper (1 / 2 / 3 at 400 / 720dp)
   reading the *container* width via `BoxWithConstraints`, which is the correct way — it
   handles split-screen and foldables for free. This was already good and was left alone.
2. **`TodayCard` overflows in 2-column mode.** Three `MiniStat`s in a `Row` with 18dp gaps
   needs ~230dp. On a 411dp phone (Pixel-class) `columnsFor` returns 2, so each card is
   ~183dp wide and "Accuracy" wraps to two lines inside a card whose sibling is
   single-line. **FIXED** — the stats take `weight(1f)` and the gaps shrink with the
   available width.
3. **`AchievementGrid` takes a hardcoded `columns = 2`.** **This finding was wrong when it
   was written** — `ProgressContent` already called `columnsFor(maxWidth)` and passed the
   result, inside a `BoxWithConstraints`, with a comment saying so. The default `= 2` on
   the function is a default, not a call site's value. Re-checked against the file rather
   than reported as a fix.

   What *was* real, and is **FIXED**: `AdaptiveGrid` accepted a column count without
   checking it against the number of children. At three columns with two children — which is
   what `columnsFor` returns on a tablet for a grid of two cards — it reserved a third of
   the width for an empty slot, so the two cards were sized as though a third existed. The
   breakpoint says how many columns *could* fit; the grid now says how many are needed.
4. **The stroke canvases are a fixed `230.dp` square.** The word detail view rendered one
   inside an `AlertDialog`, whose `widthIn(min = 280.dp)` plus 24dp of dialog padding left
   ~204dp. A 230dp child in a 204dp box is clipped. **FIXED twice over** — the canvases
   size themselves from the box they are given, clamped to 180–260dp, *and* the container
   is now a `ModalBottomSheet` (§7.1), which is full-bleed and gives the canvas the whole
   screen width on a phone and on a tablet alike.
5. **Library and Add Word are full-bleed single-column** on a tablet, with a 56dp hanzi
   glyph and one short definition marooned in 800dp of width. Not broken, but not
   designed; the dashboard is the only screen that adapts. **OPEN** — the single-column
   form layout is defensible for a form, and making it two-pane is a larger change than
   this pass should make blind. This is the clearest remaining gap and the one thing here
   that a device check should confirm before it is called a real deficiency rather than a
   taste judgement.
6. **The home pillar grid** was the one other fixed-column layout, and it was worse than
   the library: four columns on a 360dp phone gave ~76dp per card, which ellipsised
   "Stroke order" at 11sp. **FIXED** — `BoxWithConstraints` with 2 columns below 400dp and
   4 at or above, chunked into rows with a weighted spacer so a partial row keeps the
   outer column edges rather than left-aligning under itself.

---

## 7. Modal behaviour

1. **`WordDetailDialog` used `AlertDialog` for a content-rich view.** A stroke canvas, a
   definition, a radical and a three-line example is not an alert, for three separate
   reasons:

   * **Height.** `AlertDialog` reserves its own title and button rows, so the body — the
     only part that varies in length — gets what is left. With the stroke guide expanded
     this view is taller than a 640dp phone, and the dialog clipped it with the *Close*
     button pinned over the content. The character being looked up, the stroke order and
     the example sentence were the three things most likely to be off-screen.
   * **Position.** A dialog centres its content. This view is a character study: a
     Tian-grid glyph wants a stable known position, and centring meant the glyph moved
     vertically as the user switched between the guide and practice modes.
   * **Reach.** A bottom sheet puts its content in the lower two-thirds of the screen,
     which is where a thumb already is.

   **FIXED** — `WordDetailSheet`, a `ModalBottomSheet`: full height, scrollable, dismisses
   on drag, tap-outside or back. The glyph, reading and provenance chips sit above the
   fold so the sheet answers "what is this character" before any scrolling. There is an
   **explicit close button as well as** the drag handle and backdrop tap, because
   gesture-only dismissal is unreachable for anyone navigating by switch access or
   TalkBack without a direct-touch gesture.

   The delete confirmation stays an `AlertDialog`, because a two-button decision *is* an
   alert.
2. Nothing dismissed a dialog on a hardware-back press other than `onDismissRequest`, which
   M3 wires to back correctly. Clean.
3. No dialog traps or double-dismiss problems. Clean.

---

## 8. Loading, empty and error states

These are in good shape and were mostly left alone, which is worth recording:

* `DashboardUiState` and `ProgressUiState` are sealed Loading/Ready/Failed, with a retry
  that calls a real refresh. The failure state names what failed and offers a way to fix
  it.
* The deck's "Nothing to review" flash-before-load bug was already fixed, and the
  `wordsLoaded` guard is the right shape.
* Empty states distinguish "you have nothing" from "nothing matched your filter" in
  Library.
* `DashboardAggregator` refuses to print `0%` for an unmeasured accuracy and refuses a
  `0/600` for a badge whose metric is not tracked.

### 8.1 A loading state with no exit — **FOUND AND FIXED**

This one is worth setting out separately, because it is the failure mode the rest of §8 is
about, and it was hiding inside a guard that looked correct.

`MainViewModel.ensureReviewSession` was:

```kotlin
fun ensureReviewSession(words: List<WordWithSrs>) {
    if (_reviewSessionWordIds.value == null && words.isNotEmpty()) startReviewSession(words)
}
```

The screen's first branch is `if (!wordsLoaded || reviewSessionWordIds == null)` → the
loading view. The two halves of that guard disagree about what they are waiting for.
`wordsLoaded` resolves for *every* learner, because `userWords` emits `emptyList()` when
there is no user. `reviewSessionWordIds` resolved only if there was at least one word. So
for a learner with an empty library — or anyone whose `currentUser` had not resolved yet —
the second half of the condition stayed `null` forever, and the spinner the guard was
waiting on was the spinner the guard was showing.

The consequence is the worst kind: not an error, not a wrong number, a screen that looks
like it is working. And it hits precisely the newest user, on the first screen they open
after signing in, which is also the screen a notification can deep-link them into.

Fixed by making the empty case settle. `reviewSessionWordIds` is now an **empty list**,
which is a different statement from the `null` that means "we have not looked yet", and
the screen can tell them apart. The completion view was adjusted to match, and that
adjustment is the part worth noting: it had been showing a green tick and the title "All
caught up" whenever `nothingLeft` was true. But "nothing is due" is a fact about the
schedule, not an achievement — so the tick and the title now depend only on `studied`,
meaning a sitting that actually happened, and `nothingLeft` only selects the explanatory
sentence. A learner with zero words is not to be congratulated for having no words.

**One remaining lie, partly addressed.** `SettingsScreen` — `notificationPreviewsEnabled`
is a labelled setting that resets every time the user leaves and returns to the screen.
The storage decision is still **OPEN**: it is now `rememberSaveable`, so a rotation no
longer silently re-enables a setting the learner just turned off, but it is still not
persisted across leaving the screen. That is the correct state to be in until someone
decides whether this is a stored user preference (which would mean writing to
`user_preferences`, which exists and has `remindersEnabled` and `showPinyin` on it) or a
local preview switch that is *supposed* to reset. It is documented in the code as the
former question rather than guessed at.

**One stale promise, FIXED.** `SessionSummaryCard` and the completion view promised what
the button did not do. "Review Entire Deck Again" started a sitting over what was *due
now*, and — the part that mattered — over a `dueWords` snapshot taken before the reviews
the learner had just written reached the database. So the button either re-presented the
deck that had just been finished, or, once the flow had caught up, started an empty
sitting. `restartReviewSession()` now queries the repository in a one-shot call
(`getDueWordsForUserOnce` → `VocabularyDao.dueForUserNow`) rather than reading a flow, and
reports "All caught up" with the button removed when the answer is nothing. A control that
can only re-report nothing is a control the learner learns to distrust.

---

## 9. Performance

1. **`AnimatedStrokeOrderPlayer` autoplays an infinite loop.** `isPlaying` starts `true`,
   and the `LaunchedEffect` advances a step every ~1.3s forever, driving
   `animProgress.snapTo` + `animateTo` on every tick. On a phone that is continuous
   recomposition and continuous redraw of two full-glyph `Canvas` passes — a
   `nativeCanvas.drawText` of a 161px glyph, twice — for as long as the Writing tab is
   visible, on a battery, whether or not the learner is looking at it. **FIXED** — the
   guide now steps only when asked, and a learner who wants a loop can press play. The
   control is still there; it just no longer starts itself.
2. `UserTracingCanvas` re-appends a point to `mutableStateListOf`-backed state on every
   drag event and redraws every committed stroke. Bounded by the 230dp box, acceptable.
3. `detectHorizontalDragGestures` on the deck card wrapping a `verticalScroll` child is a
   real gesture conflict; in practice the horizontal slop wins and the vertical scroll is
   unaffected, but it is one of the two things that would need to change for the landscape
   layout in §5. Handled by the layout change rather than by the gesture.
4. **`collectAsState()` in 33 places, `collectAsStateWithLifecycle()` in none.** State
   collection continues while the app is backgrounded, so a Flow-driven database query
   keeps running behind a locked screen. **FIXED** — `lifecycle-runtime-compose` was
   already a dependency, so this was a mechanical substitution and no dependency was added.
   Verified by grep: 0 remaining `collectAsState()`, 34 `collectAsStateWithLifecycle()`
   across `app/src/main`. The 34th is the dashboard's own new collection.

---

## 10. Typography

161 `fontSize = N.sp` call sites across 11 files, and `Typography` in `Type.kt` defined
exactly one style (`bodyLarge`), which nothing used. The type scale was bypassed
completely: the app could not honour the system font-size setting, and there was no floor.

Concretely, on the smallest text in the app:

* `HomeScreen.kt` — `9.sp` for the pillar subtitles, `9.sp` for the HSK badge
* `LibraryScreen.kt` — `9.sp` for the HSK badge
* `SettingsScreen.kt` — `9.sp` for the profile badge
* `StrokeOrderCanvas.kt`, `SwipeDeckReviewScreen.kt` — `10.sp` for interval labels, the
  state badge and the "1/4" counter
* `DashboardContent.kt` — `10.sp` for the weekly chart's bar counts and weekday ticks

9sp is below the legibility floor for body-adjacent text and 10sp is marginal on a 6.1"
phone at arm's length. **FIXED, completely** — a real `Typography` scale was defined in
`Type.kt` (all 15 M3 roles, sized for a phone rather than a desktop baseline), and *every*
sub-11sp site in `ui/` is now at 11sp or above, verified by grep:

```
> search for `fontSize = N.sp` with N < 11 across app/src/main/java/com/example/ui
(no matches)
```

That includes the weekly chart, which was the last holdout and the least excusable: a bar's
count is the single number the chart exists to communicate, and it was the smallest text on
the screen. The rule now lives in the `Typography` doc comment rather than in a constant,
because a constant nothing reads is documentation wearing a type.

**Still OPEN:** all 161 of those per-call `fontSize`s are still off the scale, so the app
still cannot honour the system font-size setting. This is deliberately incremental and
**not** a scripted rewrite — a mechanical `fontSize = 12.sp` → `style = bodySmall` can
silently change the weight of a label that already sets `fontWeight`, and the screenshot
tests are the only thing that would notice. It wants to be done by hand, one screen at a
time.

The concentration, for whoever picks it up first:

| File | Call sites |
|---|---|
| `DashboardContent.kt` | 32 |
| `LibraryScreen.kt` | 22 |
| `SwipeDeckReviewScreen.kt` | 21 |
| `HomeScreen.kt` | 16 |
| `AddWordScreen.kt` | 14 |
| `SettingsScreen.kt` | 12 |
| `AuthScreen.kt` | 12 |
| `StrokeOrderCanvas.kt` | 12 |
| `ProgressContent.kt` | 17 |
| `TouchTargets.kt` | 2 |
| `MainActivity.kt` | 1 |

---

## 11. Navigation

1. The bottom bar holds four destinations — Routine, Add, Library, Settings — and the
   Review deck is correctly *not* one of them, because a study surface wants the whole
   screen. `popUpTo(startDestination) { saveState }` + `restoreState` is the right
   pattern: each tab keeps its own scroll and search state.
2. **The `dueCount` badge is on the wrong tab and is a red badge.** It sits on Routine
   (Home), not on the review deck it is counting toward, and it used `SrsAgainDark` — the
   exact colour the app uses for "you rated this Again". A count of work outstanding is
   legitimate information, but rendering it in the failure colour on a tab that is not the
   task puts pressure on the user for something they have not done yet, at any hour of any
   day. **FIXED** — the badge now uses the app's own accent (`LilacPrimary`). The count
   is unchanged; only the threat framing is gone, which is what the anti-manipulation rule
   was about. Leaving it red would have been a judgement call in the wrong direction.
3. `LibraryScreen` has no `onNavigateBack`. It is a bottom-nav destination, so there is
   nothing to go back to, and the system back button does the right thing. Correct as
   found.
4. **Add Word's back button does two different things** (`AddWordScreen.kt:121-127`): from
   the review step it discards the AI draft; everywhere else it navigates back. A control
   that means "discard my work" on one tap and "go back" on the next, with no difference
   in appearance, loses data silently. **FIXED** — the draft-discard path now confirms
   first.

---

## 12. Non-manipulation, re-checked against the mobile brief

The gamification work set a line: real activity only, no randomness, no streak-loss
pressure, no leaderboards. A mobile audit is a good moment to check the presentation
layer against it, because presentation is where pressure actually lives.

* **No streak-loss or streak-at-risk messaging anywhere.** The streak card says "Not yet
  extended today", which is a fact about a counter and not a threat. Correct.
* **No countdown on locked badges.** A locked badge shows "3 / 50", a measurement of study
  done, not "47 to go". Correct, and deliberately so.
* **No "come back or lose your streak" copy** in the notification preview, which is the
  classic place it would appear. Correct.
* **The session summary is flat.** It reports answers, accuracy, XP, and rating
  distribution, and it does not congratulate. Correct, and it also cannot congratulate on an
  empty session: `ReviewSessionCompletedView` shows an `Info` glyph and the title "Nothing
  to review" unless a sitting actually happened. The green tick and "Session complete" are
  reachable only by `studied` — *not* by `nothingLeft`, which is a statement about the
  schedule rather than an achievement. See §8.1, which is where that distinction was found
  to have been drawn the wrong way round.
* **The red due badge is gone**, §11.2. The count is still there — it is real information —
  but it is the app's accent rather than its failure colour. That was the last place the
  presentation layer was measuring the user against something.

---

## 13. Smaller real defects found on the way

* `SwipeDeckReviewScreen.kt:876` — the button said "Review Entire Deck Again" but the code
  beside it starts a session over *what is due now*, which is a deliberate correctness fix
  from earlier work. The label still described the old, wrong behaviour. **FIXED.**
* `AddWordScreen.kt:95` — `quickSuggestions` hardcodes seven Chinese words in client code,
  and `take(4)` means three of them are dead. Linguistic data belongs in the dictionary,
  not in a composable. **FIXED** — the list is gone; the field takes what the learner
  types.
* `SettingsScreen.kt:164` — the profile card falls back to a literal
  `"learner@hanzisrs.com"` when there is no user. That is fabricated data on screen.
  **FIXED.**
* `StrokeOrderCanvas.kt:367` — `strokes` was not reset when the character changed, and
  `pointerInput(Unit)` captured `targetStrokeCount` from the first composition forever, so
  a second character's target was always the first character's count. **FIXED** by keying
  on the character.
* `Color.kt` — 20 alias tokens (`CrimsonPrimary`, `ImperialGold`, `JadeGreen`, `InkBlack`,
  `RicePaper`…) that are all assigned to the lilac palette and none of which are referenced
  by any screen, plus mojibake in the tone-colour comments and two wrong tone names.
  **FIXED** — deleted the aliases, repaired the comments to the standard four tone names
  plus the neutral. The only two retained are `TianGridLine`/`TianGridCenter`, which are
  genuinely referenced.
* `Theme.kt` — `val colorScheme = SophisticatedDarkColorScheme`, with `darkTheme` and
  `dynamicColor` parameters accepted and then ignored, on a dark-only app.

  **Left as found, and the parameters were removed rather than honoured.** This was
  attempted and reverted, and the reason is worth recording, because the "obvious fix" here
  is a trap.

  Honouring `darkTheme` by adding a `lightColorScheme` does not work, and shipping it would
  have broken the app. The screens do not read their colours from
  `MaterialTheme.colorScheme` — they name `DarkBg`, `DarkSurfaceCard`, `TextLight`,
  `LilacPrimary`, `SrsGoodDark` and so on at **600 call sites across 12 files**. The only
  scheme token anything in `ui/` reads at all is `error`, at four call sites.

  So a `lightColorScheme` would change nothing the app draws, and would change one thing it
  does: the components that *default* to scheme colours rather than being told what to use.
  `NavigationBar`, `TopAppBar`, `Switch`, `OutlinedTextField` and `LinearProgressIndicator`
  would all follow the scheme, because that is what they do when nothing overrides them. A
  phone set to light mode would have had a white bottom bar and pale text fields sitting on a
  charcoal screen. That is worse than ignoring the parameter, because it looks like a theme
  and is not one.

  A light theme is a tokenisation pass over the whole UI — 600 call sites — not a
  colour-scheme swap. The parameters belong in this signature in the commit that has done
  that work, and not before.

  `dynamicColor` is the same argument plus a second problem: wallpaper-derived accents
  would replace the lilac that carries *meaning*. The four SRS rating buttons are the one
  place in the app where a colour is a label, and they sit next to a legend naming them.
  If those four could change hue to match someone's wallpaper, the legend would stop being
  true.
* `HomeScreen.kt:115` and `AuthScreen.kt:173` said **"HanziFlow"**; `strings.xml` said
  **"HanziSRS"**; the package is `com.example`; the launcher icon is the template. Two
  names for one app, visible in the same screenshot. **FIXED** — one name, in
  `strings.xml`, read via `stringResource` at every site that names the app.
* `VocabularyDao.LIBRARY_PROJECTION` did not carry `vocabulary.provenance`, even though
  `AddWordScreen` and both migrations have been writing it for some time. So the detail
  view labelled a word "Added by you" or "Library" based on nothing at all. **FIXED** — the
  column is projected, and the sheet prints the recorded value, with an unrecognised value
  rendered as "Source not recorded" rather than rounded to the nearest known category. The
  same join already had `pinyin_syllables` and `characters` attached, so `toneNumber`,
  `toneContour`, `partOfSpeech` and `structure` came along at no extra cost, and the sheet
  now shows tone as a number and a contour instead of only as an accented vowel.
* `MainActivity.kt` bottom-bar labels — **this finding was wrong.** The labels already
  hardcoded `11.sp`, not a sub-floor size. Nothing was changed.

---

## 14. What was deliberately not changed

* **The deck's swipe-to-grade gesture.** 250dp of travel to commit a real SRS write is a
  reasonable threshold and the buttons are the primary path. No change.
* **`SrsAlgorithm` and the scheduling model.** Out of scope for a UI audit, and the
  long-absence interval bug is already logged against the v4 migration.
* **Two-pane forms on tablets.** §6.5. Real, but it is a design project, and doing it
  without a device to check it on would be guessing.
* **A light theme.** §13. The parameters were removed rather than honoured, because
  honouring them would have shipped a broken app.
* **Autofill hints.** §4.2. `AuthScreen`'s identifier field is polymorphic — email or
  phone, chosen by a mode tab — and guessing the autofill type from a tab index is worse
  than declaring none. It wants a product decision, not a patch.
* **Saveable scroll positions.** §5.3. Mechanical to implement, but restoring a scroll
  offset on a results screen scrolls the summary out of view on the way *in*, so the
  honest version needs a per-screen decision rather than a blanket change.

---

## 15. Verification

Every change above is enforced by the existing unit-test suite, which must not regress: the
dashboard, SRS, migration and gamification tests all exercise the same view models and
aggregators these screens read.

`:app:testDebugUnitTest` — **245 tests, 0 failures, 0 errors, 0 skipped, 16 classes,
39.2s of test time.** Up from the 168-test baseline that preceded this work. Per class:

| Class | Tests |
|---|---|
| `ProgressEngineTest` | 37 |
| `DashboardAggregatorTest` | 32 |
| `ReviewDeckStateTest` | 27 |
| `GamificationRepositoryTest` | 25 |
| `PronunciationServiceTest` | 23 |
| `DashboardRepositoryTest` | 20 |
| `SchemaRelationshipTest` | 20 |
| `DatabaseMigrationTest` | 16 |
| `PinyinAnalyzerTest` | 13 |
| `StrokeNameParserTest` | 8 |
| `PasswordHasherTest` | 7 |
| `SrsCardStateContractTest` | 7 |
| `SrsAlgorithmLimitsTest` | 6 |
| `SrsAlgorithmTest` | 2 |
| `AppResourcesTest`, `GreetingScreenshotTest` | 1 each |

New coverage: `StrokeNameParserTest` (8) for the parser `StrokeOrderCanvas` now depends
on, including the reachable `",,,"` input that used to crash it; the gamification engine and
repository (37 + 25); and six more `DatabaseMigrationTest` cases for the 2→3 upgrade,
including `runMigrationsAndValidate`.

Two of the failures this suite caught were in the new tests rather than in the code, and
both were the same mistake: **an assertion written from the intent rather than from the
behaviour.**

* `assertEquals(4, strokes.map { it.nameCn }.distinct().size)` — four identically-named
  strokes have a `distinct()` size of 1, so the assertion contradicted the line above it,
  which asserted four rows. The parser has no dedup and never did.
* `assertEquals(1, stepped.index)` after a mid-write `next()` — `next()` returns `this`
  while a write is in flight, so the index correctly stayed 0. The test was asserting that
  the guard had *failed*.

Both are worth recording because they are the failure mode this suite is supposed to
prevent, pointed the other way: a test that encodes what the code should do, rather than
what it does, fails loudly here — but the same carelessness pointed at a *missing* guard
would have passed.

### A real bug this audit surfaced

`ProgressEngine.levelFor` never returned for a total above ~2.147 billion:

```kotlin
while (totalXpForLevel(level + 1) <= safe) level++
```

`totalXpForLevel` *saturates* at `Int.MAX_VALUE`, so from level 6554 onward it returned
`Int.MAX_VALUE`, and `Int.MAX_VALUE <= Int.MAX_VALUE` is true forever. The loop was
infinite. `levelProgress` is called while drawing the progress screen, so any caller
passing a capped-or-overflowed XP total would have hung the **UI thread** rather than
throwing. The fix runs the comparison against an unclamped `Long` and bounds the search at
a `MAX_LEVEL` no `Int` total can reach. `ProgressEngineTest` now pins termination with a
`@Test(timeout = 5_000)`, the exact level at the saturation boundary, and a monotonic sweep
over ordinary totals — because the failure was not "a wrong answer", it was "no answer",
and only a timeout can catch that.

It was found by a hung build rather than a failed assertion, and the diagnosis is worth
keeping: Gradle writes its XML only at task end, so a hung suite produces *no* result
files, and "no failures recorded" reads exactly like "all green" if you are not counting
them. `jcmd <pid> Thread.print` located the loop in one step. **Count the result files.**

### What could not be verified here

Touch-target and layout assertions cannot be made in a JVM unit test. The honest position
is that the target sizes in §2 were computed from the modifier chains by reading them, not
by measuring a laid-out tree, and they need a device or a Robolectric/Compose layout test to
confirm. That is **OPEN** and is the most useful next thing to build, because it would make
this whole class of defect detectable in CI rather than by reading.

Likewise: nothing in this audit was run on a physical device or an emulator. Every claim
about what fits in 360dp, or about a layout in landscape, is arithmetic from the modifier
chains and the documented device metrics. The numbers are the ones a reviewer would
check, and they are recorded here precisely so that someone *with* a device can.

The `ensureReviewSession` fix in §8.1 is the one behavioural change in this audit with no
test behind it, because `MainViewModel` needs an `Application` and a database to construct,
and this suite has no harness for it. The logic is four lines and the fix is obvious from
the guard it corrects, but "obvious" is what was said about the bug.
