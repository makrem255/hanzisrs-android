// `java` alone resolves to the Gradle Java extension rather than the package, so the import is
// what makes `Properties` nameable here.
import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.roborazzi)
}

// Room writes each schema version to app/schemas as JSON. MigrationTestHelper replays those
// files to build a real pre-migration database, so this is what makes "the migration preserves
// the data" a testable claim rather than an assertion in a comment. The location is passed to
// KSP directly because the `room { }` extension needs the Room Gradle plugin, which this
// project does not apply.
val roomSchemaDir = "$projectDir/schemas"

// ---------------------------------------------------------------------------
// Release signing material
// ---------------------------------------------------------------------------
// The four values a signed release needs, and where each one is read from:
//
//   storeFile      keystore.properties:storeFile     or  $KEYSTORE_PATH
//   storePassword  keystore.properties:storePassword  or  $STORE_PASSWORD
//   keyAlias       keystore.properties:keyAlias      or  $KEY_ALIAS
//   keyPassword    keystore.properties:keyPassword    or  $KEY_PASSWORD
//
// `keystore.properties` is listed in .gitignore, as are `*.jks` and `*.keystore`, so neither the
// file nor the keystore it points at can be committed by accident. The committed
// `keystore.properties.example` is a template with no values in it.
//
// The environment variables are the better choice in CI and on a shared build machine, where there
// is no second file to leak. The properties file takes precedence when both are set.
//
// `keyAlias` used to be the hardcoded string "upload". That was a guess about someone else's
// keystore: if the real alias differed, signing failed with a password/alias error pointing at the
// wrong cause. It is now supplied explicitly, like the other three.

val keystorePropertiesFile = rootDir.resolve("keystore.properties")
val keystoreProperties = Properties().apply {
  if (keystorePropertiesFile.isFile) {
    keystorePropertiesFile.inputStream().use { load(it) }
  }
}

fun signingValue(propertyName: String, envName: String): String? =
  keystoreProperties.getProperty(propertyName)?.trim()?.takeIf { it.isNotEmpty() }
    ?: System.getenv(envName)?.trim()?.takeIf { it.isNotEmpty() }

// Relative paths here are project-relative, which is what makes a portable
// `storeFile=my-upload-key.jks` work for everyone. An absolute path in the file still wins,
// because `resolve` returns its argument when that argument is already absolute.
val releaseStoreFile =
  signingValue("storeFile", "KEYSTORE_PATH")?.let { rootDir.resolve(it) }
    // Android Studio's default upload-keystore name. Kept as the fallback so the failure message
    // names a concrete expected path rather than "unset".
    ?: rootDir.resolve("my-upload-key.jks")
val releaseStorePassword = signingValue("storePassword", "STORE_PASSWORD")
val releaseKeyAlias = signingValue("keyAlias", "KEY_ALIAS")
val releaseKeyPassword = signingValue("keyPassword", "KEY_PASSWORD")

// Nothing above throws. A contributor without a keystore must still be able to run
// `assembleDebug` and the unit tests, so the complaint belongs to a task that only runs when a
// release is actually being packaged. See `verifyReleaseSigning` at the bottom of this file.

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.aistudio.hanzisrs.learn"
    minSdk = 24
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    // Where AI word generation is requested. This is a *URL*, not a credential.
    //
    // The Gemini API key used to be a `buildConfigField` here, injected by the Secrets plugin from
    // a local `.env`. That put a paid third-party credential inside every APK: a `buildConfigField`
    // becomes a string constant in classes.dex, so the key was recoverable by anyone who unzipped
    // the app. The key now lives only in the AI backend's environment (see `backend/server.mjs`),
    // and this app has nothing to authenticate with.
    //
    // Overridable at build time so CI and local development can point at a different host without
    // editing a tracked file:
    //     gradlew :app:assembleRelease -PHANZISRS_AI_BACKEND_URL=https://ai.example.com
    //
    // Blank means the AI feature reports itself unavailable and the built-in offline dictionary
    // remains available, which is a working app rather than a broken one.
    buildConfigField(
      "String",
      "AI_BACKEND_URL",
      "\"${project.findProperty("HANZISRS_AI_BACKEND_URL") ?: ""}\""
    )
  }

  signingConfigs {
    create("release") {
      // A null password here is not an error at configuration time. AGP would report it far less
      // clearly than `verifyReleaseSigning` does, and it would also break every non-release task.
      storeFile = releaseStoreFile
      storePassword = releaseStorePassword
      keyAlias = releaseKeyAlias
      keyPassword = releaseKeyPassword
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("release")
    }
    debug { }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions {
    unitTests {
      isIncludeAndroidResources = true
      all {
        // A failing assertion is reported as `expected:<x> but was:<y>` on one line. Without
        // this, Gradle prints only the exception type and the line number, which is enough to
        // find the assertion but not to tell what actually went wrong.
        it.testLogging {
          events("failed")
          exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
          showStackTraces = true
        }
      }
    }
  }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
  // MigrationTestHelper reads the schema JSON from assets, not from the filesystem. The Room
  // Gradle plugin normally publishes roomSchemaDir there, but this project only runs the Room
  // compiler through KSP and does not apply that plugin, so it has to be wired by hand.
  //
  // The source set is `debug`, not `test`, and that is not a stylistic choice. A Robolectric
  // unit test has no assets of its own: AGP writes the paths Robolectric uses into
  // intermediates/unit_test_config_directory/.../test_config.properties, and
  // `android_merged_assets` there points at the *main variant's* mergeDebugAssets. Adding the
  // directory to the `test` source set therefore has no effect at all — the JSON never reaches
  // the directory Robolectric reads, and the migration test fails with FileNotFoundException
  // before it asserts anything. `debug` is a build type, and build-type assets are merged into
  // mergeDebugAssets, which is exactly the directory that needs filling. Debug rather than main
  // so 69 KB of schema JSON never ships to users in the release APK.
  sourceSets {
    getByName("debug") { assets.srcDir(roomSchemaDir) }
    getByName("androidTest") { assets.srcDir(roomSchemaDir) }
  }
}

// The Secrets Gradle plugin was removed from this project.
//
// It existed for exactly one purpose: injecting `GEMINI_API_KEY` from a local `.env` into
// BuildConfig. That is a mechanism for putting a secret in the APK, which is where the key
// spent its life - readable in `classes.dex` by anyone who downloaded the app. The key now lives
// in the AI backend's environment instead, and nothing in the client needs to be injected.
//
// A `buildConfigField` is still used, for the backend *URL*, which is not a secret and is meant to
// be readable by anyone holding the app.

// Every coordinate below is one something in app/src actually imports.
//
// The commented-out ones used to be here with a note saying they were kept "to make it easy to
// add them back in". That is thirteen coordinates one uncomment away from shipping, and none of
// them were commented out after being used — they were speculative. The list below was checked
// by grepping app/src for each library's package, not by reading the file and assuming.
//
// Deliberately absent, having been verified unused: Retrofit, Moshi (+ its KSP processor),
// OkHttp's logging interceptor, the whole Firebase BOM / AI / App Check stack, Espresso, and
// compose tooling (there is no `@Preview` in the project). The Gemini call is now a hand-built
// OkHttp request parsed with `org.json`, sent to our own proxy rather than to Google.
//
// App Check is the notable omission, and it is a real gap rather than an oversight: verifying
// that a caller is this app is what would stop someone spending the proxy's Gemini quota through
// a modified client. `backend/README.md` sets that out in full.
dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.activity.compose)
  // `icons.extended` is required, not incidental: ErrorOutline, VolumeUp, LocalLibrary,
  // FastRewind, FastForward, Undo, Visibility, VisibilityOff, AutoAwesome, Speed, Flip,
  // Psychology, RecordVoiceOver, Hearing and NotificationsActive are all outside `icons.core`.
  // The standalone `icons.core` artifact went, since everything it ships is in the catalogue.
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.okhttp)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  // The reason `roomSchemaDir` has to be attached to `debug` rather than `test`: a Robolectric
  // test reads the *main variant's* merged assets, so the exported schemas have to exist there.
  testImplementation(libs.androidx.room.testing)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  "ksp"(libs.androidx.room.compiler)
}

// Arguments for the Room annotation processor itself. `room.schemaLocation` is what makes
// `exportSchema = true` write app/schemas/<version>.json, which MigrationTestHelper replays to
// build a real pre-migration database. Without it "the migration preserves the data" would be
// an assertion in a comment rather than a test.
ksp {
  arg("room.schemaLocation", roomSchemaDir)
  arg("room.incremental", "true")
}

// ---------------------------------------------------------------------------
// Release signing: fail early, and say what to do about it
// ---------------------------------------------------------------------------
// Without this, a release build with no keystore spends a few minutes compiling and then reports:
//
//   > Task :app:packageRelease FAILED
//   Execution failed for task ':app:validateSigningRelease'.
//     > Keystore file '...\my-upload-key.jks' not found for signing config 'release'.
//
// That names the symptom and nothing else: not the passwords, not the alias, and not the fact that
// a `keystore.properties` file is the intended way to supply them. This task answers all of that
// in one message.
//
// It is a task rather than a configuration-time check for two reasons. A configuration-time throw
// would break `assembleDebug` and `testDebugUnitTest` for anyone without a keystore, which would
// make the app unbuildable for most contributors. And `TaskExecutionGraph.whenReady` is not
// compatible with the configuration cache this project enables (`org.gradle.configuration-cache=true`,
// gradle.properties:21), whereas task dependencies are.

val verifyReleaseSigning by tasks.registering {
  group = "verification"
  description = "Checks that release signing material is present, and explains what is missing."

  // Captured into locals now, at configuration time, so the body below does not re-read the
  // environment or the properties file when it runs. That keeps the task safe under the
  // configuration cache, which serialises the task's inputs rather than recomputing them.
  val storeFilePath = releaseStoreFile
  val storePasswordValue = releaseStorePassword
  val keyAliasValue = releaseKeyAlias
  val keyPasswordValue = releaseKeyPassword
  val exampleFile = rootDir.resolve("keystore.properties.example")

  doLast {
    val missing = mutableListOf<String>()
    if (!storeFilePath.isFile) {
      missing += "keystore file: ${storeFilePath.absolutePath} does not exist"
    }
    if (storePasswordValue == null) missing += "store password   (keystore.properties:storePassword or \$STORE_PASSWORD)"
    if (keyAliasValue == null) missing += "key alias        (keystore.properties:keyAlias or \$KEY_ALIAS)"
    if (keyPasswordValue == null) missing += "key password     (keystore.properties:keyPassword or \$KEY_PASSWORD)"

    if (missing.isEmpty()) return@doLast

    throw GradleException(
      buildString {
        appendLine("Cannot sign a release: the signing material is incomplete.")
        appendLine()
        missing.forEach { appendLine("  - $it") }
        appendLine()
        appendLine("How to fix it:")
        appendLine()
        appendLine("  1. Create a keystore, if you do not already have one:")
        appendLine("       keytool -genkeypair -v \\")
        appendLine("         -keystore my-upload-key.jks \\")
        appendLine("         -alias upload \\")
        appendLine("         -keyalg RSA -keysize 2048 -validity 10000")
        appendLine("     Choose both passwords yourself. There is no default, and neither this")
        appendLine("     repository nor anyone reading it should ever hold them.")
        appendLine()
        appendLine("  2. Put the values in a file next to this one, named keystore.properties.")
        appendLine("     It is gitignored, as is *.jks, so neither can be committed by accident.")
        if (exampleFile.isFile) {
          appendLine("     $exampleFile is the committed template - copy it and fill it in:")
          appendLine("         copy keystore.properties.example keystore.properties")
        }
        appendLine()
        appendLine("  3. Or supply them as environment variables instead, which is preferable in CI")
        appendLine("     and on a build machine where there is no second file to leak:")
        appendLine("         set KEYSTORE_PATH=my-upload-key.jks")
        appendLine("         set KEY_ALIAS=upload")
        appendLine("         set STORE_PASSWORD=...")
        appendLine("         set KEY_PASSWORD=...")
        appendLine()
        appendLine("Do not commit the keystore or the passwords. Do not put them in")
        appendLine("gradle.properties, a tracked build file, or source.")
        appendLine()
        appendLine("To build without signing - for a device install, or to check that")
        appendLine("compilation and packaging succeed - use the debug variant:")
        appendLine("     gradlew :app:assembleDebug")
      }
    )
  }
}

// Both artifacts route through here. `packageRelease` and `signReleaseBundle` each depend on
// `validateSigningRelease`, so hooking that single task is enough; the other two names are listed
// so a future AGP change that reorders the graph cannot silently skip the check.
tasks.matching {
  it.name == "validateSigningRelease" || it.name == "packageRelease" || it.name == "signReleaseBundle"
}.configureEach {
  dependsOn(verifyReleaseSigning)
}
