# Phase C Implementation Plan — HanziSRS Chinese Learning Platform

## 1. Repository state (verified 2026-10-20)

- **Branch:** `main`
- **Latest commit:** `b478828` — Phase B3: Expand offline dictionary to 507 HSK 1 entries
- **Working tree:** Clean (only unrelated `Your-companion-app-for-learning-Chinese-on-Android-/`)
- **minSdk:** 24 (Android 7.0)
- **targetSdk/compileSdk:** 36 (Android 16)
- **Baseline tests:** All pass (BUILD SUCCESSFUL)

## 2. Existing architecture (verified from code)

### Navigation (MainActivity.kt)
- Bottom nav: Home, Learn (Library), Progress, Profile
- Additional screens: Add Word, Deck (Review), Random Review, Auth
- NavHost with shared easing transitions
- Deep link support via `navigate_to` intent extra

### Screens
| Screen | Purpose |
|--------|---------|
| HomeScreen | Dashboard: due count, random review entry, quick actions, recent vocab |
| LibraryScreen | Word list with search/filter, word detail bottom sheet with InteractiveStrokeSection |
| ProgressScreen | Level, milestones, badges |
| SettingsScreen | Profile, theme, logout |
| AddWordScreen | AI generation + offline sample + manual entry + review/approve form |
| SwipeDeckReviewScreen | Flashcard review with swipe, 4-pillar tabs (Writing, Meaning, Context) |
| RandomReviewScreen | Speech-based practice, ungraded |
| AuthScreen | Sign in / sign up |

### Data layer
- **Room database:** AppDatabase with entities: User, Word, Character, PinyinSyllable, Vocabulary, ExampleSentence, LearningLevel, LearningItem, UserVocabulary, SrsState, StudySession, SessionCard, Progress, Achievement, UserAchievement, DailyStat, UserSettings
- **Repositories:** WordRepository, SrsRepository, StudySessionRepository, GamificationRepository, DashboardRepository, UserRepository, WordRepository
- **Migrations:** Migration_1_2, Migration_2_3 (safe, non-destructive)

### SRS
- SM-2 algorithm with ease factor, interval, repetitions
- ReviewDeckState machine for card navigation
- SrsRating: AGAIN, HARD, GOOD, EASY
- Due-date based scheduling

### Dictionary
- 507 HSK 1 entries from CC-CEDICT + HSK 3.0
- OfflineDictionary: lazy-loaded, thread-safe, hanzi + pinyin lookup
- PinyinConverter: numbered → tone-marked
- Provenance: IMPORTED source, UNKNOWN/IMPORTED provenance, isVerified=false

### Handwriting
- InteractiveStrokeSection: stroke guide (animated) + practice canvas
- UserTracingCanvas: finger drawing, Clear/Undo, Hide/Show, Hint, Check
- StrokeMatch evaluator: pure Kotlin, geometric comparison
- 11 bundled characters with stroke geometry

### Audio
- PronunciationService with TTS
- UiSoundPlayer with SoundPool (7 sounds)
- SpeechRecognitionManager (offline speech-to-text)

### AI
- GeminiAiService: proxy-based AI word generation
- Offline sample fallback (hardcoded + dictionary)
- Manual entry (Phase A)

## 3. Verified features (must be preserved)

1. **Authentication:** Sign in/sign up with email/phone/anonymous, session persistence
2. **Home dashboard:** Due count, recent vocabulary, quick actions, random review entry
3. **Library:** Search, filter, word detail with stroke practice, provenance display
4. **Add Word:** AI generation, offline sample, manual entry, review/approve form, validation, duplicate handling
5. **Review (SwipeDeck):** Flashcard review, swipe grading, 4-pillar tabs, session completion, summary
6. **Random Review:** Speech-based practice, ungraded, attempt counting
7. **Progress:** Level, milestones, badges, XP
8. **Settings:** Theme (light/dark/system), TTS speed, daily limits, logout
9. **Handwriting:** Stroke guide, practice canvas, hide/show, hint, check, resize-safe
10. **Offline:** All core features work without network
11. **Themes:** Light, dark, system with true-black dark theme

## 4. Phase C1 — Foundation and user experience

### Objective
Establish a reliable baseline and improve navigation/home experience without breaking existing features.

### C1.1 — Baseline verification
- Run all unit tests and debug builds
- Document any existing failures
- Create this plan document

### C1.2 — Home dashboard polish
- Improve due-review visibility (already has badge on nav)
- Add "weak words" summary (words with low accuracy)
- Add daily goal progress indicator
- Ensure all statistics are derived from real data

### C1.3 — Navigation coherence
- Verify all screen transitions work
- Add back-navigation consistency
- Ensure deep links work

### C1.4 — Loading and error states
- Standardize loading indicators across screens
- Standardize error messages
- Add retry buttons where appropriate

### C1.5 — Accessibility
- Verify touch targets (48dp minimum)
- Add content descriptions
- Test with TalkBack

### C1.6 — Smaller screen layouts
- Test on 320dp width
- Verify no clipping or overflow

### Files likely touched
- HomeScreen.kt (dashboard improvements)
- MainActivity.kt (navigation)
- DashboardContent.kt (stats)
- Components (loading, error states)

### Tests
- Existing tests must pass
- Add unit tests for dashboard aggregation
- Add UI tests for loading/error states

### Acceptance criteria
- All existing tests pass
- No new crashes
- Dashboard shows real data
- Navigation works on all screens

## 5. Phase C2 — Vocabulary and SRS

### Objective
Improve the complete path from dictionary search to saved vocabulary and review.

### C2.1 — Vocabulary details
- Improve word detail sheet (LibraryScreen)
- Show provenance badge (AI/dictionary/manual/starter)
- Show HSK level
- Show stroke count (when available)

### C2.2 — Flashcard feedback
- Improve review card readability
- Add swipe gesture hints
- Show interval preview on rating buttons (already exists)

### C2.3 — Weak-word practice
- Identify words with low accuracy or frequent AGAIN ratings
- Add "Practice weak words" entry point on Home
- Filter review deck to weak words

### C2.4 — Due-review visibility
- Show due count prominently
- Add "Review now" button when due > 0
- Show next due time

### C2.5 — Persistence verification
- Add tests for SRS state persistence
- Add tests for session recovery
- Add tests for duplicate prevention

### Files likely touched
- LibraryScreen.kt (detail improvements)
- SwipeDeckReviewScreen.kt (feedback)
- HomeScreen.kt (weak words, due review)
- SrsRepository.kt (weak word query)
- WordRepository.kt (filtering)

### Tests
- Existing tests must pass
- Add unit tests for weak-word identification
- Add integration tests for persistence

### Acceptance criteria
- All existing tests pass
- Weak words correctly identified
- Due review count accurate
- No data loss on app restart

## 6. Phase C3 — Handwriting

### Objective
Improve the existing drawing and evaluation flow.

### C3.1 — Drawing interaction
- Smooth stroke rendering (already good)
- Pressure sensitivity (if supported)
- Undo/redo improvements

### C3.2 — Evaluation feedback
- Improve Check result display
- Add per-stroke guidance
- Show "try again" prompts

### C3.3 — Incomplete attempt handling
- Clear messaging for empty/tiny/mismatched attempts
- Guide user to complete all strokes

### C3.4 — Tests
- Add tests for incomplete attempts
- Add tests for stroke-count mismatch
- Add tests for ambiguous alignment

### Files likely touched
- StrokeOrderCanvas.kt (UI improvements)
- StrokeMatch.kt (evaluation improvements)
- TracePracticeTest.kt (new tests)

### Tests
- Existing tests must pass
- Add unit tests for edge cases

### Acceptance criteria
- All existing tests pass
- Evaluation feedback is clear and honest
- No false "correct" claims

## 7. Phase C4 — Pronunciation and listening

### Objective
Implement the smallest reliable audio feature supported by current architecture.

### C4.1 — Audio inventory
- Inspect available audio assets
- Check TTS availability
- Document what's available

### C4.2 — Listening exercises
- Add "Listen and repeat" exercise (if audio available)
- Add tone recognition exercise (if tone data available)
- Graceful degradation when audio unavailable

### C4.3 — Tests
- Add tests for audio playback
- Add tests for exercise logic

### Files likely touched
- PronunciationService.kt
- New exercise screens
- MainViewModel.kt (exercise state)

### Tests
- Existing tests must pass
- Add unit tests for exercise logic

### Acceptance criteria
- All existing tests pass
- Audio plays correctly when available
- Graceful degradation when unavailable

## 8. Phase C5 — Sentences and grammar

### Objective
Introduce structured lessons and exercises using verified content.

### C5.1 — Data model
- Create Lesson entity (title, description, level)
- Create Sentence entity (chinese, pinyin, english, provenance)
- Create Exercise entity (type, question, answer, provenance)

### C5.2 — Content
- Add verified sentences (from CC-CEDICT examples or curated)
- Link sentences to vocabulary
- Track provenance

### C5.3 — Exercises
- Word order exercises
- Fill-in-the-blank
- Translation exercises

### C5.4 — UI
- Lesson list screen
- Lesson detail screen
- Exercise screen

### C5.5 — Tests
- Add unit tests for lesson/exercise logic
- Add integration tests for persistence

### Files likely touched
- New entity files
- New DAO files
- New repository files
- New screen files
- MainViewModel.kt (lesson state)
- MainActivity.kt (navigation)

### Tests
- Existing tests must pass
- Add unit tests for new logic

### Acceptance criteria
- All existing tests pass
- Lessons load correctly
- Exercises work
- No fabricated content

## 9. Phase C6 — Personal learning path

### Objective
Add daily goals, streaks, achievements, and personalization.

### C6.1 — Daily goals
- Configurable daily word/review goals
- Progress tracking
- Goal completion celebration

### C6.2 — Streaks
- Consecutive day tracking
- Streak display on home
- Streak preservation on missed days (no penalty)

### C6.3 — Achievements
- Badge system (already partially implemented)
- Achievement unlock notifications
- Achievement display

### C6.4 — Personalization
- Learning path recommendations
- Weak word focus
- Adaptive difficulty

### C6.5 — Tests
- Add unit tests for streak calculation
- Add unit tests for goal tracking
- Add integration tests for persistence

### Files likely touched
- ProgressEngine.kt (streak, goals)
- GamificationRepository.kt (achievements)
- HomeScreen.kt (goals, streak display)
- SettingsScreen.kt (goal configuration)
- MainViewModel.kt (goal state)

### Tests
- Existing tests must pass
- Add unit tests for new logic

### Acceptance criteria
- All existing tests pass
- Goals track correctly
- Streaks calculate correctly
- No data loss

## 10. Phase C7 — Stabilization

### Objective
Perform regression, migration, offline, accessibility, and performance checks.

### C7.1 — Regression testing
- Run all unit tests
- Run all instrumented tests
- Manual testing on device

### C7.2 — Migration testing
- Test database migrations
- Test data preservation
- Test backward compatibility

### C7.3 — Offline testing
- Test all features offline
- Test network failure handling
- Test offline dictionary

### C7.4 — Accessibility testing
- Test with TalkBack
- Test with large text
- Test with high contrast

### C7.5 — Performance testing
- Test startup time
- Test memory usage
- Test battery impact

### C7.6 — Documentation
- Update README
- Update architecture docs
- Update test documentation

### C7.7 — Release preparation
- Create release build
- Test on multiple devices
- Prepare release notes

## 11. Data migration risks

1. **Schema changes:** Any new entity requires a Room migration. Use non-destructive migrations (ALTER TABLE ADD COLUMN).
2. **Data preservation:** Existing user data must be preserved. Test migrations with real data.
3. **Backward compatibility:** New fields must have defaults. Old apps must still work.
4. **Rollback:** Migrations must be reversible. Test rollback scenarios.

## 12. Test strategy

### Unit tests
- Pure logic tests (no Android dependencies)
- Repository tests (with in-memory Room database)
- ViewModel tests (with mocked dependencies)

### Integration tests
- End-to-end flow tests
- Database migration tests
- Offline behavior tests

### UI tests
- Compose UI tests (Robolectric)
- Screenshot tests (Roborazzi)
- Accessibility tests

### Manual tests
- Device testing on multiple screen sizes
- Network failure testing
- Performance testing

## 13. Known limitations

1. **Audio:** No bundled audio assets. TTS depends on device capabilities.
2. **Stroke data:** Only 11 characters have verified stroke geometry.
3. **Dictionary:** Only HSK 1 (507 entries). Higher levels not included.
4. **AI:** Requires operator-run proxy. Not available in all builds.
5. **Offline speech recognition:** Depends on device capabilities.

## 14. Deferred features

1. **Cloud sync:** Requires backend infrastructure.
2. **Social features:** Requires user accounts and networking.
3. **Subscriptions:** Requires payment processing.
4. **Analytics:** Requires privacy review.
5. **Advanced grammar:** Requires curated content.

## 15. Phase C1 implementation

### Immediate next steps

1. Create this plan document ✅
2. Run baseline tests ✅ (all pass)
3. Implement dashboard improvements
4. Add weak-word identification
5. Add daily goal tracking
6. Improve navigation
7. Add accessibility improvements
8. Test on smaller screens

### C1.1 — Dashboard improvements

**Files to modify:**
- `HomeScreen.kt` — add weak words summary, daily goal progress
- `DashboardContent.kt` — add new stat cards
- `DashboardAggregator.kt` — add weak word count, goal progress

**New components:**
- `WeakWordsCard` — shows count of weak words + "Practice" button
- `DailyGoalCard` — shows progress toward daily goal
- `StreakCard` — shows current streak

**Tests:**
- Add unit tests for weak word identification
- Add unit tests for daily goal tracking
- Add UI tests for new cards

### C1.2 — Weak word identification

**Algorithm:**
- A word is "weak" if:
  - Accuracy < 70% (from review log)
  - OR has 3+ "AGAIN" ratings in last 10 reviews
  - OR not reviewed in 30+ days (and was reviewed before)

**Implementation:**
- Add `getWeakWords(userId)` to `WordRepository`
- Add `weakWordCount` to `DashboardSnapshot`
- Add "Practice weak words" button on Home

### C1.3 — Daily goal tracking

**Implementation:**
- Add `dailyGoal` to `UserSettings` (default: 10 words)
- Add `DailyGoalProgress` to `DashboardSnapshot`
- Track progress via `daily_stats` table
- Show progress bar on Home

### C1.4 — Navigation improvements

**Implementation:**
- Verify all screen transitions
- Add back navigation consistency
- Test deep links

### C1.5 — Accessibility

**Implementation:**
- Verify 48dp touch targets
- Add content descriptions
- Test with TalkBack

### C1.6 — Smaller screen layouts

**Implementation:**
- Test on 320dp width
- Fix any clipping or overflow
- Use responsive layouts

---

## Phase C1 acceptance criteria

- [ ] All existing tests pass
- [ ] No new crashes
- [ ] Dashboard shows real data (weak words, daily goal)
- [ ] Navigation works on all screens
- [ ] Touch targets ≥ 48dp
- [ ] No clipping on 320dp screens
- [ ] Accessibility labels present

## Phase C1 verification gate

Before proceeding to C2:
1. Run all unit tests ✅ (baseline passes)
2. Run debug builds ✅
3. Review diff for unintended changes
4. Verify no data loss
5. Report any issues

---

## Status

- **Phase C1:** In progress
- **Phase C2-C7:** Not started
- **Next action:** Implement C1.1-C1.6 incrementally