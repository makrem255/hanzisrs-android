# Release signing

Everything needed to produce a signed HanziSRS artifact, and exactly where the signing material
belongs.

Nothing in this repository contains a keystore or a password, and nothing in it should.

## What is needed

A signed release requires four values:

| Value | Meaning | `keystore.properties` | Environment variable |
| --- | --- | --- | --- |
| store file | path to the upload keystore | `storeFile` | `KEYSTORE_PATH` |
| store password | password for the keystore | `storePassword` | `STORE_PASSWORD` |
| key alias | which key inside it to sign with | `keyAlias` | `KEY_ALIAS` |
| key password | password for that key | `keyPassword` | `KEY_PASSWORD` |

`keystore.properties` takes precedence when both are set.

None of these four values exist in this repository, and there is no default for any of them. Do not
add one: a committed password is a published password, and a published upload key means someone
else can sign an update to your app.

## Step 1 — create the upload keystore

Once per app listing. If you already have one, skip this; do not create a second.

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"

keytool -genkeypair -v `
  -keystore my-upload-key.jks `
  -alias upload `
  -keyalg RSA -keysize 2048 -validity 10000 `
  -dname "CN=<your name>, O=<your organisation>, C=<country>"
```

keytool prompts for the two passwords. Let it — do not pass them on the command line, where they
land in your shell history.

Two details worth knowing before you run it:

- **`-validity 10000`** is about 27 years. Google Play requires at least 25 years of validity for
  an app signing key and can refuse to accept updates signed with a key that expires sooner. You
  cannot extend an existing key's validity, only replace it.
- **PKCS12 cannot hold two different passwords.** Modern keytool defaults to PKCS12, warns that
  `-keypass` is unsupported, and ignores it — so `keyPassword` ends up equal to `storePassword`.
  That is fine for an upload key. If you specifically need distinct passwords, add `-storetype JKS`.

## Step 2 — supply the values

Two options. Either is acceptable; they differ in where the secret lives.

### Option A — `keystore.properties` (convenient locally)

```powershell
copy keystore.properties.example keystore.properties
```

Then fill in the four values. `keystore.properties` is in `.gitignore` (line 32), as are `*.jks` and
`*.keystore` (lines 30–31), so `git add -A` cannot pick the file or the keystore up by accident.

Verify that is still true before you trust it:

```powershell
git check-ignore -v keystore.properties my-upload-key.jks
```

### Option B — environment variables (preferred in CI)

```powershell
$env:KEYSTORE_PATH  = "my-upload-key.jks"
$env:KEY_ALIAS      = "upload"
$env:STORE_PASSWORD = "..."
$env:KEY_PASSWORD   = "..."
```

No second file to leak, and nothing written to disk that a crash report or a backup might capture.
This is the better choice on a build machine, and the only sensible one in CI where secrets come
from the platform's own store.

### Do not put these in `gradle.properties`

`gradle.properties` is tracked. A password there is a committed password.

## Step 3 — build

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"

.\gradlew.bat :app:assembleRelease   # signed APK
.\gradlew.bat :app:bundleRelease     # signed AAB
```

Use the **AAB** for Google Play. Play requires an app bundle for new listings, and it enables
dynamic delivery, where Play serves only the device's current configuration rather than the whole
app. The APK is for sideloading and enterprise distribution.

Build output goes to `%USERPROFILE%\HanziSRS-build` rather than the project directory, because the
project sits in a OneDrive folder and the sync client's file handles break dexing. See
`README.md`. So the artifacts are:

```
%USERPROFILE%\HanziSRS-build\app\outputs\apk\release\app-release.apk
%USERPROFILE%\HanziSRS-build\app\outputs\bundle\release\app-release.aab
```

Set `HANZISRS_AI_BACKEND_URL` on the command line, or via the same `-P` property, if you are
pointing the app at a deployed AI backend rather than leaving the feature off.

## If the build stops with "Cannot sign a release"

That is `:app:verifyReleaseSigning` reporting which of the four values is missing, before any
packaging work starts. It is deliberate: Gradle's own failure for this

```
Execution failed for task ':app:packageRelease'.
  > Keystore file '...\my-upload-key.jks' not found for signing config 'release'.
```

names the missing file and nothing else — not the passwords, not the alias, and not the fact that a
`keystore.properties` file is the intended way to supply them. The task's message lists every
missing value at once, so you fix it in one pass instead of one failure at a time.

The check is a task rather than a configuration-time guard so that `assembleDebug` and the unit
tests keep working for contributors who have no keystore at all.

## Verifying a signed artifact

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
& "$env:LOCALAPPDATA\Android\Sdk\build-tools\36.0.0\apksigner.bat" verify --print-certs `
  "$env:USERPROFILE\HanziSRS-build\app\outputs\apk\release\app-release.apk"
```

A signed APK reports the certificate's SHA-256. An unsigned one reports:

```
DOES NOT VERIFY
Missing META-INF/MANIFEST.MF
```

For an AAB, signing is applied at upload time by Play App Signing, so the local bundle is signed with
your upload key but the APK Google serves is signed with the app signing key Play generates and
holds. That is expected, not a problem.

## Verifying no credential shipped in the artifact

Signing proves the artifact is intact. It says nothing about what is *inside* it, and the whole
point of moving the Gemini key to the backend is that it must not be there. Check it explicitly
before uploading:

```powershell
$apk = "$env:USERPROFILE\HanziSRS-build\app\outputs\apk\release\app-release.apk"
$scan = "$env:TEMP\hanzisrs-apk-scan"

Remove-Item $scan -Recurse -Force -ErrorAction SilentlyContinue
Expand-Archive $apk -DestinationPath $scan

# `AIza` is the prefix on every Google API key, and cannot occur by accident in a resource
# name, a package name or a URL — so any hit is a real credential.
$hits = Get-ChildItem $scan -Recurse -File |
  Select-String -Pattern 'AIza' -SimpleMatch -Encoding Default -ErrorAction SilentlyContinue

if ($hits) { $hits; throw "A Google API key is present in the APK - do not upload this artifact." }
"clean: no API key in $apk"
```

Also confirm the client is calling your backend rather than Google directly:

```powershell
Select-String -Path "$scan\classes*.dex" -Pattern 'generativelanguage' -SimpleMatch -Encoding Default
```

There should be no matches. `generativelanguage.googleapis.com` belongs in `backend/server.mjs` and
nowhere in the app.

`BuildConfigSecretsTest` covers the same ground at the source level and runs as part of
`testDebugUnitTest`, which is faster to run and good enough for every commit. Scan the built APK
before each upload, because that is the only check that covers resource merging and dex generation,
which the source-level tests cannot see.

## After the first upload

- Keep the keystore and passwords. Without them you cannot update the listing; the only recovery is
  removing the app and publishing a new one under a new listing.
- Store the backup somewhere you would not store the keystore itself, and separately from the
  machine that built it. An upload key is the one secret worth losing a drawer to protect.
- Do not treat the upload key as the app signing key. Play App Signing generates and holds that one
  for you; you never see it.
- Play requires that the app signing key be at least 2048 bits and valid for 25+ years. Checked
  above, at creation, because it cannot be fixed afterwards.