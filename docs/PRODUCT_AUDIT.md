# Product Assessment

An audit of the app as a finished product rather than a codebase: whether it does what it says,
whether the parts that exist work, and what is missing that a learner would notice.

Every claim below is either read from source with a `file:line`, or covered by a test. Anything
that could not be checked that way is marked **unverified**.

---

## Summary

The app is more honest than most of its category. The parts that are hard — the scheduling
algorithm, the streak, the progress metrics, the AI's failure modes — are real, measured and
correctly reasoned. Where it was weak, it was weak in the direction of *under*-claiming.

Two things were genuinely broken, and both are now fixed:

1. **A failed review was never revisited** (`SrsRating.AGAIN`). The button said "10 min"; no
   mechanism in the app could act on it. This was the single most consequential defect found.
2. **The daily workload was not the learner's.** The limits that decide what the app recommends
   each day were stored, validated, migrated and read by every scheduling decision — and no
   learner could change them.

Neither required a rewrite. Both were wiring that already existed on one side only.

---

## Does this feel like an intelligent Chinese learning platform?

Mostly yes, and the intelligence is in the right places.

**What is genuinely real:**

| Claim | Evidence |
|---|---|
| SRS adapts per card | `SrsAlgorithm.kt` — ease factor 1.3–2.5, moved ±0.15/±0.20 per rating, clamped. Intervals scale by it. This is SM-2 done properly, and `now` is a required parameter so the algorithm cannot silently read the wall clock. |
| The streak is measured | `SrsRepository.kt:263-294` — same day is a no-op, yesterday extends, a real gap resets to 1. `ProgressDao.kt:90` reads `MAX(longestLength)` for *badges* specifically, because a badge about history measured against the current run is permanently unearnable after one missed day. |
| Progress is counted, not decorated | `ProgressDao.kt:80-92` — five real `COUNT`/`COUNT(DISTINCT)` subqueries over real tables. `sessionId IS NOT NULL` is load-bearing so a review outside a session cannot invent a session. |
| "What next" encodes a teaching judgement | `DashboardAggregator.kt:119-140` — reviews before new words (a due review is decaying memory; a new word is merely unlearned), new words only within quota, difficult words only when caught up. Stated, not implied. |
| AI failure is honest | `GeminiAiService.kt:224-249` — the offline dictionary returns `null` for an unknown character rather than inventing one. It used to fabricate a radical reading `部首` and a fixed five-stroke breakdown for any query, and store it as `provenance = AI_GENERATED`. That was the worst instance of pretending in this codebase, and it is already gone. |

**Where "intelligent" is thinner than the marketing would suggest:**

- **The AI is an authoring assistant, not a tutor.** It drafts a word entry — reading, meaning,
  HSK level, radical, example sentences, stroke breakdown — which the learner then reviews and
  approves before anything is saved (`AddWordScreen.kt:823`, `MainViewModel.kt:798`). That is the
  correct shape for AI here: it removes the tedium of looking a character up, and a human decides
  whether the result is true. It does not adapt teaching, diagnose errors, or generate exercises.
  I think this is the right call rather than a shortfall, and I would resist adding a chat tutor.

- **The SRS has no cross-card or aggregate adaptation.** Ease is per card, which is correct. There
  is no modelling of a learner's *global* difficulty — someone who rates EASY on eight of ten
  cards is not offered more new words. This is a real limitation, but implementing it means
  inventing a heuristic with no evidence behind it, and a wrong one is worse than none.

- **Content is user-supplied or a small built-in dictionary.** There is no curriculum, no graded
  reader, no corpus. The app schedules what you give it. `HSK` is stored per word but nothing
  uses it to sequence learning.

---

## Does SRS actually personalize the review schedule?

Yes, per card — and that is the standard meaning of the word.

`SrsAlgorithm` implements SM-2 with a per-card ease factor, per-rating deltas, a lapse reset
(`AGAIN` sends repetitions to 0), and a capped maximum interval so a long run of Easy ratings
cannot push a card years out. The card is re-scheduled from the card's own history every time.

What it does not do is move the review date based on how well the *deck* is going, or on
retrievability. That would be research-grade work.

---

## Is the learning flow intuitive?

Yes. The deck is the strongest part of the app.

- The order is frozen at session start (`ReviewDeckState.kt:34-43`), because `srs_state.dueDateMillis`
  changes the instant a rating lands — a deck derived from the live query reorders under the
  learner's finger and can declare itself finished early.
- A card cannot be rated before it is revealed (`ReviewDeckState.kt:14-17`). Recording a recall
  that never happened poisons every retention figure derived from `review_log` later.
- One write per card at a time, enforced by comparing state before and after
  (`MainViewModel.kt:886-896`) — the earlier guard read `isRating` once and let double taps
  straight through.
- The rating button shows the interval the write will *actually* produce, via the repository
  rather than a second copy of the rule (`SwipeDeckReviewScreen.kt:306-312`).

This is a thoughtfully built interaction, and it is where the app's care is visible.

---

## What was broken, and is now fixed

### 1. A failed review was never revisited — **the significant one**

`AGAIN` is the most important rating in any spaced-repetition system: it means "I did not know
this". `SrsAlgorithm.kt:68` schedules it ten minutes out, and the rating button displays that
"10 min" because the label comes from the same code that writes the row.

Nothing could act on it:

- `ReviewDeckState.completeRating` advanced the index and never re-inserted the card.
- `wordIds` is frozen at session start, so the deck could not grow either.
- There is no `androidx.work`, no `AlarmManager`, no `JobScheduler` anywhere in the app.
- `remindersEnabled` / `reminderHour` are columns in `user_preferences`, validated on write
  (`Validator.kt:200`), migrated, and read by **no code path**.

So the learner pressed the button that said the word would return, the sitting ended, and the word
returned only if they happened to reopen the app inside ten minutes. The one rating that most needs
a second look was the one rating guaranteed not to get one, while the button advertised it.

**Fixed** in `ReviewDeckState.completeRating`: an `AGAIN` card is re-queued at the end of the
current session. It is posed face-down again on arrival (`relearnIds`), because showing the answer
would hand over the recall the second attempt exists to test. The failure is still recorded —
re-queueing adds an attempt, it never retracts the mistake.

This is what Anki does with a failed card, and the reason it works there is the same reason it was
broken here: a lapse gets looked at again *in the sitting where it happened*.

Covered by 8 new tests, plus three existing tests whose expectations this deliberately changed.

### 2. The daily workload was not the learner's

`dailyNewWordLimit` and `dailyReviewLimit` decide what the app asks for each day:

- `DashboardDao.kt:124-125` reads them to cap the new words and reviews offered.
- `DashboardSnapshot.kt:71-84` uses them to compute the remaining quota and whether it is met.
- `DashboardAggregator.kt:119-140` bases the entire recommendation on those numbers.
- `DashboardContent.kt:234` renders the review goal bar from them, and `:261` the new-words counter.

They are stored in `user_preferences`, validated on write, migrated through `Migration_1_2`, and
read by `UserPreferenceDao` — which has a complete `updateForUser` statement for all nine columns
that **no screen ever called**. There was no control anywhere in the app.

Every learner was on 10 new words a day for the life of their account. That is a defensible
default for an unknown learner and a wrong one for a known one: someone with an exam in three
weeks and someone dipping in for ten minutes are not served by the same number, and the app had no
opinion to offer.

**Fixed**: a "Daily Workload" card in settings with two steppers, wired through
`UserRepository.setDailyNewWordLimit` / `setDailyReviewLimit`. The write is a read-modify-write
inside one transaction, because `updateForUser` sets every column at once and two concurrent
writes would otherwise each read the same "before" row.

Design decisions worth stating: it is a stepper rather than a slider or a text field because the
number must be legible as a plan and cannot be typed into a value the app will clamp; the buttons
disable at the ends rather than clamping silently; `0` is honoured as the deliberate choice it is,
with copy explaining that reviews continue; and the screen shows "Loading your limits…" rather than
the defaults, because a rendered fallback would show a number as though it had been chosen.

Covered by 13 new tests, including the ones that matter: that changing one limit does not reset
the other or the eight settings sharing the row, and that `0` survives.

---

## Where the implementation was pretending to work

Beyond the two fixes, these were verified as **already correct** — worth recording, because the
alternative in each case would have been to add a feature on top of a broken one:

| Was apparently broken | Actually |
|---|---|
| AI fabricates words for unknown characters | `GeminiAiService.kt:241-248` returns `null` and says so. The fabrication path was removed. |
| Streak is decorative | Computed and written in `SrsRepository.advanceStreak`, correctly, with a documented rationale per branch. |
| Progress/XP is invented | Five real SQL aggregates over real tables. |
| Achievements unlock on faked thresholds | `ProgressDao.kt:76-78` — `longestLength` for badges specifically, with the reason a current-run metric would be wrong. |
| Reminders never fire | `SettingsScreen.kt:339` says plainly: "Scheduled daily reminders are not configured in this local-only version." The dead columns are a legacy of a feature that was never claimed as working. |

Still honest, still worth knowing:

- **The review card paints every never-studied word in the failure red**, because `isDue` is true
  for any unscheduled card (`dueDateMillis` defaults to now). It contradicts a rule the app
  states at `MainActivity.kt:176-183`, which it applied to the nav badge. The honest fix is a
  palette decision — `srsStateColor` already maps `NEW` to the same red — so I left it rather than
  make a design change blind.
- **`GEMINI_API_KEY` ships in the APK** and is extractable. Documented as a deliberate trade-off
  at `GeminiAiService.kt:65-79`; not solvable without a backend proxy.

---

## Unnecessary features

Very few. The feature set is disciplined — no streak-shaming, no leaderboards, no ads, no
notifications pretending to be engagement.

The genuinely removable weight is not product but scaffolding: `GreetingScreenshotTest.kt`
(a template test that writes a PNG of "Hello Robolectric!" on every run and defines a `Greeting`
composable used nowhere), `ExampleRobolectricTest.kt`, and two dead tables, `learning_items` and
`character_strokes`.

`strings.xml` holds 2 entries against a UI of hardcoded English literals. For a single-language
app this is a maintainability cost, not a product one — but it means there is no i18n path without
extraction first.

---

## Missing foundational features

Ranked by what a learner would notice, and restricted to things that are actually missing rather
than things that would sound impressive:

1. **A scheduler.** No `WorkManager`, no alarms. This is why `AGAIN` needed an in-session fix
   instead of the scheduled re-delivery it was designed for. It is also why daily reminders are
   not possible, and why `remindersEnabled`/`reminderHour` sit in the schema as columns that
   describe a feature the app does not have. On Android 12+ this needs `SCHEDULE_EXACT_ALARM`
   reasoning, which is why it was not attempted in a final pass.

2. **A real onboarding / first-run path.** What teaches a new learner what a rating button does?
   The "10 min" on the Again button is the only instruction, and it was describing a schedule the
   app could not deliver.

3. **A way to review a word you already know you missed.** The library lists cards; there is no
   "practice these difficult words" action, even though `DashboardAggregator` computes a ranked
   `difficult` list and shows it. The data is there and the button is not.

4. **Content.** No curated course. The app schedules whatever the learner supplies, and a learner
   with an empty collection is told "You are caught up", which is technically true and useless.

---

## Interactions, UI coherence, hidden problems

**Coherent.** One dark palette, one radius scale, one card treatment, consistent spacing. The
app-bar/dashboard/settings/library surfaces read as one product. The accessibility work is real
rather than checkbox-compliant — `IconTarget` and `TouchTargets.kt` exist so the 48dp minimum and
`Role.Button` are applied by construction, and 11sp is documented as the floor for
body-adjacent text with the reason.

**Smooth, with known gaps:**

- Rotation loses dialog state in two places (`LibraryScreen.kt:127-128`, `AddWordScreen.kt:131` use
  `remember` where `:120-121` correctly use `rememberSaveable`).
- No scroll restoration on the library or home lists.
- No cancellation for in-flight AI requests — leaving the screen leaves the request running.
- `AiGenerationState.Error` carries a `String`, so the failure *type* (rate limit, network,
  bad key, malformed response) is lost by the time the UI sees it, and every failure renders as
  generic text.

**Performance.** One real scaling issue: `LearnerDao.kt:23` is
`WHERE userId = ? AND status = 'ACTIVE' ORDER BY addedAt DESC`, and no index covers
`(userId, status, addedAt)`. SQLite narrows by `userId` then sorts the learner's entire
collection on a temp b-tree, on a `Flow` that re-runs on every enrolment. It is invisible at 50
words and gets worse forever. Fixing it needs a schema change to v4, a migration, and a schema
JSON — a deliberate piece of work, not a final-pass fix.

Also: `MainViewModel` is 1156 lines with no test and no constructor seam. It is the largest
untested surface in the app and the next thing I would test.

---

## Verified / unverified

**Verified by execution:** the unit suite, green, read from the JUnit XML rather than inferred
from an exit code. Counts and failure counts are in the commit message for the change that
introduced them.

**Verified by reading source**, with `file:line` throughout the sections above.

**Unverified — I could not check these here:**

- **No UI was ever run.** Every visual change in this pass — the re-queue, the steppers, the
  in-app-bar inset fix — is verified by compilation and by tests on the pure state machine, not
  by a screenshot or a device.
- **The release build cannot be assembled.** `my-upload-key.jks` is absent and `KEYSTORE_PATH` is
  unset, so the release variant cannot be signed or verified here. `isMinifyEnabled = false` with
  a template `proguard-rules.pro`. I would not enable R8 without being able to test the result:
  a large APK is a smaller problem than one that crashes on launch.
- **Touch interaction of the re-queue.** That a failed card returns is tested as a state
  transition. That it *feels* right — that a card returning four cards later is welcome rather
  than irritating — is a design judgement I cannot make from a unit test.
- **Contrast ratios** for the muted/subtle text roles, and the swipe-vs-system-back gesture
  interaction.
