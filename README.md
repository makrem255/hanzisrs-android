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
4. Run the app on an emulator or physical device

The app builds and runs without any API key. The AI word-generation feature stays switched off
until a backend is configured — see **AI word generation** below. Nothing secret needs to be set up
to get a working debug build.

### Setting up the AI backend (optional)

AI word generation goes through a small proxy that holds the Gemini key server-side. The key is
never in the app, so there is nothing to add to this repository:

```powershell
copy backend\.env.example backend\.env    # then put your key in backend\.env — it is gitignored
node backend\server.mjs

# point the app at it; 10.0.2.2 is the host machine as seen from the emulator
.\gradlew.bat :app:assembleDebug "-PHANZISRS_AI_BACKEND_URL=http://10.0.2.2:8080"
```

`backend/README.md` covers deployment and what the proxy does and does not protect against.

### Building a signed release

Release builds need an upload keystore that is **not** in this repository. See
[`docs/RELEASE.md`](docs/RELEASE.md) for the full procedure, and
`keystore.properties.example` for the template to copy.

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:assembleRelease   # signed APK
.\gradlew.bat :app:bundleRelease     # signed AAB — this is what Play uses
```

If the signing material is missing, the build stops with a message naming exactly which values are
absent and where to put them, instead of failing part-way through packaging.

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
| Remote | `data/ai/GeminiAiService.kt` | Calls the AI backend proxy; structured error reporting |

### The Gemini API key is no longer in the app

This used to be the project's largest security defect, and the README recorded it as an accepted
risk. It is fixed rather than accepted.

`GEMINI_API_KEY` was injected into `BuildConfig` from a local `.env` by the Secrets Gradle plugin.
A `buildConfigField` is compiled into `classes.dex` as a plain string constant, so the key shipped
inside every APK and was recoverable by anyone who downloaded the app and unzipped it.

The key now lives only in the environment of `backend/server.mjs`, which forwards requests to
Gemini. The app calls that proxy and sends nothing but the learner's query — it has no credential
to send. The Secrets plugin was removed, because a plugin that copies every `.env` entry into
`BuildConfig` would reintroduce the problem the moment anyone put a key back in `.env`.

The only thing left in `BuildConfig` is `AI_BACKEND_URL`, which is a URL and not a secret.
`BuildConfigSecretsTest` asserts that no credential-shaped field appears there, so this cannot
quietly regress.

What this does **not** fix: the proxy endpoint is public, so a modified client can still spend your
quota. Rate limiting bounds that; Android App Check would actually prevent it. `backend/README.md`
sets out the gap and the options.

### AI failure handling

`generateChineseWordData` returns `Result.failure` with a typed `AiFailure`
(`BackendNotConfigured`, `Network`, `Api`, `MalformedResponse`) so the UI can tell the
learner what actually went wrong. It no longer silently substitutes the built-in
offline dictionary; that data is only used when the learner explicitly taps
"Use offline sample data instead", and is labelled `LOCAL_FALLBACK` in the UI.

`MissingApiKey` was renamed to `BackendNotConfigured`. The app no longer holds a key, so telling a
learner that "no API key is configured in this build" would point them at a credential they have
never seen and cannot fix.

The model id is `gemini-3.5-flash`, set by `GEMINI_MODEL` on the backend. Google
currently recommends `gemini-3.8-flash` or `gemini-3.5-flash-lite` for new work.
