// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.kotlin.compose) apply false
  alias(libs.plugins.google.devtools.ksp) apply false
  alias(libs.plugins.roborazzi) apply false
  alias(libs.plugins.secrets) apply false
  alias(libs.plugins.google.services) apply false
}

// This project is stored inside a OneDrive-synced folder. The OneDrive client
// holds sync handles on files as Gradle writes them, which makes the dex
// packaging steps fail reproducibly with:
//   java.nio.file.AccessDeniedException: ...\app\build\intermediates\desugar_graph\...
//   java.io.IOException: Unable to delete directory ...\project_dex_archive\...
//
// Sources stay in OneDrive (so they remain backed up), but all generated build
// output is redirected to the local disk, which is where Gradle expects a
// writable scratch area. Set HZB_BUILD_DIR to override the location.
val buildRoot: File = System.getenv("HZB_BUILD_DIR")?.let(::File)
  ?: File(System.getProperty("user.home"), "HanziSRS-build")

subprojects {
  layout.buildDirectory.set(File(buildRoot, project.name))
}
