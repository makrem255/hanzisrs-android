import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.roborazzi)
  alias(libs.plugins.secrets)
  alias(libs.plugins.google.services)
}

// Room writes each schema version to app/schemas as JSON. MigrationTestHelper replays those
// files to build a real pre-migration database, so this is what makes "the migration preserves
// the data" a testable claim rather than an assertion in a comment. The location is passed to
// KSP directly because the `room { }` extension needs the Room Gradle plugin, which this
// project does not apply.
val roomSchemaDir = "$projectDir/schemas"

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
  }

  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
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

// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects.
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
  ignoreList.add("FIREBASE_APPCHECK_DEBUG_TOKEN")
}

googleServices { missingGoogleServicesStrategy = MissingGoogleServicesStrategy.WARN }

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(platform(libs.firebase.bom))
  // implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  // implementation(libs.androidx.camera.camera2)
  // implementation(libs.androidx.camera.core)
  // implementation(libs.androidx.camera.lifecycle)
  // implementation(libs.androidx.camera.view)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  // implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  // implementation(libs.coil.compose)
  implementation(libs.converter.moshi)
  implementation(libs.firebase.ai)
  // Uncomment to use Firestore:
  // implementation(libs.firebase.firestore)

  // Uncomment ALL FOUR of the following dependencies together to use Firebase Auth and Google
  // Sign-In via Credential Manager:
  // implementation(libs.firebase.auth)
  // implementation(libs.androidx.credentials)
  // implementation(libs.androidx.credentials.play.services)
  // implementation(libs.googleid)
  implementation(libs.firebase.appcheck.recaptcha)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.logging.interceptor)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  // implementation(libs.play.services.location)
  implementation(libs.retrofit)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.androidx.room.testing)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
  "ksp"(libs.moshi.kotlin.codegen)
}

// Arguments for the Room annotation processor itself. `room.schemaLocation` is what makes
// `exportSchema = true` write app/schemas/<version>.json, which MigrationTestHelper replays to
// build a real pre-migration database. Without it "the migration preserves the data" would be
// an assertion in a comment rather than a test.
ksp {
  arg("room.schemaLocation", roomSchemaDir)
  arg("room.incremental", "true")
}
