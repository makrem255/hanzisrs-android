<div align="center">
<img width="1200" height="475" alt="GHBanner" src="https://ai.google.dev/static/site-assets/images/share-ais-513315318.png" />
</div>

# Run and deploy your AI Studio app

This contains everything you need to run your app locally.

View your app in AI Studio: https://ai.studio/apps/f0917cb4-6cb9-4470-9a2c-964714c3cef6

## Run Locally

**Prerequisites:**  [Android Studio](https://developer.android.com/studio)


1. Open Android Studio
2. Select **Open** and choose the directory containing this project
3. Allow Android Studio to fix any incompatibilities as it imports the project.
4. Create a file named `.env` in the project directory and set `GEMINI_API_KEY` in that file to your Gemini API key (see `.env.example` for an example)
5. Remove this line from the app's `build.gradle.kts` file: `signingConfig = signingConfigs.getByName("debugConfig")`
6. Run the app on an emulator or physical device
7. If you have already published your app in AI Studio, please [request upload key reset](https://support.google.com/googleplay/android-developer/answer/9842756#zippy=%2Crequest-an-upload-key-reset) in Google Play Console.

## Building from the command line

The Gradle wrapper is checked in. The build requires a JDK 25 toolchain; the
Android Studio bundled JBR satisfies this:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
```

### Build output is written outside the project directory

This project lives inside a OneDrive-synced folder. The OneDrive client holds
sync handles on files while Gradle writes them, which makes `:app:dexBuilderDebug`
fail with `AccessDeniedException` on `desugar_graph` and `project_dex_archive`.

The root `build.gradle.kts` therefore redirects all generated output to
`%USERPROFILE%\HanziSRS-build`. Sources stay in OneDrive so they remain backed
up. Override the location with the `HZB_BUILD_DIR` environment variable.

## Architecture notes

Single-module (`app`) Android app, Jetpack Compose + Material3, unidirectional
data flow.

| Layer | Location | Responsibility |
| --- | --- | --- |
| UI | `ui/screens`, `ui/components` | Compose screens and the stroke-order canvas |
| State | `ui/viewmodel/MainViewModel.kt` | Exposes `StateFlow`s, owns screen-level orchestration |
| Domain | `data/srs/SrsAlgorithm.kt` | Pure SM-2-style scheduling, unit tested |
| Data | `data/db`, `data/repository` | Room entities/DAOs behind repositories |
| Remote | `data/ai/GeminiAiService.kt` | Gemini REST calls with structured error reporting |

### Known accepted risk: the Gemini API key ships inside the APK

`GEMINI_API_KEY` is read from `.env` at build time and compiled into
`BuildConfig`, so it is present in the APK and can be extracted by anyone who
downloads the app. `GeminiAiService` calls `generativelanguage.googleapis.com`
directly, so although `firebase-ai` and App Check are on the classpath, App
Check does not protect this endpoint.

This is currently accepted. Before a public release, either move the call
behind a backend proxy that holds the key, or switch to the Firebase AI SDK with
App Check enforced.

### AI failure handling

`generateChineseWordData` returns `Result.failure` with a typed `AiFailure`
(`MissingApiKey`, `Network`, `Api`, `MalformedResponse`) so the UI can tell the
learner what actually went wrong. It no longer silently substitutes the built-in
offline dictionary; that data is only used when the learner explicitly taps
"Use offline sample data instead", and is labelled `LOCAL_FALLBACK` in the UI.

The model id is `gemini-3.5-flash` (see `GeminiAiService.MODEL_ID`). Google
currently recommends `gemini-3.8-flash` or `gemini-3.5-flash-lite` for new work.
