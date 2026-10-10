# HanziSRS — Firebase Test Lab readiness

## Identity

| Item | Value |
|---|---|
| Application ID | `com.aistudio.hanzisrs.learn` |
| Namespace (R class) | `com.example` |
| minSdk / targetSdk / compileSdk | 24 / 36 / 36 |
| Version | 1.0 (versionCode 1) |
| Instrumentation runner | `androidx.test.runner.AndroidJUnitRunner` |
| Launch mode | `singleTop` (notification re-entry safe) |
| AI backend URL | `BuildConfig.AI_BACKEND_URL`, blank by default (AI reports unavailable, offline dictionary works) |

## Build the artifacts

From the repo root (`gradlew` / `gradlew.bat`):

```bat
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:assembleDebugAndroidTest
```

Outputs (verified by building them — sizes at build time in parentheses):

- App APK: `app/build/outputs/apk/debug/app-debug.apk` (17,891 KB)
- Test APK: `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` (1,018 KB)

(When `HZB_BUILD_DIR` is set, as in this workspace, outputs redirect under
`$HZB_BUILD_DIR/app/outputs/apk/...` with identical relative paths.)

A release build additionally requires signing material and is **not** needed for Robo:

```bat
.\gradlew.bat :app:assembleRelease
```

fails early with instructions unless `keystore.properties` (gitignored) or
`KEYSTORE_PATH` / `STORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD` is present.
Never commit those values.

## Run unit tests locally (no device needed)

```bat
.\gradlew.bat :app:testDebugUnitTest
```

29 suites / 362+ tests, all JVM (JUnit + Robolectric). This is the regression gate
that runs before every commit — not a substitute for on-device evidence.

## Run a Robo crawl (no test APK needed)

```bash
gcloud firebase test android run \
  --type robo \
  --app app/build/outputs/apk/debug/app-debug.apk \
  --device model=Pixel8,version=34,locale=en,orientation=portrait \
  --timeout 300s \
  --robo-script-file tools/robo-script.json
```

`tools/robo-script.json` biases exploration toward Review → grade → Random Review →
Learn → Profile. Robo needs no credentials: first launch lands on guest/auth and every
empty state has a working action. No backdoor, no test flag, no hidden entry point —
production build is what gets crawled.

Suggested matrix (keep it small on the free tier):

- `Pixel8,version=34` (primary, portrait)
- `Pixel7,version=33` (older OS)
- One large-screen device, e.g. `PixelTablet,version=34` (layout/FAB/nav-bar overlap)

## Run instrumented tests (needs the test APK + a device)

```bash
gcloud firebase test android run \
  --type instrumentation \
  --app app/build/outputs/apk/debug/app-debug.apk \
  --test app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk \
  --device model=Pixel8,version=34,locale=en,orientation=portrait \
  --timeout 300s
```

## Key stable selectors for scripts and assertions

Screens: `home_screen`, `learn_screen`, `progress_screen`, `profile_screen`,
`random_review_screen`. Bottom bar: `nav_item_home`, `nav_item_library`,
`nav_item_progress`, `nav_item_settings`.

Flows: `review_start_button` (Home) / `dashboard_start`, `review_card`,
`reveal_answer_button`, `srs_rate_again` / `srs_rate_hard` / `srs_rate_good` /
`srs_rate_easy`, `session_complete_title`, `session_review_again_button`,
`random_review_entry`, `random_review_start`, `random_review_help`,
`random_review_add_words`, `random_review_card`, `random_review_listen`,
`random_review_next`, `random_review_exit`, `random_review_exit_yes`,
`random_review_exit_no`, `library_search_field`,
`word_row_<id>`, `approve_word_button`, `sound_effects_switch`,
`theme_mode_selector`, `slow_tts_switch`, `logout_button`.

## Known device-only gaps (cannot be verified on JVM)

Swipe feel and thresholds, speech-recognition accuracy, TTS/sound audibility,
haptics, permission-deny UX, notification cold-start entry, rotation mid-drag,
FAB/scroll overlap on small screens. Robo video + screenshots are the evidence
for these — inspect them, do not assume green means felt-right.
