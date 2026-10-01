package com.example

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the fix for the app's largest security defect: a credential shipped inside the APK.
 *
 * `GEMINI_API_KEY` used to be a `buildConfigField`, injected by the Secrets Gradle plugin from a
 * local `.env`. A `buildConfigField` is compiled into `classes.dex` as a plain string constant, so
 * the key shipped with every APK and was recoverable by anyone who downloaded the app and unzipped
 * it. The key now lives only in the AI backend's environment.
 *
 * The removal is not self-enforcing. Nothing in the toolchain stops someone adding a
 * `buildConfigField` for a secret again, and the failure mode is silent: the build succeeds, the
 * tests pass, and the credential ships. So this asserts the shape of the build rather than trusting
 * the change to have been complete.
 *
 * Scope note: what this can prove is limited to what is reachable from a unit test. It covers the
 * generated `BuildConfig` class, the app's own resources, and the app's own sources. It cannot read
 * a built APK. The end-to-end check for that — scanning `classes.dex` and `resources.arsc` of a
 * real release artifact — is part of the release procedure in `docs/RELEASE.md`, because that is
 * the only place it is actually meaningful.
 */
class BuildConfigSecretsTest {

    /**
     * `AIza` is the prefix Google issues every Gemini API key with.
     *
     * Scanning for it is useful precisely because it cannot occur by accident. No resource name,
     * version string, package name, class name or URL contains it, so any hit is a real credential
     * that has been pasted into a file.
     */
    private val googleApiKeyPrefix = "AIza"

    @Test
    fun `BuildConfig declares no field that looks like a credential`() {
        val credentialLikeWords = listOf(
            "KEY", "SECRET", "TOKEN", "PASSWORD", "CREDENTIAL", "PASSWD", "AUTH"
        )

        val suspicious = BuildConfig::class.java.declaredFields
            .map { it.name }
            .filter { name ->
                val upper = name.uppercase()
                credentialLikeWords.any { upper.contains(it) }
            }

        assertTrue(
            "BuildConfig must not carry credential-shaped fields, but found: $suspicious. " +
                "Anything added there is readable in classes.dex by anyone with the APK. " +
                "Secrets belong to the backend environment, not the app.",
            suspicious.isEmpty()
        )
    }

    @Test
    fun `the AI backend URL is present, and is a URL rather than a credential`() {
        // The other half of the same contract: remove the secret, but keep the endpoint
        // configurable. Without this field the AI feature could never be enabled at all, and
        // "fixing" the leak by deleting it would have been a silent feature removal.
        val declared = BuildConfig::class.java.declaredFields.map { it.name }

        assertTrue(
            "BuildConfig.AI_BACKEND_URL is missing, so no backend could ever be configured",
            declared.contains("AI_BACKEND_URL")
        )
    }

    @Test
    fun `the backend URL is not a credential in disguise`() {
        // A sanity check on the value, not just the name. If someone ever pastes a key into this
        // field "to make it work", the prefix test below on the field value would catch it, and
        // this says why the value is allowed to be in the APK at all: it is an https URL, or it is
        // empty and the AI feature is simply switched off.
        val value = BuildConfig.AI_BACKEND_URL

        assertTrue(
            "AI_BACKEND_URL must be empty or an absolute http(s) URL, but was: '$value'",
            value.isEmpty() || value.startsWith("http://") || value.startsWith("https://")
        )
    }

    @Test
    fun `no Google API key appears anywhere in the app's own sources`() {
        // Covers the requirement directly: the key must not be in source code. The scan root is
        // `src/main`, so this test's own file — which necessarily contains the prefix in order to
        // search for it — is not in range and needs no exclusion.
        val hits = scanFor(googleApiKeyPrefix)

        assertTrue(
            "A Google API key literal appears in the app's sources:\n" +
                hits.joinToString("\n") { "  ${it.path}: ${it.line}" },
            hits.isEmpty()
        )
    }

    @Test
    fun `no Google API key appears in the manifest or in string resources`() {
        // These end up in the APK too: the manifest is merged verbatim, and strings.xml is
        // compiled into resources.arsc. Neither is in `src/main/java`, so the test above does not
        // reach them.
        val manifestDirectory = requireModuleDirectory()
        val targets = listOf(
            manifestDirectory.resolve("src/main/AndroidManifest.xml"),
            manifestDirectory.resolve("src/main/res/values/strings.xml"),
        )

        val missing = targets.filterNot { it.isFile }
        check(missing.isEmpty()) {
            "expected to find ${targets.joinToString(" and ")}, but missing: " +
                missing.joinToString(", ") { it.path }
        }

        val hits = targets.flatMap { file ->
            file.readLines()
                .withIndex()
                .filter { (_, line) -> line.contains(googleApiKeyPrefix) }
                .map { "${file.path}:${it.index + 1}: ${it.value.trim()}" }
        }

        assertTrue(
            "A Google API key literal appears in a manifest or resource file:\n" +
                hits.joinToString("\n"),
            hits.isEmpty()
        )
    }

    private data class Hit(val path: String, val line: String)

    /** Every line of every Kotlin/gradle/XML file under the app module's main source set. */
    private fun scanFor(needle: String): List<Hit> {
        val sourceRoot = requireModuleDirectory().resolve("src/main")

        return sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension in setOf("kt", "kts", "xml", "json", "properties") }
            .flatMap { file ->
                file.readLines()
                    .withIndex()
                    .filter { (_, line) -> line.contains(needle) }
                    .map { Hit(file.path, it.value.trim()) }
            }
            .toList()
    }

    /**
     * The `app` module directory.
     *
     * Walks up from the working directory until it finds a directory holding both the module's
     * manifest sources and its build script. Gradle runs unit tests with the module directory as
     * the working directory, but an IDE or a plain JVM run may start in the repository root, so
     * walking up covers both without hard-coding either.
     *
     * `File("")` is deliberately not used as the starting point: `new File(new File(""), child)`
     * resolves against the filesystem root rather than the current directory, which is a quiet way
     * to look in the wrong place and find nothing.
     *
     * This throws rather than returning null. A scan that quietly found nothing because it looked
     * in the wrong place would report a pass while testing nothing at all — which for a security
     * assertion is worse than no test, because it looks like coverage. It did exactly that once:
     * `File("")` resolved to `C:\src\main\...`, and the tests were green while scanning nothing.
     */
    private fun requireModuleDirectory(): File {
        var candidate: File? = File(".").absoluteFile.normalize()

        while (candidate != null) {
            val isAppModule = File(candidate, "src/main/java/com/example").isDirectory &&
                File(candidate, "build.gradle.kts").isFile
            if (isAppModule) return candidate
            candidate = candidate.parentFile
        }

        error(
            "could not locate the app module directory walking up from " +
                "${File(".").absolutePath}; no ancestor contained both src/main/java/com/example " +
                "and build.gradle.kts. The source-scanning tests in this class would otherwise " +
                "pass without examining anything."
        )
    }
}