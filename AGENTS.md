# AGENTS.md

Working instructions for AI agents (OpenCode and equivalents) on the HanziSRS repository.

---

## 1. Project Context

HanziSRS — Android Chinese-vocabulary trainer.

- **Stack**: Kotlin 2.2.10 · Jetpack Compose + Material 3 (BOM 2024.09.00) · Room 2.7.0 (DB version 3) with KSP 2.3.5 · Navigation Compose 2.8.9 · Coroutines · OkHttp 4.10.0 · AGP 9.1.1. `minSdk 24`, `target/compileSdk 36`.
- **Architecture**: single `:app` module — Compose → `MainViewModel`/`StateFlow` → Repositories → Room. `GeminiAiService` → dependency-free Node proxy (`backend/server.mjs`) → Gemini. The Gemini API key never enters the app binary.
- **Tests**: JUnit 4.13.2 + Robolectric 4.16.1 + Roborazzi 1.59.0 + `compose.ui.test.junit4`.
- **Build**: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"`, then `.\gradlew.bat …`. Build output is redirected **outside** the repo to `%USERPROFILE%\HanziSRS-build` (override with `HZB_BUILD_DIR`) — a OneDrive dexing workaround. Never assume `app/build` holds artifacts.
- **User**: communicates in Arabic; app UI strings are English.

### 1.1 Identity & layout

| Item | Value |
|---|---|
| `applicationId` | `com.hanzisrs.app` — **final**; do not "fix" it. The retired `com.aistudio.hanzisrs.learn` must not come back |
| `namespace` (R class) | `com.example` — **differs** from `applicationId` by design; do not "fix" it |
| `versionCode` / `versionName` | `1` / `1.0` |
| Instrumentation runner | `androidx.test.runner.AndroidJUnitRunner` |
| Source sets | `app/src/main`, `app/src/test` (51 test files, JVM), `app/src/androidTest` (instrumented — currently only `ExampleInstrumentedTest.kt`) |

### 1.2 Build & test commands

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.SomeTest"
.\gradlew.bat :app:assembleDebugAndroidTest
.\gradlew.bat :app:connectedDebugAndroidTest     # needs a device or emulator
```

- **Outputs land outside the repo** at `%USERPROFILE%\HanziSRS-build\app\outputs\…`. APKs are **not** in `app/build`.
- `org.gradle.jvmargs=-Xmx4g`, `org.gradle.workers.max=4`, `org.gradle.configuration-cache=true`, `org.gradle.caching=true` — the daemon is memory-capped on purpose, and **the 4g default is correct: do not raise it.** If the build dies with `Java heap space`, the failure is usually in the **Gradle daemon, not the forked test worker** — see the daemon-OOM item in §3.1 for the verified stack and the fix. Reproduce against the unmodified file before changing any code.
- **Last verified unit-test run (2026-11-16): 51 suites / 578 tests / 0 failures / 0 errors / 0 skipped**, read from freshly written JUnit XML in `%USERPROFILE%\HanziSRS-build\app\test-results\testDebugUnitTest` (`BuildConfigSecretsTest` 5/5 passing). The suite is green **after** the 50-word HSK 2 dictionary expansion, so the old "not re-run since the expansion" caveat is closed. Check the XML timestamps before quoting these numbers — a stale XML from an earlier run is not a fresh pass.
- `app/schemas` is wired to the **`debug`** source set (not `test`): Robolectric reads the *main variant's* merged assets, so the exported Room schemas must exist there or `MigrationTestHelper` fails with `FileNotFoundException` before it asserts anything.

---

## Current Architectural Truths (Source of Truth)

Re-confirmed against the working tree on 2026-11-16. These **override any older note elsewhere** in this file, in `docs/`, or in a skill. Read them before touching build config, Firebase, or the AI backend.

### Package ID

- The official `applicationId` is **`com.hanzisrs.app`**. This is correct and final.
- **Do not "fix" it to any other value.** `com.aistudio.hanzisrs.learn` is the *retired* identifier. The `namespace` (`com.example`) is a separate thing that deliberately differs, and it stays as it is.

### Firebase scope

- Firebase BoM (`com.google.firebase:firebase-bom`) and `app/google-services.json` are present and permitted — for **App Distribution only** as things stand. **Analytics is not active today**: enabling it means adding the Analytics SDK, which is a new Firebase product and therefore a dedicated task with explicit approval.
- **Do NOT add, suggest, or configure the Analytics SDK, Crashlytics, Firebase Authentication, Firestore, or Cloud Functions** unless the user explicitly asks for one in a dedicated task. "Firebase is already a dependency" is **not** a reason to add a second Firebase product.
- Declared in `app/build.gradle.kts`: `platform("com.google.firebase:firebase-bom:35.2.0")`, plus the `com.google.gms.google-services` plugin, whose root `build.gradle.kts` declaration (`id("com.google.gms.google-services") version "4.6.0" apply false`) puts it on the classpath for every module.
- **The plugin is applied conditionally**, on the presence of `app/google-services.json` (`file("google-services.json").exists()`). That file is gitignored, so a fresh clone has no copy — and that clone still builds. Gradle tracks the file's existence as a configuration-cache input, so adding or removing it correctly invalidates the cache entry.
- `app/google-services.json` is **gitignored** (`.gitignore:28`) and must stay that way. It sits outside `src/main`, so the `BuildConfigSecretsTest` `AIza` scan does **not** cover it — the gitignore entry is what protects it. Never commit it; never paste its contents into a report or a log.
- **No Firebase SDK is declared, and the BoM resolves to zero artifacts.** It is a platform constraint only: no `firebase-*` module is requested, so it contributes nothing to the APK. No source file calls a Firebase API and the manifest declares no Firebase component. The catalog still lists `firebase-auth`, `firebase-firestore`, `firebase-ai` and `firebase-appcheck-recaptcha`; those are catalog entries only, not dependencies, and adding one is a dedicated task.

### AI proxy architecture

- `backend/server.mjs` is a **standalone, local Node.js proxy** (`node:http`, `PORT`, reads `GEMINI_API_KEY` from its environment). It has **NOT** been migrated to Firebase Cloud Functions.
- **Keep it independent.** There is no `functions/` directory, no `firebase.json`, no `.firebaserc`, and no `firebase-admin` dependency. Do not scaffold a Cloud Function, and do not point the app at one.
- The app talks to this proxy over plain HTTP; the proxy is the only thing that talks to Gemini.

### Where each truth lives

| Fact | Source of truth |
|---|---|
| `applicationId` | `app/build.gradle.kts` → `defaultConfig` |
| Firebase scope | `app/build.gradle.kts` → `dependencies`, plus `app/google-services.json` |
| Conditional plugin application | `app/build.gradle.kts` → `hasGoogleServicesConfig` |
| Proxy shape | `backend/server.mjs`, `backend/README.md` |
| Release signing | `app/build.gradle.kts` → `verifyReleaseSigning`, `keystore.properties.example` |

### Stale claims to ignore (verified contradictions)

These are still in the repo and are now **wrong**. They are the usual origin of the outdated
"Firebase is banned" / "Firebase was removed" assumption. Do not act on them, and do not
"restore" the state they describe:

| Stale claim | Where | Reality |
|---|---|---|
| "Fifteen unused dependencies, **including all of Firebase**" — `firebase-bom`, `firebase.ai`, `firebase.appcheck.recaptcha`, the `google-services` plugin alias and the `googleServices {}` block were all removed | `docs/REFACTOR_AUDIT.md` §2.10 (lines 200–210) | `firebase-bom` and the `google-services` plugin are **back in the working tree** for App Distribution. The BoM resolves to zero artifacts and the plugin application is conditional. Only `firebase-ai`, `firebase-appcheck-recaptcha`, `firebase-auth` and `firebase-firestore` remain unused catalog entries. |
| Application ID is `com.aistudio.hanzisrs.learn` | `docs/FIREBASE_TEST_LAB.md` §Identity (line 7) | The official ID is `com.hanzisrs.app`. The doc's Identity table is stale — fix the **doc**, never the build file. |

Also note: `gradle/libs.versions.toml` still pins `firebaseBom = "34.17.0"`, but the app
**hardcodes** `platform("com.google.firebase:firebase-bom:35.2.0")` in `app/build.gradle.kts`.
The catalog alias is unused. Do not "tidy" the app back onto `libs.firebase.bom` — that would
silently downgrade the BoM.

---

## 2. Non-Negotiable Rules (highest precedence)

These outrank every instruction below, including skill guidance.

1. **Inspect before modifying.** Read local `git status --short` first. Never assume GitHub matches local files.
2. **No git writes without explicit approval.** No `commit`, `push`, `add`, `pull`, branch switch, or history change without the user's explicit approval for that specific action.
3. **Forbidden commands:** `git add .`, `git add -A`, `git reset --hard`, `git clean`, force push.
4. **Protected directory** — never open, list, search, or modify: `Your-companion-app-for-learning-Chinese-on-Android-/`
5. **Protected file** — never open, modify, delete, regenerate, or stage: `app/src/test/screenshots/greeting.png`
6. **`docs/RANDOM_REVIEW_DESIGN.md` is the binding design contract for Random Review.** Preserve it. Read it before any Random Review task.
7. **Never change or remove working functionality during a UI task.** Never auto-expand task scope.
8. **Never claim an emulator/device/visual test occurred unless it actually did,** with concrete evidence.
9. **Never install on the user's personal phone; never wipe their app data.** Prefer a disposable test emulator.
10. **One small task at a time; one prompt per task;** wait for review before the next.
11. **Never print secret values.** `GEMINI_API_KEY` lives only in the backend environment — never in BuildConfig, Git, or logs. Never commit signing keys or `keystore.properties`. If a config contains a password/token/key, report only the path and the key name.

### 2.1 Working-tree discipline

- `app/build.gradle.kts` may carry an **unstaged, pre-existing whitespace-only edit**. It is not yours to stage, revert, or "clean up". If a task requires editing that file, read it first and leave the unrelated whitespace alone.
- The working tree is expected to be dirty from time to time. **Report** what is there; do not assume your own changes are the only ones.
- `git status --short` is the ground truth for what changed — run it before and after any task that touches files.

---

## 3. Project Invariants

No skill's advice may break these. If a skill appears to require breaking one, that is a conflict to report — not a change to make.

1. **Random Review is practice only.** It must NEVER write `srs_state` or `review_log`, and never call `submitRating` / `processReview` / any scheduler.
2. **SpeechRecognizer does speech-to-text, then text matching.** It is NOT a pronunciation grader. No tone score, no fluency score, no invented percentage. Keep UI copy honest about what was measured.
3. **No destructive Room migration fallback.** Respect version 3, `MIGRATION_1_2`, `MIGRATION_2_3`, and the exported schemas in `app/schemas` (`1.json`, `2.json`, `3.json`).
4. **Local-first.** Room + the bundled offline dictionary are the source of truth. There is no cloud sync and no second writer.
5. **Firebase scope is narrow: App Distribution only, as things stand.** The BoM (a platform constraint that resolves to zero artifacts), the conditionally applied `google-services` plugin, and `app/google-services.json` are present and permitted — see *Current Architectural Truths*. **Do not add, suggest, or configure the Analytics SDK, Crashlytics, Firebase Authentication, Firestore, or Cloud Functions** without an explicit request in a dedicated task. This is a *scope* limit, not a ban: the platform dependency and `google-services.json` are already in place, and the build is proven to succeed both with and without the config file. Three consequences that are easy to get wrong: `app/google-services.json` is gitignored and **must stay that way** (it sits outside `src/main`, so the `BuildConfigSecretsTest` `AIza` scan does **not** protect it — the gitignore entry does); the plugin is applied **conditionally**, so a clone without the file still builds; and the catalog's unused `firebase-auth` / `firebase-firestore` / `firebase-ai` / `firebase-appcheck-recaptcha` entries are **not** dependencies.
6. **Never invent `radical` / `strokeCount`** when the AI response omits them. Absent data must stay absent.
7. **Do not refactor `MainViewModel` (~1,650 lines) wholesale inside a small task.** Extraction needs its own task plus regression tests.
8. **`allowBackup="true"`, the session token in SharedPreferences, and `isMinifyEnabled = false` are KNOWN, deliberate-or-open product decisions.** Flag them; never "fix" them silently because a checklist says so.

### 3.1 Known open items — report, do not silently close

These are real and documented; a task may touch them but must not resolve them unilaterally.

| Item | State |
|---|---|
| **App Check / Play Integrity absent** | `backend/README.md` calls this *"a real gap rather than an oversight"* — a modified client can spend the proxy's Gemini quota. It is **still open**: declaring the Firebase BoM does not close it. Adding App Check means adding a Firebase artifact beyond the App Distribution scope → §3.5 applies, so it needs a dedicated task and explicit approval. |
| **`allowBackup="true"`** | Conflicts with the `mobile-app-security` release gate. Backups of a vocabulary app may be wanted. Product decision. |
| **`isMinifyEnabled = false`** | Release builds are unminified, so `crash-reporting`'s "upload the R8 mapping file" has no artifact to upload yet. |
| **Session token in SharedPreferences** | `SessionStore.kt` uses `getSharedPreferences(FILE_NAME, MODE_PRIVATE)`. The *Gemini* key was already moved server-side; this is a residual local credential. |
| **`remindersEnabled` / `reminderHour`** | Columns exist and are validated (`0..23`), but **no code path reads them to schedule anything** — there is no `WorkManager`, `AlarmManager` or `JobScheduler`. `NotificationHelper` only fires a due-count notification **when the learner taps** it (`HomeScreen` / `SettingsScreen` → `sendDueReminderNotification()`). Never claim reminders "work". |
| **`pilot.json` `version` field** | Still `2026-10-19`, which dates the **HSK 1** import only; the file now ships 557 entries (507 HSK 1 + 50 HSK 2). `ATTRIBUTION.txt` documents both batches. |
| **Daemon OOM during `testDebugUnitTest`** | Verified 2026-11-16: a `Java heap space` failure here is in the **Gradle daemon**, **not** the forked test worker. Stack: `Test.getPreviousFailedTestClasses` → `SerializableTestResultStore.forEachResult` → `SerializableTestResult$Serializer.deserializeFailure`. It happens during **task-graph creation**, deserialising a **stale failed-test record dated 2026-11-11** from the binary result store — before any test executes, which is why it can fail in 4 seconds. Raising the daemon to `-Xmx8g` makes it **worse**: native malloc failure, then a daemon crash. **Keep the 4g default in `gradle.properties`.** Fix: `.\gradlew.bat --stop`, then move the stale `results-generic.bin` / `output-events.bin` aside in the **external** build root (`%USERPROFILE%\HanziSRS-build\app\test-results\testDebugUnitTest\binary`). Derived output, outside the repo — **never delete repo files to work around it.** |
| **Conditional plugin: release variant not exercised** | The `google-services` plugin is applied only when `app/google-services.json` exists. Both paths were proven for the **debug** variant (build succeeds with and without the file). The **release** variant has **not** been exercised under either condition — *not yet verified*. |
| **Cold Gradle cache not exercised** | `apply(plugin = "com.google.gms.google-services")` resolved from this machine's **warm** Gradle cache. Whether the root `apply false` declaration alone suffices on a machine with a **cold** cache is *not yet verified*. |
| **No fresh `git clone` test** | The "clone without `google-services.json`" case was simulated by moving the file aside, **not** by an actual `git clone`. An end-to-end clean-checkout build is *not yet verified*. |

### 3.2 Data provenance rules

- The bundled dictionary is **third-party content**: `app/src/main/assets/dictionary/ATTRIBUTION.txt` (CC-CEDICT + HSK 3.0, CC BY-SA 4.0 / MIT) and `app/src/main/assets/strokes/ATTRIBUTION.txt` (MakeMeAHanzi, Arphic Public License). **Do not modify** `ARPHICPL.TXT` or either attribution file when touching dictionary data — they carry licence obligations.
- The dictionary ships **557 entries: 507 HSK 1 + 50 HSK 2**. The 50 HSK 2 words are the *only* level-2 entries; `l = 1` for the other 507.
- Dictionary entries carry **no** radical, stroke, example, or verification data. `toGeneratedWordData()` maps them with `radical = ""`, `strokeBreakdown = ""`, `strokeCount = 0`, `source = IMPORTED`. Do not invent those fields.

---

## 4. Skill Protocol

### 4.1 Mechanism (verified)

A `skill` tool is exposed: `skill({ id: "<exact-skill-id>" })`. It loads that skill's `SKILL.md` — and up to 10 sibling files — into the conversation.

**Bodies are NOT pre-loaded.** Only the skill's `id`, `name`, and `description` are visible until you call the tool. Therefore you MUST call `skill` before relying on a skill's guidance. Never act on a skill you have only seen the name and description of.

### 4.2 Mandatory pre-flight (before ANY implementation task)

1. **Classify the task** — which layer does it touch? Compose UI / Room data / audio / security / tests / build / release / docs.
2. **Pick ONE primary skill** from the routing table, plus **at most TWO supporting** skills — only if genuinely needed.
3. **Call `skill`** for each chosen id and READ the returned instructions.
4. **If the skill has `references/`,** load only the specific file(s) the task needs — never all of them.
5. **State your choice and reasoning in one or two lines,** then implement.
6. **If no skill fits, say so and proceed without one.** Loading nothing is a valid outcome.

### 4.3 Budget discipline

- **Never load all skills.** Hard cap: 1 primary + 2 supporting per task.
- `android-native-dev/SKILL.md` is ~29.5 KB and ships 9 reference files (~74 KB total). Load `SKILL.md`, then pull only the reference the task needs: `references/accessibility.md`, `references/adaptive-screens.md`, `references/design-style-guide.md`, `references/functional-requirements.md`, `references/motion-system.md`, `references/performance-stability.md`, `references/privacy-security.md`, `references/testing.md`, `references/visual-design.md`.
- **Do not re-load a skill already loaded in this session.**

### 4.4 Routing table — Tier A (stack-matched; load proactively when the trigger matches)

| Skill id | Trigger | Scope note |
|---|---|---|
| `android-native-dev` | Any Android-native work: Kotlin, Compose, Gradle/build errors, resources, manifest, accessibility, JVM/daemon sizing | Its own description says read it before Android native development. Best stack match. |
| `jetpack-compose` | Any change to a Composable, state (`remember`/`mutableStateOf`/`derivedStateOf`), Material 3 theming, animation, Compose Navigation | Small (1.3 KB). |
| `mobile-testing` | Adding/fixing tests, screenshot/visual regression, testID & accessibility-label conventions, CI test matrices | Apply ONLY the native parts: JUnit, Robolectric, Espresso, Compose `createComposeRule`, Roborazzi. IGNORE the React Native / Jest / MSW / Detox / Maestro sections. Its Firebase Test Lab guidance now matches the app's setup (Firebase is present for App Distribution) — but see `docs/FIREBASE_TEST_LAB.md` for what this project has actually wired up. |
| `mobile-app-security` | Tokens, credential storage, backup/dataExtraction rules, exported components, TLS, secrets, proxy rate-limiting, release hardening | Its release gate CONFLICTS with current `allowBackup=true`, `isMinifyEnabled=false`, and tokens in SharedPreferences. Report the gap; do not change product decisions unilaterally. |

### 4.5 Routing table — Tier B (conditional; load ONLY when the task explicitly enters that domain)

| Skill id | Load only when | Mandatory translation / limit |
|---|---|---|
| `data-modeling` | Room entities, indexes, migrations, schema design | REASONING ONLY (normalization, index rationale, up/down discipline, schema docs, anti-patterns). Its SQL is PostgreSQL (`JSONB`, `GIN`, `INCLUDE`, materialized views, partitioning) and its migrations are Prisma/TypeScript — NONE of that DDL applies to Room/SQLite. |
| `offline-first` | Offline behaviour, dictionary loading, network-failure UX, flaky-network testing | **NOTE THE EXACT ID — it is `offline-first`, not `offline-first-apps`.** Its local-first principle is ALREADY satisfied here. Do NOT introduce an outbox, delta-sync cursors, LWW/CRDT conflict resolution, or any sync engine — there is no sync server and no second writer. |
| `app-store-release` | Play release, versionCode/versionName, Play App Signing, AAB, data safety / privacy labels, staged rollout | Play half only. Apple / TestFlight / App Store Connect material is not applicable. |
| `fastlane` | User explicitly asks to automate Play upload or build numbering | Android half only (`upload_to_play_store` / supply, strictly increasing versionCode). `match` / `gym` / TestFlight are iOS. Requires Ruby + a Gemfile the repo does not have — PROPOSE, never install without approval. |
| `appium` | User explicitly asks for E2E device automation | Genuinely native (UiAutomator2) but needs an Appium server, driver, client and a device. None exist. Prefer Robolectric/Compose tests first. |
| `biometric-auth` | User explicitly requests biometric unlock | Not a current feature. Android half only: BiometricPrompt + Keystore-backed CryptoObject, enrolment-change invalidation. Never a boolean-only gate. |
| `deep-links` | User explicitly requests links that open specific screens | Android App Links / `assetlinks.json` / `autoVerify` half only; requires a hosted domain. Ignore the React Navigation / Expo linking sections. |
| `push-notifications` | User explicitly requests notifications | Distinguish LOCAL reminders (NotificationManager/WorkManager) from PUSH. `remindersEnabled`/`reminderHour` exist as columns but no scheduler consumes them — verify before claiming reminders work. FCM is a Firebase artifact outside the App Distribution scope → dedicated task and explicit approval required. |
| `crash-reporting` | User explicitly requests crash reporting | Entirely Crashlytics-based. The Firebase plugin and `google-services.json` are already in place, but **Crashlytics itself is outside the permitted Firebase scope** → ASK FIRST in a dedicated task. The skill's own decision table offers Sentry as an alternative. |

### 4.6 Tier C — DO NOT LOAD (stack mismatch, evidence-based)

- **`expo-native-ui`** — Expo Router, NativeWind, `react-native-reanimated`, `.tsx` routes in `app/`. This project is Kotlin/Compose. No technical reason to load it.
- **`frontend-dev`** — web landing/marketing pages, Tailwind/GSAP/Three.js, python media generation, 5.7 MB of web fonts. There is no web frontend.
- **`performance-profiling`** — Chrome DevTools, `clinic.js`, Web Vitals, a React `useEffect` leak example. Wrong tooling for native Android. For Android performance use `android-native-dev` (+ its `references/performance-stability.md`); the real tools are Perfetto, Android Studio Profiler, `dumpsys meminfo`.

### 4.7 Combination patterns

- **Compose UI fix** → `jetpack-compose` (primary) + `android-native-dev`; add `mobile-testing` only if tests are added.
- **Room schema/migration** → `data-modeling` (reasoning) + `android-native-dev` (`references/testing.md`); verify the exported schemas.
- **Security / backup / token / proxy** → `mobile-app-security` (primary) + `android-native-dev` (`references/privacy-security.md`).
- **Gradle build failure / test-worker OOM** → `android-native-dev` ONLY. Never `performance-profiling`.
- **Play release** → `app-store-release` (primary) + `mobile-app-security` for the release gate; `fastlane` only if automating.
- **Random Review anything** → read `docs/RANDOM_REVIEW_DESIGN.md` FIRST, then `jetpack-compose` (+ `mobile-testing` for screenshot tests). Re-assert the SRS-isolation and not-a-grader invariants.

### 4.8 Precedence and anti-abuse

Project rules (§2) and invariants (§3) ALWAYS outrank skill guidance. **A skill is advisory expertise, never authority.**

A skill must NEVER be used to justify:

- adding a dependency, plugin, SDK, or config file;
- a git write, or touching a protected path;
- expanding scope beyond the current task;
- breaking an invariant;
- silently changing a product decision (backup, minification, token storage);
- claiming a test or visual check that did not run.

When a skill conflicts with the project, **report the conflict with evidence and STOP for a decision.** Do not "apply the checklist".

### 4.9 What the skills are NOT for

- **They are not a substitute for reading this repo.** `AGENTS.md` and the code are authoritative; a skill is outside expertise that may predate this codebase.
- **They are not a licence to modernise.** `android-native-dev` describes a generic greenfield Android project (product flavors, sample app scaffolding, an `EXPO_PUBLIC_*`-style secret convention borrowed from a different ecosystem). This repo has **one** module, **no** product flavors, and a deliberate dependency-minimal posture. Take the *principles* (threading, Compose context rules, resource naming, M3 contrast, touch targets); ignore the *scaffolding*.
- **They are not a task list.** A skill's "Workflow" section describes how to do that kind of work **in general**. It does not authorise extra steps in this repo.
- **They are not a substitute for a design decision.** When a skill's checklist and a project invariant disagree, the invariant wins and the disagreement gets reported.

---

## 5. Reporting Contract (every task)

Report all of the following:

- **Files read.**
- **Files changed, and WHY per change.**
- **Which skill ids were loaded and why** — including which reference files.
- **Tests actually executed, with real results.** Label `FROM-CACHE` / `UP-TO-DATE` honestly.
- **`git status --short`.**
- **Anything unverified.** Write "not yet verified" instead of guessing.

### 5.1 Evidence standards

- **A claim needs a citation.** "The test passes" needs the run output. "The contrast is fine" needs the measured ratio. "It compiles" needs the build result.
- **Never describe a cached or skipped result as a fresh pass.** If Gradle reported `FROM-CACHE` or `UP-TO-DATE`, say so.
- **Never describe a device/emulator/visual check that did not happen.** If you only read code, say you read code.
- **Distinguish "the code says X" from "X was observed".** A comment is not a test.
- **When a task is blocked, report the blocker and what you tried** — not a plausible-sounding completion.

### 5.2 Reporting format

```text
Files read:        <paths>
Files changed:     <path> — <why, one line each>
Skills loaded:     <skill-id> (+ <reference file>) — <why>
Tests run:         <command> → <result>   [FROM-CACHE / UP-TO-DATE / fresh]
git status:        <output>
Unverified:        <what you could not confirm>
```

---

## 6. Reference Map

`docs/` files are **not** auto-loaded. Read them by path when relevant.

| Path | Use for |
|---|---|
| `docs/RANDOM_REVIEW_DESIGN.md` | Binding Random Review contract — read before any Random Review task |
| `docs/RELEASE.md` | Keystore, signing, build-output location, release procedure |
| `docs/FIREBASE_TEST_LAB.md` | Device-lab readiness, app identity, artifact build — **Firebase is present in the app for App Distribution (§3.5), so this describes the current setup; it does not authorise adding Crashlytics, Authentication, Firestore, or Cloud Functions**. Its Identity table still carries the retired `com.aistudio.hanzisrs.learn` — see *Stale claims to ignore*. |
| `docs/PRODUCT_AUDIT.md` | Product-level gaps and intent |
| `docs/FUNCTIONAL_AUDIT.md` | Feature-by-feature behaviour audit |
| `docs/REFACTOR_AUDIT.md` | Known refactor candidates (see §3.7 — extraction needs its own task) |
| `docs/MOBILE_UX_AUDIT.md` | UX findings |
| `docs/DICTIONARY_DATA.md` | Bundled dictionary format and provenance |
| `docs/STROKE_DATA.md` | Bundled stroke geometry provenance |
| `backend/README.md` | Proxy threat model — App Check gap, rate limiting, secret handling |
| `app/src/main/assets/dictionary/ATTRIBUTION.txt` | CC-CEDICT + HSK 3.0 licence and attribution |
| `app/src/main/assets/strokes/ATTRIBUTION.txt` | MakeMeAHanzi stroke geometry attribution |
| `keystore.properties.example` | Release-signing template — **contains no values**; the real file is gitignored |

---

## Quick reference

```text
Before implementing: classify layer → pick 1 primary skill (+≤2 supporting) → skill() → read → say why → build.
Never: git writes without approval · open the protected folder/file · paste skill bodies here ·
        add dependencies from a checklist · break an invariant · claim an unrun test.
Always: report files read/changed, skills loaded, real test results, git status, unverified items.
Facts: applicationId is com.hanzisrs.app (final) · Firebase = App Distribution only today ·
       the google-services plugin is applied ONLY if app/google-services.json exists ·
       backend/server.mjs stays a standalone Node proxy, NOT Cloud Functions.
```
