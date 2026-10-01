# Code review and refactor

A senior review pass over the whole project for duplication, dead code, unnecessary
dependencies, oversized components, coupling, layering, repeated API and database logic,
inconsistent error handling, missing validation and poor abstractions — followed by the
refactoring that was actually justified.

The governing rule for this pass, stated because it decided most of the findings:

> **Do not refactor working code for stylistic reasons.** Change something only where there is
> a real cost — duplication that can drift and already has, coupling that blocks testing,
> complexity that is hiding a bug, a missing transaction, a crash path. A large file that is
> large but coherent is left alone, and this document says so where it applies.

Every finding is either **FIXED** (changed in this pass, with the test that holds it) or
**OPEN**. Nothing is listed as fixed that was not compiled and run. Where a number appears it
was grep-verified or measured, not estimated.

---

## 1. What this pass changed, at a glance

| # | Finding | Severity | State |
|---|---|---|---|
| 1 | Study-day bucketing disagreed between the write and read paths | **Critical** | FIXED |
| 2 | `newWordsIntroduced` was never incremented | **High** | FIXED |
| 3 | Login revealed whether an account existed | **High** | FIXED |
| 4 | A read failure told the learner their vocabulary was empty | **High** | FIXED |
| 5 | `finishSession` could kill the process on a database error | **High** | FIXED |
| 6 | Ease factor was truncated, losing 0.01 per AGAIN | **Medium** | FIXED |
| 7 | The daily new-word limit was reported but not enforced | **Medium** | FIXED |
| 8 | `SrsAlgorithm` read a wall clock it was not given | **Medium** | FIXED |
| 9 | The due-words rule was written twice, in two spellings | **Medium** | FIXED |
| 10 | 15 unused dependencies, incl. all of Firebase | **Medium** | FIXED |
| 11 | 54 unused imports across five screens | **Low** | FIXED |
| 12 | Duplicated SRS colour mapping, drifting | **Low** | FIXED |
| 13 | `PinyinAnalyzer` violated its own documented invariant | **Low** | FIXED |
| 14 | "1 words" in a notification | **Low** | FIXED |
| 15 | Filter chip was 42dp while a comment claimed 48dp | **Low** | FIXED |
| 16 | `MainViewModel` has no test and no seam to write one | **Medium** | **OPEN** |
| 17 | SRS ignores time since last review | **High** | **OPEN** |
| 18 | `processReview` and `recordAnswer` are two transactions | **High** | **OPEN** |
| 19 | Release build cannot be verified here | **High** | **OPEN** |

---

## 2. Fixed

### 2.1 The study day was computed two different ways — Critical

`SrsRepository` bucketed a review with `floorDiv(millis, 86_400_000)`, which is UTC.
`DashboardRepository` converted through the learner's `ZoneId`. Outside UTC they are not the
same function, so a review answered between local midnight and 00:00 UTC was written into one
day's row and read back out of another's. The learner studied, the answers were saved, and the
dashboard showed nothing.

**Why it survived a green suite:** `DashboardRepositoryTest` pinned `zone = ZoneOffset.UTC`,
where the two implementations are provably identical. A test that cannot fail for the reason it
appears to cover is worse than no test, because it is read as evidence.

A false KDoc sat on top of it asserting "the two must agree" — the exact claim nobody had
checked.

**Fixed** by extracting `data/srs/StudyDay.kt` (`epochDayOf`, `startOfEpochDayMillis`,
`endOfEpochDayMillis`). Both repositories now take a `zone: ZoneId` and delegate. The false
comment is replaced by a description of what went wrong.

**Held by** `StudyDayTest` (5 tests), which is the test the old suite should have had. Every
boundary case is in a zone with a non-zero offset, in **both** directions (`Asia/Shanghai`,
UTC+8, whose day starts before the UTC one; `America/Chicago`, UTC−6, which does not), and
includes a daylight-saving case: 8 March 2026 is 23 hours long in Chicago, so a day boundary
computed as `epochDay * 86_400_000` is 86,400,000 ms out and an hour of "due today" is silently
included or dropped. One test asserts the two zones genuinely disagree on a chosen instant, so
the others cannot pass vacuously.

### 2.2 `newWordsIntroduced` was never incremented — High

The column existed, was written to `daily_stats` on every review, was selected by
`DailyStatDao.observeDay`, and was surfaced on the dashboard — and nothing ever incremented it.
`saveNewWordWithInitialSrs` enrolled the word and returned. The figure was permanently `0` for
every learner on every day, and no test could catch that, because every assertion about it would
have been written against the value the code produced.

**Fixed** inside the same transaction as the enrolment: read the day's row, upsert with `+1`.
Two separate writes would mean a crash between them leaves a word enrolled and uncounted, and
nothing re-derives the daily row. The word and its count are one fact.

**Held by** `NewWordsIntroducedTest` (4 tests), which asserts per-save with the offending word
named, checks that two learners on one device do not share a counter, and pins the inverse: a
day with no words must have *no row*, not a row of zeroes, because `observeDay` exists to
distinguish "no activity yet" from "activity".

### 2.3 A login revealed whether an account existed — High

```
login("nobody@example.com", "x")  ->  "No account with that email"
login("real@example.com",   "x")  ->  "Incorrect password"
```

Given a list of candidate addresses that difference alone enumerates the registered ones, with
no password guessing. The backoff *was* symmetric — `an unknown identifier is throttled like a
wrong password` had been true all along — but a delay is only an oracle if the two responses are
distinguishable, and the response a caller reads is the message, not the elapsed time. The
symmetric delay did nothing.

**Fixed** with one `internal const val AUTH_FAILED_MESSAGE` used by both branches, so the parity
is structural and cannot drift by someone editing one site.

**Held by** two new tests in `UserSessionTest`, asserting the *sentence* rather than the timing:
that an unknown account and a wrong password are refused in identical words, and that no
authentication failure distinguishes its cause (unknown account, wrong password, empty
password, phone-number identifier).

### 2.4 A read failure told the learner their vocabulary was empty — High

`userWords` degrades an unreadable database to an empty list, which is the only honest
degradation available on a `List` flow — but an empty list is rendered as *a starting point*. A
corrupt database, a full disk, a revoked permission, and "you have no words" all rendered as the
same screen, so a learner with 400 saved words was told their library was "ready for its first
word" and invited to type them all in again. The title bar said `Vocabulary Library (0)`, which
is not a neutral placeholder — it is a claim.

`HomeScreen` had the same shape with no guard at all, and no `wordsLoaded` check either, so it
told a learner with a full library that they had nothing in their collection on every app open.

**Fixed** with `libraryError: StateFlow<String?>` on the view model, set by the existing `.catch`
and cleared by an `onEach` ahead of it (without the clear it is a latch never unset, which is
worse than not having it). Both screens now distinguish three states. The message is a fixed
sentence, never `throwable.message`: a Room or SQLite message is a `SQLITE_` code or an absolute
file path, and the second discloses where the learner's data lives.

### 2.5 `finishSession` could kill the process — High

`finishSession` launched a coroutine containing four unguarded database calls — `session.await()`,
`sessionRepository.end`, `summariseSession`, `evaluateAwards` — with no `try`. An uncaught
failure in `launch` reaches the `CoroutineExceptionHandler`, and `viewModelScope` has no handler,
so it reaches the thread's default handler and the process ends.

The subtle part: `activeSession` holds an `async`, which *defers* its failure to whoever awaits
it. The two look interchangeable and behave differently, which is why this survived review.

**Fixed** with a `try`/`catch` that clears the summary and surfaces a fixed sentence. The
session row is already committed when a failure can land, so the honest outcome is no summary
rather than a wrong one — the answers are safe in `review_log` and count next time.

### 2.6 Ease was truncated — Medium

`(newEase * 100.0).toInt() / 100.0` truncates toward zero, so any product landing a hair below
its intended value loses up to 0.01. Binary floating point makes that routine rather than exotic.
Ease is the one scheduler input that compounds — it multiplies every later interval — so a 0.01
loss on the first AGAIN is still there after a hundred reviews.

**Confirmed empirically, not by reading.** `SrsEaseRoundingTest` was written first and failed
with `expected:<2.3> but was:<2.29>`. (Worth recording: PowerShell's `[int]` cast rounds,
banker's style, and does not model Java's truncating cast. A shell check would have given the
wrong answer here.) **Fixed** with `Math.round(newEase * 100.0) / 100.0`.

### 2.7 The daily new-word limit was reported but never enforced — Medium

A consequence of §2.2, and a finding in its own right. The allowance is computed as
`limit - newWordsIntroduced`, and `newWordsIntroduced` was always zero, so the allowance was
always the full limit. A learner could add a hundred words in one morning and the dashboard
would keep offering the full daily allowance.

**Now enforced**, and **held by** `the daily new word allowance is consumed by words added today`,
which asserts the remaining allowance after each of four additions against a limit of three.

### 2.8 `SrsAlgorithm` read a wall clock it was never given — Medium

`calculateNextReview` defaulted `now` to `System.currentTimeMillis()`, so a supposedly pure
state machine read the ambient clock. The two callers that relied on the default were in
`SrsCardStateContractTest`, walking 120 schedules against a time that moved underneath them — a
contract test on the scheduler's bounds that could pass at 23:59 and fail at 00:01.

**Fixed** by making `now` mandatory. The compiler then located both impure call sites on its
own, which is the point of the change. `SrsCardStateContractTest` now passes a fixed
`FIXED_NOW`, so the walk means the same thing on every run.

### 2.9 The due-words rule was written twice, in two spellings — Medium

```
MainActivity.kt:250          dueWords.value.ifEmpty { userWords.value }
SwipeDeckReviewScreen.kt:144 if (dueWords.isNotEmpty()) dueWords else allWords
```

Both read `StateFlow.value` off the view model from a composable-scope lambda, so the decision
about which cards a learner is shown lived outside the thing that owns the deck. Any change to
it — capping the sitting, preferring learning cards to due ones — had two places to be made, one
of them a navigation callback that no test reaches.

Reading `.value` there was also quietly wrong: a `stateIn` flow's `value` is whatever the last
emission held, which on a cold start is the initial `emptyList()`, so it resolved to "the whole
collection" for a learner whose due words had not been queried yet.

**Fixed** with a private `wordsForReview()` on the view model, a no-argument
`startReviewSession()` for the navigation layer, and a no-argument `ensureReviewSession()`.
`restartReviewSession` is left alone: it queries fresh and deliberately does *not* fall back,
which is a different rule.

### 2.10 Fifteen unused dependencies, including all of Firebase — Medium

Every one grep-verified as zero usages in `app/src` before removal: `firebase-bom`, `firebase.ai`,
`firebase.appcheck.recaptcha`, `converter-moshi`, `moshi.kotlin`, `okhttp.logging.interceptor`,
`retrofit`, `androidx.compose.material.icons.core` (subsumed by `.extended`),
`androidx.compose.ui.tooling.preview`, `debugImplementation(compose.ui.tooling)`,
`espresso.core`, `roborazzi.junit.rule`, a duplicate androidTest Compose BOM, the
`ksp(moshi.kotlin.codegen)` processor, the `google-services` plugin alias, the `googleServices {}`
block, and `ignoreList.add("FIREBASE_APPCHECK_DEBUG_TOKEN")`. Thirteen commented-out speculative
coordinates and the note saying "keep them commented so they're easy to add back" are gone,
replaced by a comment recording that they were removed and why.

### 2.11 Unused imports, duplicated colour mapping, and a chip 6dp too small

- **54 unused imports** across five screens, removed. `getValue`/`setValue` were excluded from
  the sweep: they are operator imports backing `by` delegation, and removing them fails to
  compile in a way that does not name the cause.
- **SRS state → colour** was mapped twice, in two files, and the `LibraryScreen` copy had an
  `else -> SrsAgainDark` that silently rendered an unreadable state as "needs review" rather
  than as unreadable. Collapsed into `srsStateColor` (exhaustive, no `else`) and
  `srsStateColorOrNull`, which returns null and is rendered as `TextMuted`.
- **The filter chip was ~42dp** while a comment three lines below claimed 48dp. The
  `.selectable` half of a touch-target fix had landed and the height half had not; the
  `minimumTouchTarget` import was present the whole time, unreferenced. Rather than delete the
  import and correct the comment, the fix was finished.
- **`PinyinAnalyzer.analyze("")` returned `toneContour = ""`**, violating an invariant its own
  field documents ("Always populated… so the drawing code does not have to special-case"). Fixed
  to the neutral contour, and the main path's `.orEmpty()` — a latent second instance, currently
  unreachable because `TONE_MARKS` only defines tones 1–4 — routed through one total accessor.
- **"You have 1 words"** in `NotificationHelper`.
- **`"-"` versus `"—"`**: the no-accuracy glyph in `DashboardContent` and in `ProgressContent`
  were different characters, so the shared constant's KDoc was untrue. They are one constant now.
- **`DashboardLoading`/`DashboardError` hardcoded the word "progress"** while also serving the
  dashboard, so a failed dashboard read said "Progress could not be loaded". They take a label.
- **`SrsRatingButton` took an unused `label` parameter** while `SrsRating.label` — the real
  source — had no production reader at all.
- **"Session complete" appeared twice** in the same card, 22sp and 24dp apart. The duplicate is
  gone; the only caller already printed a larger one.

### 2.12 Test and file hygiene

- `ExampleUnitTest.kt` → `SrsAlgorithmTest.kt`. The file declared `class SrsAlgorithmTest`; only
  the filename was a template fossil.
- `SrsCardStateContractTest` now pins `FIXED_NOW` (§2.8).
- A KDoc in `SrsAlgorithm` cited `SrsAlgorithmBoundsTest`; the real file is
  `SrsAlgorithmLimitsTest`. A comment naming a test that does not exist sends the next person
  looking for it.

---

## 3. Reviewed and deliberately left alone

Recorded because "we looked and it is fine" is a result, and because the next reader will
otherwise re-open the question.

**`SwipeDeckReviewScreen.kt:993` — `if (studied && summary != null)`.** The compiler warns that
the condition is always true. It is, and the explicit null check is what allows `summary` to be
smart-cast at line 997. Removing it does not simplify anything; it breaks the build. Load-bearing
as written.

**`latestAiRequest` is not atomic, and does not need to be.** Every increment
(`generateWord`, `useOfflineSampleFor`) is reached from Compose UI callbacks, so it runs on the
main thread, and the read that compares against it is inside `viewModelScope` — also the main
thread. `++` on a main-thread-confined field is not a data race, and `AtomicLong` would change
nothing observable while adding a field type to reason about. The existing KDoc already explains
the ordering that makes it correct.

**`SrsCalculationResult.state` stays a `String`.** Switching it to `StorageValues.CardState`
would be tidier and is not worth the migration risk to a value written straight into a database
column. The literals now reference `StorageValues.CardState.*.storageValue` at their four
definition sites, and `SrsCardStateContractTest` still guards the edge.

**`SrsRating.description` has no production reader.** Left: it is data, not logic, and it is the
kind of thing the next screen to show rating descriptions needs. Removing it would be tidiness.

**`SrsDeckState`'s members with no external reader are not dead.** `canRate` is read by
`beginRating`, `answersGiven` by `completeRating` — both *inside* the class. The screen is not
the state machine's only legitimate reader. What *was* wrong is a KDoc listing `answersGiven`
among "the screen now reads", which grep shows it does not; that sentence is corrected.

**`MainViewModel` is large.** Roughly 1,200 lines, and it is large because it is the only
lifecycle-bearing object in the app and everything crosses it. Splitting it would mean splitting
the deck, the session, auth, progress, gamification and AI across several classes that then
share all of that state through injected collaborators — a large refactor whose failure mode is
subtle behavioural drift, bought for file length. Not justified by size alone.

**猫 and 喝 stroke data are left as they are.** They are wrong in a way that is visible on
screen, and correcting them means inventing stroke breakdowns. Inventing linguistic data is
worse than an acknowledged error, so this is flagged for a human with the sources to hand.

---

## 4. Open

Each of these was found, understood, and deliberately not changed. None is speculative: all are
located and sized.

### 4.1 `MainViewModel` has no test, and no seam to write one — the largest gap

The class holds the auth gate, the seeding gate, the rating-claim guard, write-failure recovery,
`skipVanishedCards`, the AI request token and the session lifecycle. All of it is
compile-verified and reasoned about; none of it is executed by a test.

It cannot be tested as it stands. The repositories are `private val` fields constructed inline
from `AppDatabase.getDatabase(application)`, so a test cannot substitute one that throws — which
is exactly the substitute §2.5 and §2.11 need. The comment that made them private says so
plainly: "the visibility was not buying a seam; it was an invitation to skip the ViewModel."

**What it needs:** a constructor seam. A secondary `constructor(application: Application)` must
remain a real one-argument constructor, because `AndroidViewModelFactory` finds it by reflection
over the exact parameter list and a Kotlin default argument does not produce one. A primary
constructor taking a `Dependencies` holder, plus that secondary constructor delegating to it, is
the shape that works.

**Then:** a fake `SrsRepository` that throws, pinning §2.5 end to end; and the double-tap guard
asserted above `ReviewDeckState` rather than only inside it.

### 4.2 The SRS ignores time since the last review — High

`calculateNextReview` never reads `lastReviewMillis` or the gap to `dueDateMillis`. A card due
three weeks ago is scheduled exactly as one due an hour ago. `review_log.elapsedMillis` is
recorded on every answer and read by nothing.

This is a real gap in the scheduler's model, and it is **not** the "write a test for correct
behaviour" kind of task: there is no correct behaviour to assert yet, because the rule does not
exist. It needs a design decision (what is the penalty, is it per-card or global, does it cap or
multiply, what happens to a card that has been absent for a year) and then a v4 migration to
carry the data. Writing a test first would be writing down a guess.

### 4.3 `processReview` and `recordAnswer` are two transactions — High

A rating is applied to `srs_state` in one transaction and the session/answer bookkeeping in
another. A crash between them leaves a card rescheduled and a session that does not count it —
so XP was paid, the summary is short, and the streak and the daily roll-up disagree with the
history. Both are inside the same repository and can be one `withTransaction`.

### 4.4 The release build cannot be verified in this checkout — High

`isMinifyEnabled = false` and `isShrinkResources` is unset, so the release variant ships
unshrunk and un-obfuscated. That is a real finding, and it is deliberately deferred: enabling R8
without a populated ProGuard file produces a release build that cannot be tested here, and a
build that cannot be built is worse than one that is merely large.

The blockers are environmental: `my-upload-key.jks` is absent, `KEYSTORE_PATH` is unset, there is
no APK in `app/outputs`, and `proguard-rules.pro` is still the untouched 21-line template. This
needs a machine with the keystore, not a code change.

### 4.5 Smaller, with locations

- **The thrice-duplicated resolve-or-insert sequence** for reference content belongs in a
  `ContentResolver`. `Migration_1_2` has a copy and must keep it — it runs before Room's DAOs
  exist, which is precisely why the duplication is tolerable there and nowhere else.
- **`learning_items`** (table, DAO, entity, two foreign keys) and **`character_strokes`**
  (written by a migration, read by nothing) are dead. Deleting them needs a migration.
- **Three Android Studio template tests** — `GreetingScreenshotTest`, `ExampleRobolectricTest`,
  `ExampleInstrumentedTest` — test nothing.
- **`LearnerProgress` foreign-key columns are unindexed**, so every join for a learner scans.
- **`daily_stats.sessionCount` is never incremented.** It should not be displayed until it is.
- **No cancellation for AI requests.** A learner who leaves the screen leaves a request running.
- **`AiGenerationState.Error` loses the failure type**, so "no network" and "rate limited" reach
  the learner as one indistinguishable string.
- **Rating buttons are not disabled while a write is in flight.** The `beginRating` guard makes
  a double tap a no-op; it does not make the button look inert, so it looks broken.
- **Scroll position is not restored across rotation**, and there is no autofill configuration on
  the sign-in form.
- **`seedUserData` refusals are only logged**, so a partially seeded learner has no indication
  of it.

---

## 5. How this was verified

- `:app:testDebugUnitTest` — full suite, run after every change that could affect it.
- Compile errors were used as evidence, not just as obstacles: removing the `now` default found
  the two impure call sites, and removing `SrsRatingButton`'s `label` found its only caller.
- Every claim of "unused" was grep-verified against `app/src` before removing anything,
  including the import sweep, which excludes `getValue`/`setValue` for the reason above.
- The ease-truncation finding was reproduced as a failing test before it was fixed, because
  "2.5 − 0.20 is not 2.3" is a claim about a particular pair of doubles, not about the code.
- Three `DashboardRepositoryTest` failures after §2.2 were investigated rather than adjusted. Two
  asserted `today == null` as a proxy for "no reviews today" and were rewritten to assert the
  count; one asserted a daily limit that had never been enforced. All three had encoded the bug
  as the expected result. The first is a case worth naming: fixing a bug the tests were written
  against produces failures that look like regressions and are actually the bug being fixed.

## 6. A note on the test suite

Three tests written in this pass were themselves wrong on first run, and were fixed rather than
weakened:

- `SrsEaseRoundingTest` compared against a chain that started one step before the first result,
  so it reported a clean-looking truncation cascade that was the test being off by one.
- `UserSessionTest`'s "wrong password" case passed the *correct* password.
- The three `DashboardRepositoryTest` failures above.

Recording this because a test that fails for the wrong reason and is then adjusted is how suites
decay into asserting whatever the code does, which is the condition this whole pass exists to
undo.
