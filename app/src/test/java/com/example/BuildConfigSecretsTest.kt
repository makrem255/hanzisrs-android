package com.example

import org.junit.Assert.assertEquals
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
 * generated `BuildConfig` class, the app's own resources, the app's own sources, and the backend
 * proxy's own source. It cannot read a built APK. The end-to-end check for that — scanning
 * `classes.dex` and `resources.arsc` of a real release artifact — is part of the release procedure
 * in `docs/RELEASE.md`, because that is the only place it is actually meaningful.
 *
 * The backend proxy is scanned here, and not only in the app module, because the proxy holds the
 * billable key: the Gemini key lives in the proxy's environment, and the proxy's own source is
 * the only place in this repository where that key could plausibly end up as a literal. Before
 * this, `backend/` was covered by nothing at all — and this repository is public.
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

    /**
     * The backend proxy must not contain a Google API key literal.
     *
     * The Gemini key lives in the proxy's environment and nowhere else. The proxy's source is
     * tracked in a public repository, so a literal committed there is readable by anybody and
     * billable to whoever owns the key. Nothing else in the toolchain would notice: the build
     * succeeds, the existing tests pass, and the credential is simply there.
     *
     * Two shapes are recognised, and the difference between them is the whole design:
     *
     *  - `realistic` — `AIzaSy` plus at least 33 more key characters, which is the shape Google
     *    issues (39 characters in total). **Never tolerated**, anywhere, including on the fixture
     *    declaration lines themselves.
     *  - `key-like` — the `AIza` prefix plus at least 20 more key characters. Tolerated only on a
     *    documented fixture declaration, and only where the value is not realistic-shaped.
     *
     * The consequence is the property that matters: pasting a real key anywhere in `backend/`
     * fails this test, and there is no exemption path for it — not on a new variable, not on a
     * new line, not on a different file, and not on the fixture lines either.
     */
    @Test
    fun `no Google API key appears in the backend proxy source`() {
        val hits = scanBackendForKeyLiterals()

        assertTrue(
            "A Google API key literal appears in the backend proxy source:\n" +
                hits.joinToString("\n") {
                    "  ${it.relativePath}:${it.lineNumber}: ${it.shape} key literal (${it.length} characters)"
                } +
                "\n\nThe Gemini key belongs in the backend's environment, never in a file. This " +
                "repository is public, so a literal committed here is readable by anybody and " +
                "billable to whoever owns the key.",
            hits.isEmpty()
        )
    }

    /**
     * The exemption for the two backend fixtures must stay exactly as narrow as it is.
     *
     * The fixtures are legitimate — they are stand-ins that let the proxy tests run without a
     * real key — but an exemption that can grow is an exemption that will grow. Pinning the
     * table here means widening it has to be a deliberate, visible edit to this test rather than
     * a quiet addition that makes the scan tolerate one more thing.
     *
     * The table is keyed on (file, holder name) and never on a value, for the same reason the
     * scan exists: a value-based exemption would have to embed the fixture's text in this file.
     */
    @Test
    fun `the backend fixture exemption stays pinned to the two documented TEST_KEY declarations`() {
        assertEquals(
            "The backend fixture exemption must stay pinned to the two documented TEST_KEY " +
                "declarations. Widening it here is how a real key would end up tolerated.",
            listOf(
                "backend/e2e.test.mjs" to "TEST_KEY",
                "backend/server.test.mjs" to "TEST_KEY",
            ),
            backendFixtureExemptions.map { it.relativePath to it.holder }
        )
    }

    /**
     * The backend scan's file exclusion must stay exactly as narrow as it is.
     *
     * `backend/.env` is skipped because it is gitignored and holds the developer's real key.
     * Everything else in the proxy is scanned, including `backend/.env.example`, which is tracked
     * and must stay values-free. Pinning the set here means widening it has to be a deliberate,
     * visible edit to this test rather than a quiet addition that stops the scan looking at
     * something.
     */
    @Test
    fun `the backend scan skips only the gitignored env file`() {
        assertEquals(
            "The backend scan's file exclusion must stay pinned to the gitignored .env file. " +
                "Widening it here is how a tracked file could stop being scanned.",
            setOf(".env"),
            backendIgnoredSecretFileNames
        )
    }

    /**
     * The skip depends on `.env` being gitignored, so that dependency is asserted rather than
     * assumed.
     *
     * If the ignore rule is ever removed, `backend/.env` becomes committable — and the scan
     * would still be skipping it, because the scan reads the working tree, not the index.
     * Failing here says so loudly at the moment the protection is removed, rather than leaving it
     * to be discovered later.
     */
    @Test
    fun `git still ignores the env file that the backend scan skips`() {
        val gitignore = requireRepositoryDirectory().resolve(".gitignore")
        val ignoresEnv = gitignore.readLines().any { line ->
            val rule = line.trim()
            rule == ".env" || rule == "backend/.env" || rule == "**/.env"
        }

        assertTrue(
            "`.gitignore` no longer ignores .env files, but the backend scan still skips " +
                "`backend/.env`. A key in that file would become committable while going " +
                "unscanned, so either restore the ignore rule or narrow the scan's exclusion.",
            ignoresEnv
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
     * The two documented test fixtures in the backend proxy, and the only key-shaped literals the
     * backend scan tolerates.
     *
     * `backend/e2e.test.mjs` and `backend/server.test.mjs` each declare a `TEST_KEY` constant: a
     * stand-in for the Gemini key, so the proxy has something to forward and its tests can run
     * without a real credential. They are not secrets — the end-to-end test replaces
     * `globalThis.fetch`, so nothing ever reaches Google — but they are key-shaped, so the scan
     * has to allow exactly them.
     *
     * The exemption is narrow in three independent ways, and each one closes a different hole:
     *
     *  1. It is keyed on (file, holder name) and never on a value. A value-based exemption would
     *     have to embed the fixture's text in this file — which is exactly what the scan exists to
     *     prevent.
     *  2. It only covers the *declaration* of that holder, matched as `const`/`let`/`var` plus the
     *     holder name. A reassignment, a second constant, or a different variable in the same file
     *     is not covered.
     *  3. It never covers a realistic-shaped literal. A real key pasted onto the fixture
     *     declaration line itself still fails, so swapping a fixture for a real key is caught even
     *     though the line is exempted.
     *
     * The effect: there is no path by which a realistic key literal can live in `backend/` and
     * still pass this scan.
     */
    private data class FixtureExemption(val relativePath: String, val holder: String)

    private val backendFixtureExemptions = listOf(
        FixtureExemption("backend/e2e.test.mjs", "TEST_KEY"),
        FixtureExemption("backend/server.test.mjs", "TEST_KEY"),
    )

    /** A key-shaped literal found in the backend proxy source. */
    private data class BackendHit(
        val relativePath: String,
        val lineNumber: Int,
        val shape: String,
        val length: Int,
    )

    /**
     * The shape Google actually issues: `AIzaSy` plus at least 33 more key characters, 39 in total.
     *
     * This is a pattern, not a key: the character class cannot match itself, so the scan never
     * trips on its own source. No example key is written anywhere in this file for the same
     * reason.
     */
    private val realisticKeyPattern = Regex("""AIzaSy[A-Za-z0-9_-]{33,}""")

    /**
     * The looser shape: the `AIza` prefix plus a run of key characters.
     *
     * The length floor is what separates a key from a bare mention of the prefix in prose, which
     * is legitimate in a comment and in a template file.
     */
    private val keyLikePattern = Regex("""AIza[A-Za-z0-9_-]{20,}""")

    /** The file extensions in the backend proxy that the scan reads. */
    private val backendScannedExtensions =
        setOf("mjs", "js", "cjs", "json", "env", "example", "properties", "yml", "yaml", "txt", "md")

    /**
     * The one file name the scan is allowed to skip.
     *
     * `backend/.env` is gitignored (`.gitignore:18`) and is the sanctioned home for the real
     * Gemini key: the proxy reads `GEMINI_API_KEY` from its environment, and that environment is
     * populated from this file. It is never committed, so a key sitting in it is not exposed by
     * anything this repository does.
     *
     * Failing the build because a developer has their own key in their own ignored file would be
     * a false positive, and a false positive that fires on correct behaviour teaches people to
     * delete the file rather than to fix the real leak. The threat model here is "a secret
     * committed to a public repository", not "a secret present in a developer's local, ignored
     * file".
     *
     * The exclusion is by exact file name and nothing else. It is not a wildcard, not a
     * directory, and not "anything that looks like an env file": `.env.example` is a different
     * name, so it stays in scope — it is tracked, and it must stay values-free. The set is pinned
     * by a test, so it cannot be widened quietly, and a separate test asserts that `.gitignore`
     * still ignores `.env`, so the skip cannot outlive the protection it depends on.
     */
    private val backendIgnoredSecretFileNames = setOf(".env")

    /**
     * Every key-shaped literal in the backend proxy source that this scan refuses to tolerate.
     *
     * A `realistic` hit is always a failure. A `key-like` hit is a failure unless it sits on a
     * documented fixture declaration and is not realistic-shaped.
     */
    private fun scanBackendForKeyLiterals(): List<BackendHit> {
        val backendDirectory = requireBackendDirectory()
        val hits = mutableListOf<BackendHit>()

        val files = backendDirectory.walkTopDown()
            .filter { it.isFile && it.extension in backendScannedExtensions }
            .filter { it.name !in backendIgnoredSecretFileNames }
            .sortedBy { it.path }
            .toList()

        for (file in files) {
            val relativePath = "backend/" + file.relativeTo(backendDirectory).invariantSeparatorsPath
            val lines = file.readLines()

            for (index in lines.indices) {
                val line = lines[index]

                val realisticRanges = realisticKeyPattern.findAll(line).map { it.range }.toList()
                for (range in realisticRanges) {
                    hits += BackendHit(relativePath, index + 1, "realistic", range.last - range.first + 1)
                }

                for (match in keyLikePattern.findAll(line)) {
                    // A realistic literal is already reported above; reporting it twice would
                    // only make the failure message noisier.
                    val coveredByRealistic = realisticRanges.any { range ->
                        range.first <= match.range.first && match.range.last <= range.last
                    }
                    if (coveredByRealistic) continue

                    val exempt = backendFixtureExemptions.any { exemption ->
                        exemption.relativePath == relativePath &&
                            isFixtureDeclaration(line, exemption.holder)
                    }
                    if (!exempt) {
                        hits += BackendHit(relativePath, index + 1, "key-like", match.value.length)
                    }
                }
            }
        }
        return hits
    }

    /**
     * Whether this line *declares* the named holder, rather than merely mentioning it.
     *
     * Only a declaration is exempt. A reassignment, a second constant with the same value, or a
     * different variable in the same file is not a declaration of the exempt holder, so none of
     * them is covered by the exemption.
     */
    private fun isFixtureDeclaration(line: String, holder: String): Boolean {
        val declaration = Regex("""\b(?:const|let|var)\s+""" + Regex.escape(holder) + """\b\s*=""")
        return declaration.containsMatchIn(line)
    }

    /**
     * The repository root.
     *
     * The backend directory is a direct child of it, so the backend's parent is the root. It
     * throws rather than returning null for the same reason `requireBackendDirectory` does: a
     * lookup that quietly found nothing would make the test that uses it pass without examining
     * anything.
     */
    private fun requireRepositoryDirectory(): File {
        val root = requireBackendDirectory().parentFile
            ?: error(
                "the backend directory has no parent, so the repository root cannot be located. " +
                    "The test that reads .gitignore would otherwise pass without examining anything."
            )
        return root
    }

    /**
     * The backend proxy directory.
     *
     * Found by walking up from the app module directory looking for a sibling `backend` that
     * contains `server.mjs`. That is the same "walk up, do not hard-code" discipline as
     * `requireModuleDirectory`, and it throws rather than returning null for the same reason: a
     * scan that quietly found nothing because it looked in the wrong place would report a pass
     * while testing nothing at all.
     */
    private fun requireBackendDirectory(): File {
        var candidate: File? = requireModuleDirectory().parentFile

        while (candidate != null) {
            val backend = File(candidate, "backend")
            if (File(backend, "server.mjs").isFile) return backend
            candidate = candidate.parentFile
        }

        error(
            "could not locate the backend directory walking up from " +
                "${requireModuleDirectory().absolutePath}; no ancestor contained a backend/server.mjs. " +
                "The backend source-scanning test would otherwise pass without examining anything."
        )
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