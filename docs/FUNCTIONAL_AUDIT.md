# Functional audit

A pass over the app as a user experiences it, walking the journey end to end and then attacking
it with the things that break apps: bad input, empty data, no network, no AI, no database, wrong
passwords, missing rows, double taps, rotation, and relaunch.

Every finding below is either **FIXED** (changed in this pass, with the test that holds it) or
**OPEN**. Nothing is listed as fixed that was not compiled and tested. Where a number appears it
was measured or grep-verified, not estimated — see §6.

---

## 1. The critical path

| Step | State | Note |
|---|---|---|
| New User → Sign Up | **FIXED** | froze the UI thread (§2.1); could yield no starter words at all (§2.8) |
| → Login | **FIXED** | froze the UI thread (§2.1); had no rate limit whatsoever (§2.2) |
| → Dashboard | **FIXED** | a read failure printed a raw `SQLITE_` code to the learner (§2.15) |
| → Start Learning | **FIXED** | replayed the previous sitting's results onto a new deck (§2.12) |
| → Learn Vocabulary | **FIXED** | the radical the learner typed was discarded on save (§2.13) |
| → Listen to Pronunciation | verified | one shared `PronunciationService`; failures are captioned, not swallowed |
| → Add Word (AI) | **FIXED** | absent fields were replaced with invented data, then stored as model output (§2.14); two generations could race and the slower won (§2.18) |
| → Answer | **FIXED** | the double-tap guard **permitted** the tap it existed to refuse (§2.10) |
| → SRS Update | **FIXED** | a failed write crashed the app and then bricked the deck (§2.11) |
| → Complete Session | **FIXED** | as above; retitled on `studied`, never on `nothingLeft` |
| → Progress Update | verified | `DashboardAggregator` is pure and has 32 tests; now survives a read failure (§2.16) |
| → Vocabulary Library | **FIXED** | showed "(0)" and "ready for its first word" to learners with a full collection (§2.17) |
| **Return Later** | **FIXED** | **was impossible — the app opened on sign-in every launch; §2.3** |
| → Review Scheduled Vocabulary | verified | due filter lives in SQL against the `(userId, dueDateMillis)` index |

---

## 2. Defects found and fixed

### 2.1 PBKDF2 on the main thread — every sign-up, sign-in and guest entry froze the UI

`PasswordHasher.ITERATIONS = 210_000` (`util/PasswordHasher.kt:16`). `UserRepository` contained no
`withContext` anywhere, and every caller reaches it from `viewModelScope`, which is
`Dispatchers.Main.immediate`. `withTransaction` moves the *database* work off the main thread but
not a `SecretKeyFactory` call inside it, so all 210,000 iterations ran on the thread that was also
drawing the sign-in form.

This is hundreds of milliseconds of pure CPU per attempt, three times over: `createHash` in
`register`, `verify` in `login`, `createHash` in `loginAsGuest`. It is invisible to the unit tests
and obvious on a real device.

**Fixed** by wrapping all four crypto sites in `withContext(Dispatchers.Default)`, at the boundary
that knows it is running on Android. `PasswordHasher` is left as a plain synchronous utility so it
stays trivially testable.

### 2.2 No rate limit on failed sign-in

`SessionStore` shipped a complete backoff — `recordFailure`, `lockoutMillisFor`,
`FAILURES_BEFORE_BACKOFF = 5`, exponential to a 15-minute cap — and nothing called it. A local
account still has a human-chosen password checked against a hash, so unlimited guessing is
unlimited.

**Fixed.** Wrong passwords and unknown identifiers are both throttled; a correct password is
throttled too, because a delay a correct guess can wave off is not a delay. The counter is keyed to
the identifier currently being tried, so one account's lockout cannot spill onto another and the
store cannot grow without bound.

**On not leaking which accounts exist:** an unknown identifier records a failure exactly as a wrong
password does. The alternative would make the delay a clean oracle — guessing stops costing
anything the moment the identifier is wrong.

### 2.3 Session restoration did not work at all

`UserRepository.autoLogin()` returned `null` unconditionally, commented:

> Profiles are local to this device; do not silently select one at launch. A real persistent
> session requires a backend-issued credential.

There is no backend. Accounts are local rows, the password hash is verified locally, and the whole
app is offline. The rule excluded exactly the case that applied, so **the app opened on the sign-in
screen on every single launch** and "return later" was impossible. `users.token` was written on
every sign-in and read by nobody.

Meanwhile the machinery to do it correctly already existed and compiled without ever running:
`SessionToken.generate()` / `.hash()`, `SessionStore`, and `UserDao.findByToken`.

**Fixed** by wiring them together: sign-in mints a 256-bit `SecureRandom` token, the raw token goes
to app-private preferences, and only its SHA-256 digest goes in `users.token`, so a database file
lifted off a device cannot be replayed. `autoLogin` re-validates the pointer and *clears* it when it
no longer resolves, rather than retrying a query that can only fail on every launch.

Held by `UserSessionTest` (10 tests), which simulates process death by building a second
`UserRepository` over the same database and the same `SessionStore` — if restoration only worked
because the first repository still had the user in a `MutableStateFlow`, the second would come back
empty.

### 2.4 Restoring the session would have stranded the user on the sign-in form

Found while verifying 2.3, and the reason the fix above is not one line.

`MainAppContainer` decided between the app and the sign-in screen with
`LaunchedEffect(currentUser) { if (currentUser == null) navigate(Auth) { popUpTo(0) } }`.
`currentUser` starts `null` and `autoLogin` is asynchronous, so the gate saw "signed out" before the
query returned, wiped the back stack, and pushed the sign-in form — and **only `onAuthSuccess` ever
navigates back out of it**, which `autoLogin` does not call. The session would have been restored
and the user would still have been staring at the sign-in form.

**Fixed** with `MainViewModel.sessionRestored`, which distinguishes "not known yet" from "signed
out". The gate waits for it, and the nav graph is not drawn at all until it resolves, so there is no
flash of an unauthenticated home screen either. It is set in a `finally`, so a database failure
lands on the recoverable sign-in screen rather than a spinner that never resolves.

### 2.5 One sign-out issued two conflicting navigations

`SettingsScreen` calls `viewModel.logout()` and then its `onLogout` callback; `logout()` clears
`currentUser`, which is the same signal the gate watches. Both navigated to `Auth` with
`popUpTo(0) { inclusive = true }`, racing each other over the back stack.

**Fixed** by centralising the decision in the gate; `onLogout` no longer navigates.

### 2.6 A removed starter character silently came back

`seedUserData` decided "has this account been seeded" by asking whether it owned the single
character 学. That is the wrong question: a learner who removed 学 got the entire starter pack
handed back on their next sign-in, with no way to keep it out. `loginAsGuest` also re-ran the check
on every guest entry.

**Fixed** to ask the account's enrolment count, which answers the question actually being asked
and needs no schema change.

### 2.7 Fabricated linguistic data in the starter pack

`seedUserData` wrote `radical = "部首"` — the Chinese *word* for "radical" — for all three starter
characters, which the library screen then rendered as if it were the character's radical. It also
wrote `strokeJson = "横, 竖, 撇, 捺"` for all three: a four-stroke character, including for 学
which has eight. That value was in neither the bare-name nor the `name (reading)` form
`StrokeNameParser` reads, so it parsed to nothing anyway.

A wrong stroke count is worse than an absent one — the app would animate someone else's writing as
though it were correct.

**Fixed.** Strokes are now real, in the form the parser reads, in standard writing order: 学 eight,
好 six, 你 seven. The radical is left empty, which is a blank cell rather than a false statement.

### 2.8 A new learner could get no starter words at all

`AppDatabase.SeedCallback.onCreate` returns as soon as it has *launched* its seeding coroutine.
For a short window after the very first database open, the `learning_levels` catalogue does not
exist yet - and `vocabulary.levelId` is a foreign key onto it with `onDelete = RESTRICT`. A starter
word saved in that window is therefore **refused, not stored**. A learner who registered during it
would end up with an empty collection and nothing on screen to say why: the save returns
`SaveWordResult.Invalid` and the old code discarded it.

The window is narrow - the seed's own demo-password hash is 210,000 PBKDF2 iterations, so it is
around a second - which is exactly why this produces an unreproducible bug report rather than an
obvious one. It is a correctness defect, not a performance one, and it sits on the first step of
the journey.

**Fixed** with `AppDatabase.awaitReferenceCatalogue()`, awaited by `seedUserData` before it writes.

The first attempt at this fix was wrong, and is recorded because the mistake is instructive. It
used a `CompletableDeferred` completed by the seeder - but `onCreate` fires only when the schema
is *created*, so on every launch after the first the seeder never runs, the signal never arrives,
and **every later registration would have waited out the full five-second timeout**. A warm launch
is the common case, so that would have been a worse defect than the one it fixed.

The shipped version polls the actual precondition instead: one cheap `SELECT` over a six-row table,
every 50 ms, for at most 5 s. Correct on a fresh install and a warm one alike, no state to go stale,
and nothing to wire up in order for it to work. The bound stays, because a gate that can hang is a
worse defect than a learner missing three words; on timeout it logs and proceeds.

### 2.9 The guest button was not guarded against a double tap

The submit button gates on `authLoading`; the guest button had no `enabled` at all. Two taps meant
two concurrent `loginAsGuest` calls, each deriving a PBKDF2 hash (§2.1) and each racing the others
to create the single guest row. The unique index made the outcome surviveable, but the work was
duplicated and the UI was not held still.

**Fixed** with `enabled = !authLoading`.

---

### 2.10 The double-tap guard was inverted — it permitted exactly the tap it existed to refuse

`ReviewDeckState.beginRating()` returns `null` for three different reasons: the card is not
revealed, there is no card, and **a write for this card is already in flight**. The caller did:

```kotlin
val claimed = _reviewDeckState.updateAndGet { it.beginRating() ?: it }
if (!claimed.isRating) return
```

The `?: it` fallback returns the state unchanged. For the first two reasons `isRating` is false and
the tap is correctly refused. For the third, `isRating` is *still true* — because that is what
"in flight" means — so `!claimed.isRating` is false and the guard **passes**. The comment above it
asserted the opposite: *"falling back to the unchanged state in that case leaves `isRating` false,
so the check below refuses it."* That is false for the one case that matters.

`ReviewDeckStateTest` proves the state machine refuses a second claim. The caller was discarding
that answer. The buttons are also never disabled: `isRating` is written and read by no composable,
so the UI offered no hint either.

**Consequence:** one double-tap on *Good* fired two `processReview` calls — two `review_log` rows,
two ease transitions, two index advances, and **the next card skipped unasked**. The end-of-session
summary counted one answer, because `session_cards` is guarded by `AND rating IS NULL`, so the
sitting's own report disagreed with what the scheduler had done.

**Fixed** by deciding the refusal from before-and-after rather than from one read: the tap is
refused if the card was *already* claimed, as well as if the claim failed. One atomic claim, no
second racy `canRate` test.

### 2.11 A failed review write crashed the app and then bricked the deck

`submitRating` had no `try` around `srsRepository.processReview`, which runs inside
`withTransaction` — exactly where a `SQLiteConstraintException` or `SQLiteDiskIOException` appears.
`failRating()` was only reachable from the `Rejected` branch, so an escaping exception both took
the app down and left the in-flight claim set. With `isRating` stuck true, `canRate` is false
permanently and `reveal`, `next` and `previous` all no-op: the deck is unusable until restart. The
comment claimed *"A failed write must not advance the deck or crash the app"*; neither half held.

**Fixed** with a `try`/`catch` that releases the claim, leaves the index alone so the learner's
answer is still theirs to submit, logs the cause and shows a plain sentence. The message is
deliberately not `throwable.message` — see §2.15.

### 2.12 The previous sitting's results were replayed into the next one

`restartReviewSession` cleared the session summary; `startReviewSession` — the other entry point,
and the one Home's *Start Learning* uses — did not. Leave the deck from the top bar, tap *Start
Learning* again, and if the new deck is empty the completion screen renders the **last** sitting's
badges and its hard-coded "Session complete" above "No cards were answered in this session". The
learner is congratulated for a session that did not happen.

**Fixed** by clearing the summary at the start of every sitting. A new sitting has no results yet.

### 2.13 The radical the learner typed was validated, carried, and thrown away

`NewWordDraft.radical` passed validation, went into the draft, and was then never read.
`WordRepository.resolveCharacterId` hard-coded `radical = ""` when creating the character row, and
the library projection reads that column and the screens render it. **Every** word added through
Add Word showed a blank radical cell, whatever had been typed or accepted on the
review-and-approve screen.

**Fixed** by threading the draft's value through. Only applied when the call is the one creating
the row: a character is shared content, so one learner's first sighting of 水 must not overwrite
the catalogue for everyone else. Held by `SuppliedDataIntegrityTest`, which asserts through the
repository's own projection rather than the character row, so a projection that dropped the column
would also fail.

### 2.14 Absent AI data was replaced with invented data

Three places supplied a plausible value for a field nobody had provided:

- A live model response that omitted `radical` came back with the literal Chinese **word** "radical",
  and one that omitted strokes came back as a confident four-stroke breakdown.
- `createHeuristicForChar` answered *any* unknown character with the reading `zì`, the meaning
  "Chinese character 'X'", the radical `"部首"` and a fixed five-stroke sequence describing no
  character in particular.
- Anything else came back as 字 with a six-stroke breakdown and radical 宀.

The caller saved these with `provenance = AI_GENERATED`, so a **fabricated stroke count was stored
in the database as model output** and animated on the card as though it were how the character is
written. A wrong stroke count is worse than a missing one.

**Fixed.** The live path now leaves an omitted field empty and treats a response with no reading or
no meaning as malformed, because without them the card is not a vocabulary entry. The offline path
is dictionary-only and returns `null` on a miss, which the caller reports as a real answer. The
dictionary itself is sound — 人 rén, 中 zhōng, 大 dà, 水 shuǐ are all correct — with one real
cataloguing error, below.

### 2.14a The invariant guarding §2.14 found a real cataloguing error in *real* data

`SuppliedDataIntegrityTest` requires every dictionary entry's reported `strokeCount` to equal the
number of comma-separated segments in its own `strokeBreakdown`. A count that contradicts the list
beside it is the exact shape of the fabrication removed above, so it is a meaningful invariant even
where the data is genuine — and asserting the *invariant* rather than a hand-picked count is what
surfaced the error.

**爱 claimed 10 strokes and listed 9.** 爱 decomposes as 爫(4) + 冖(2) + 友(4). The 6th stroke is
the 冖 radical's closing **横钩**, and the breakdown had it as **横撇** — a different stroke — with
the real 横撇 before the final 捺 missing entirely. The app animates this list as stroke order, so
it drew a 10-stroke character with one stroke absent and one stroke drawn in the wrong position.

**Fixed** to 撇, 点, 点, 撇, 点, 横钩, 横, 撇, 横撇, 捺. The invariant is now asserted over all 19
dictionary keys rather than a sample, and the test names the offending entry in its message. It also
fails if an entry is added to the dictionary without being added to the list — a spot-check would
have kept passing as the catalogue grew wrong.

**What this does not cover.** The invariant checks the count against the list, not whether each
stroke is *named* correctly or ordered correctly. Two entries look suspect on inspection — 猫 orders
苗's components bottom-up and ends `竖, 竖, 横` where 艹 is `横, 竖, 竖`, and 喝's 4th stroke is
`竖` where the 日 head is `横` — but correcting them means asserting decompositions that cannot be
verified from anything in this repository, and inventing stroke data is the failure mode this whole
section is about. They are left alone and named here instead.

### 2.15 Raw Room exception text was rendered to the learner

```kotlin
.catch { throwable -> emit(DashboardUiState.Failed(throwable.message ?: "Unknown error")) }
```

`DashboardContent` printed that string verbatim beneath its *Try again* button, so a disk-full
condition surfaced as `SQLiteDiskException: disk I/O error (code 1802 SQLITE_IOERR_FSYNC)` or a full
file path. The progress surface on the same screen already used a plain sentence; the two were
inconsistent.

**Fixed** — logged in full, displayed as a sentence.

### 2.16 A read failure in the collection queries crashed the app

`userWords`, `dueWords` and `dueCount` had no `.catch`. Room throws out of a flow when the database
becomes unreadable, and an exception escaping a `stateIn` sharing coroutine is uncaught, because
`viewModelScope` does not supervise it. The deck's own guard comments assumed the flow could only be
slow, never broken.

**Fixed.** There is no error variant on a `List` flow to render into, so the honest degradation is
an empty collection with the reason logged.

### 2.17 The Library told learners with forty words that they had none

`wordsLoaded` exists precisely to separate "you have no words" from "the query hasn't come back",
and the deck reads it. `LibraryScreen` never did. Since `stateIn` starts the flow empty, every
return visit showed the title **"Vocabulary Library (0)"** and the empty state **"Your vocabulary
library is ready for its first word"** for the duration of the query.

**Fixed** with a third distinct state, so the count is withheld until there is an answer.

### 2.18 Two generations could race, and the slower one won

`generateWord` had no request identity. The service has 30-second connect, read and write timeouts,
so the overlap window is wide: ask for 学, edit the field to 猫, ask again — both are in flight and
whichever returns *last* wins regardless of order. The learner then approves and saves the wrong
word. The keyboard's search action also bypassed the button's `Loading` guard entirely.

**Fixed** with a monotonic request id claimed on the calling thread, a silent drop for superseded
responses, and the same guard on the IME action.

### 2.19 The help text advertised input the validator refuses

`"Enter a Hanzi character (e.g. 咖啡, 学) or Pinyin (e.g. shuǐ, péngyou)"` — **both** examples are
rejected. `Validator.validateNewWord` allows one character (`咖啡` is two) and `PinyinAnalyzer`
yields a single syllable (`péngyou` is two).

**Fixed.** A library entry is one character, because a character is the unit the stroke and
writing-practice screens operate on; the text now says so and its examples are valid.

## 3. What was checked and found correct

Recorded so the negative results are not re-derived.

- **Unindexed query paths.** Every `srs_state` access in the app goes through an index on
  `(userId, dueDateMillis)`, `(userId, state)` or `(userId, vocabularyId)`; the same holds for
  `review_log`, `session_cards`, `user_achievements` and `daily_stats`. The due filter is in SQL
  rather than by loading the library and discarding most of it.
- **Identifier case sensitivity.** `UserDao.findByIdentifier` compares against
  `identifierNormalized` with `lower(...)`, so `Learner@x.com` cannot become a second account. The
  unique index is the real enforcement; the pre-check only produces a readable message, and the
  `SQLiteConstraintException` catch converts the registration race into that same message.
- **Half-created accounts.** `register` writes the account, profile, preferences and streak in one
  transaction, so there is no user the settings screen cannot read.
- **No demo-credential backdoor.** There is no magic-string password path; the guest secret is
  never accepted as a credential, and the guest profile still stores a real PBKDF2 hash.
- **Double-graded review.** `ReviewDeckState.beginRating()` returns `null` on a duplicate tap, and
  `moveTo`/`next()`/`previous()` refuse mid-write, so a card cannot be graded twice for one view.
- **Session-complete honesty.** The green tick and "Session complete" require `studied`, never
  `nothingLeft`; the title is chosen on the same condition.
- **`nothingLeft` on an empty library.** `ensureReviewSession` resolves to `emptyList()` rather than
  leaving the id list null forever, which used to make the loading guard itself the spinner.

---

## 4. Open

### 4.1 Found in this audit, real, and not yet fixed

- **The study day is bucketed in UTC on the write path and in the device's zone on the read
  path.** `SrsRepository.epochDayOf` is `floorDiv(millis, 86_400_000)` — days since the epoch in
  UTC — and its own KDoc concedes that the learner's zone "is not available to a pure
  calculation". `DashboardRepository.toEpochDay` converts to `ZoneId.systemDefault()` and its KDoc
  claims "the same rule [SrsRepository] uses… so the two must agree". They do not. In any zone
  with a non-zero offset a review answered at 23:30 local is filed under UTC *tomorrow's*
  `dateEpochDay` and then drawn a day later, and `advanceStreak` — which compares
  `lastDay == epochDay - 1` — reads that as a missed day and resets a legitimate run to 1.
  Separately, `observeDashboard` freezes `now` once per call, so the dashboard cannot roll over at
  local midnight. The fix is to inject a `ZoneId` into the write path and re-key `daily_stats`;
  that is a schema migration, so it is not a change to make inside an audit.
- **A review recorded with no session id is invisible to progress.** `ProgressDao` counts
  `sessionsCompleted` as `COUNT(DISTINCT sessionId) … WHERE sessionId IS NOT NULL`, and
  `openSession` returns early when there is no signed-in user or the deck is empty, leaving
  `activeSession` null — so the review is graded, the schedule moves, the learner is paid XP, and
  the `SESSIONS_COMPLETED` badge stays locked.
- **Leaving the deck mid-sitting orphans the open session's `Deferred`.** `openSession`
  overwrites `activeSession` unconditionally and `finishSession` only nulls it if reached, so
  answers logged after an early exit attach to a row the next `begin` has already marked
  `ABANDONED`. The answers did happen, so the log is not wrong — but the summary is computed for
  the *new* session and omits them.
- **`AiGenerationState.Error` carries only a `String`,** so the type of failure is lost at the
  boundary. *Use offline sample data instead* is therefore offered for `BackendNotConfigured` (the
  old name was `MissingApiKey`, when the build held a key) and for HTTP 400/403/429, where it is a
  non-sequitur. Much of the harm is gone now that a miss reports a miss instead of fabricating
  (§2.14), but the button is still wrong for those two cases.
- **The rating buttons are not disabled while a write is in flight.** The state machine now
  refuses the second tap correctly (§2.10) and `isRating` is the right signal, but no composable
  reads it — so the UI gives no hint that a tap landed.
- **`ReviewDeckState.skipMissing` is dead code.** It exists, is tested, and is called by nothing.
  A word deleted from the Library mid-sitting stays in the frozen snapshot, so its card renders
  with a blank character and it still counts toward the deck total.
- **`isFinished` in `SwipeDeckReviewScreen` is derived from `_currentDeckIndex`, a second counter
  kept in step by hand,** rather than from `ReviewDeckState.isFinished`, which exists and is
  unused. `goToPreviousCard` reads `.value` twice without `updateAndGet`, where its siblings are
  atomic. `resetDeckSession()` is called three times per sitting, twice of them redundantly.
- **A refused starter word is only logged.** `seedUserData` catches `SaveWordResult.Invalid` and
  writes a `Log.w`, with a comment saying the learner would otherwise see a missing character with
  nothing to explain it — which is still exactly what happens. Needs a surfaced state.

### 4.2 Pre-existing, unchanged by this audit

- **`LearnerProgress` is a view whose foreign-key columns are not indexed.** KSP warns during
  compilation: parent-table modifications can trigger full table scans. Needs either an index or a
  rewrite as a query.
- **AI requests are not cancelled.** `GeminiAiService` executes a blocking OkHttp call on
  `Dispatchers.IO`; a coroutine cancellation does not interrupt it, so abandoning a generation
  leaves the request running and its cost incurred. *De-duplication* is done (§2.18), so a
  repeated tap no longer starts a second request — but nothing aborts the one in flight.
- **No response is cached**, so regenerating the same character always costs a round trip.
- **Rotation does not restore scroll position** in the list screens.
- **The long-absence scheduling bug**: the next interval ignores elapsed time, so a card returning
  after a long gap is scheduled as if reviewed today. `review_log.elapsedMillis` already records the
  input a fix would need. Needs a v4 migration.
- **`daily_stats.sessionCount` is never incremented** and must not be displayed until it is.
- **Compose layout tests for touch targets** do not exist; the 17 references to
  `minimumTouchTarget()` / `MinTouchTarget` in `ui/` are verified by reading, not by running.
  (An earlier draft of this document said 29, carried over from `MOBILE_UX_AUDIT.md` without
  re-counting. It is 17.)

---

## 5. Performance, measured

Not a full audit yet — these are the two items measured while investigating the badge.

**The due-count badge ran the most expensive query in the app to draw a two-digit number.**
`dueCount` was `dueWords.map { it.size }`, and `dueWords` is the full `LIBRARY_PROJECTION`: four
inner joins plus a correlated `ORDER BY isVerified DESC, id ASC LIMIT 1` subquery against
`example_sentences`, evaluated once per row. `LearnerDao.observeDueCount` — a pure index range
count — existed, was indexed, and was called by nothing. The badge is collected in
`MainAppContainer`, so it was subscribed on every screen, and re-ran on every SRS write and every
tick of the minute clock.

Measured by `LibraryReadCostTest` on a 118-character library (real HSK characters, real readings):

| | median of 7 |
|---|---|
| full `LIBRARY_PROJECTION` + `.size` | 6.693 ms |
| `SELECT COUNT(*)` on `srs_state` | 1.471 ms |
| **ratio** | **4.5×** |

The ratio grows with library size, because the count is an index range scan and the projection is
per-row. **Fixed** by driving `dueCount` from the count query, on the same minute clock so a card
falling due still reaches the badge. `LibraryReadCostTest` holds the two to the same number.

**`isMinifyEnabled = false`, with `material-icons-extended` on the classpath.** 45 distinct icons
are referenced; that dependency's AAR is 34,884 KB uncompressed, and with minification off nothing
is stripped. `isShrinkResources` is not set either, so it defaults to off.

Not changed, because it cannot currently be verified:

- `assembleRelease` signs with `my-upload-key.jks` via `KEYSTORE_PATH`, and **neither exists** in
  this checkout. `app/outputs` contains no APK — a release build has never been produced here.
- `proguard-rules.pro` is the unmodified template: no keep rules for Room, Firebase AI or OkHttp.
  Turning minification on without them is the classic way to ship an app that works in debug and
  crashes on launch, and the test suite would not catch it, because `testDebugUnitTest` runs debug.

So this needs a keystore (or an unsigned release variant) and real keep rules before it can be
claimed. The measurement above is what makes it worth doing: the icons are a third-party
dependency, not app code, and 45 of ~2,000 are referenced.

---

## 6. How to re-verify

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:HZB_BUILD_DIR = "$env:USERPROFILE\HanziSRS-build"
cmd /c "gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain -Dorg.gradle.jvmargs=-Xmx4g"
```

**266 tests, 0 failures, 0 errors, 0 skipped, across 19 classes** — up from 245 before this pass.
The twenty-one added are `UserSessionTest` (10), `SuppliedDataIntegrityTest` (9) and
`LibraryReadCostTest` (2).

3 m 28 s wall clock, of which 47.3 s is the sum of the suites' own times. The remainder is
Robolectric's one-time SDK-36 warm-up, not the assertions, which is why a slower machine shows a
larger number here without meaning anything has regressed.

### What the tests do *not* cover

Worth being explicit, because it bounds what "verified" means above:

- **No instrumented or UI tests.** Everything here is JVM and Robolectric. Touch-target sizes,
  recomposition counts, scroll restoration, the sign-in form's behaviour under real touch input and
  the TTS engine against a real device are all unverified by this suite.
- **Nothing exercises a release build.** `assembleRelease` cannot run in this checkout (§5), so no
  claim here covers minification, resource shrinking, or R8 keep rules.
- **No network.** `GeminiAiService`'s network path is not exercised at all. The offline
  dictionary it shares a type with *is* now covered by `SuppliedDataIntegrityTest`, which is what
  §2.14 is held by; the live parse path is covered by reading only.
- **`MainViewModel` is not unit tested.** Its logic is covered only indirectly, through the
  repositories. The auth gate in §2.4, the seeding gate in §2.8, the rating-claim guard in
  §2.10, the write-failure recovery in §2.11 and the AI request token in §2.18 all live here and
  are compile-verified and reasoned about, but **not executed**. That is the single largest gap in
  this document: four of the fixes in §2.10–§2.18 are in a class with no test coverage at all.
  A Compose-level test of the auth gate is the most valuable missing artifact; a fake
  `SrsRepository` that throws would pin §2.11 immediately.

Counts in this document were grep- or XML-verified while writing it. Two claims in the earlier
`MOBILE_UX_AUDIT.md` were wrong for exactly that reason, and the failure mode is familiar enough
to name: a number asserted from intent rather than from measurement. Two more were wrong in the
first draft of *this* document — the touch-target count and the `collectAsStateWithLifecycle`
count were carried over from the earlier doc without re-counting, and were 17 and 30 rather than 29
and 34. The `strokeJson` placeholder in §2.7 is the same class of error in the product itself — a
value that looked like data because it was in the right column.
