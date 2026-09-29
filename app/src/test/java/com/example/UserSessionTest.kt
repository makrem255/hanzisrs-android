package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.auth.SessionStore
import com.example.data.auth.SessionToken
import com.example.data.db.AppDatabase
import com.example.data.model.StorageValues
import com.example.data.model.UserEntity
import com.example.data.repository.AuthResult
import com.example.data.repository.UserRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The session, across a process death.
 *
 * ## What was broken
 *
 * `UserRepository.autoLogin()` returned `null` unconditionally, with a comment saying a real
 * persistent session "requires a backend-issued credential". There is no backend: accounts are
 * local rows, the password hash is verified locally, and the whole app is offline. So the rule
 * excluded exactly the case that applied, and the app opened on the sign-in screen on every
 * launch. A learner could not leave and come back without typing their email and password again.
 *
 * Meanwhile the machinery to do it correctly was already written and never called:
 * [SessionToken] to mint and hash, [SessionStore] to hold the raw token in app-private
 * preferences and to count failed attempts, and `UserDao.findByToken` to resolve it. All of it
 * unreferenced, so all of it compiled, and none of it ran.
 *
 * ## How process death is simulated here
 *
 * By building a *second* [UserRepository] over the same database and the same [SessionStore].
 * That is the whole of what a relaunch is from the repository's point of view: a new object graph,
 * a live database file, and the preferences the previous process wrote. If restoration only
 * worked because the first repository still had the user in a `MutableStateFlow`, the second
 * repository would come back empty and this test would fail.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UserSessionTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var store: SessionStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        store = SessionStore(context)
        // Each test starts with no session and no failure history, so the backoff tests below
        // count from zero rather than inheriting the previous test's attempts.
        store.clear()
        store.clearFailures("reset@example.com")
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun repository() = UserRepository(
        userDao = database.userDao(),
        database = database,
        sessionStore = store
    )

    private suspend fun newUser(identifier: String): UserEntity {
        val id = database.userDao().insertUser(
            UserEntity(
                identifier = identifier,
                identifierNormalized = identifier.lowercase(),
                authType = StorageValues.AuthType.EMAIL.storageValue,
                passwordHash = "unused-by-this-test",
                displayName = identifier.substringBefore("@"),
                token = ""
            )
        )
        return database.userDao().getUserById(id)!!
    }

    // ---- the "return later" half of the journey ---------------------------------------------

    @Test
    fun `a learner who registered is still signed in on the next launch`() = runBlocking {
        val firstLaunch = repository()
        val registered = firstLaunch.register(
            identifier = "returning@example.com",
            passwordPlain = "correct horse battery",
            displayName = "",
            isPhone = false
        )
        assertTrue("registration failed: $registered", registered is AuthResult.Success)

        // A brand new object graph, as after process death. Nothing is carried over in memory.
        val secondLaunch = repository()
        assertNull("the session survived a rebuild that was never asked for anything", secondLaunch.currentUser.value)

        val restored = secondLaunch.autoLogin()

        assertNotNull("autoLogin returned null, so the learner was signed out by relaunching", restored)
        assertEquals("returning@example.com", restored!!.identifier)
        assertEquals(
            "the restored user must be the same account",
            (registered as AuthResult.Success).user.id,
            restored.id
        )
        assertEquals("currentUser was not populated by autoLogin", restored.id, secondLaunch.currentUser.value?.id)
    }

    @Test
    fun `a learner who signed in is still signed in on the next launch`() = runBlocking {
        newUser("signedin@example.com")
        // Give the account a real PBKDF2 hash, as register would have.
        database.userDao().updateCredentials(
            userId = database.userDao().findByIdentifier("signedin@example.com")!!.id,
            passwordHash = com.example.util.PasswordHasher.createHash("correct horse battery"),
            token = "",
            now = System.currentTimeMillis()
        )

        val firstLaunch = repository()
        val result = firstLaunch.login("signedin@example.com", "correct horse battery")
        assertTrue("login failed: $result", result is AuthResult.Success)

        val secondLaunch = repository()
        val restored = secondLaunch.autoLogin()

        assertNotNull("signing in did not leave a session that survived the relaunch", restored)
        assertEquals("signedin@example.com", restored!!.identifier)
    }

    @Test
    fun `the database holds a digest of the token rather than the token itself`() = runBlocking {
        repository().register(
            identifier = "digest@example.com",
            passwordPlain = "correct horse battery",
            displayName = "",
            isPhone = false
        )

        val raw = store.readToken()
        assertNotNull("no raw token was persisted", raw)
        val stored = database.userDao().findByIdentifier("digest@example.com")!!.token

        assertFalse(
            "the database contains the raw token, so a stolen database is a full credential",
            stored == raw
        )
        assertEquals(
            "users.token must be the SHA-256 digest of the persisted token",
            SessionToken.hash(raw!!),
            stored
        )
    }

    @Test
    fun `signing out forgets the session so the next launch asks again`() = runBlocking {
        repository().register(
            identifier = "leaving@example.com",
            passwordPlain = "correct horse battery",
            displayName = "",
            isPhone = false
        )
        assertNotNull(store.readToken())

        repository().logout()

        assertNull("logout left the device pointer in place", store.readToken())
        assertNull("logout did not end the in-memory session", repository().autoLogin())
    }

    @Test
    fun `a token that no longer resolves is cleared instead of retried forever`() = runBlocking {
        store.writeToken("a token no account ever issued")
        val repo = repository()

        assertNull("an unresolvable token restored a session", repo.autoLogin())
        assertNull(
            "a token the database cannot resolve must be forgotten, or every launch pays " +
                "a query that can only fail",
            store.readToken()
        )
    }

    @Test
    fun `a device with no session opens on the sign in screen`() = runBlocking {
        assertNull(repository().autoLogin())
    }

    // ---- failed sign-in attempts -------------------------------------------------------------

    @Test
    fun `repeated wrong passwords are throttled`() = runBlocking {
        newUser("target@example.com")
        database.userDao().updateCredentials(
            userId = database.userDao().findByIdentifier("target@example.com")!!.id,
            passwordHash = com.example.util.PasswordHasher.createHash("the real password"),
            token = "",
            now = System.currentTimeMillis()
        )
        val repo = repository()

        // Below the threshold, every attempt is genuinely evaluated.
        repeat(SessionStore.FAILURES_BEFORE_BACKOFF) { attempt ->
            val result = repo.login("target@example.com", "wrong password")
            assertTrue(
                "attempt ${attempt + 1} should have been evaluated and failed, was: $result",
                result is AuthResult.Error
            )
        }

        // Once the threshold is passed, even the correct password is refused, which is the
        // entire point: the backoff has to apply to the success path too, or an attacker who
        // knows the password simply waits out each delay and continues.
        val throttled = repo.login("target@example.com", "the real password")
        assertTrue("expected a throttle, was: $throttled", throttled is AuthResult.Error)
        assertTrue(
            "the throttle should say when to retry, was: ${(throttled as AuthResult.Error).message}",
            throttled.message.contains("Too many failed attempts")
        )
    }

    @Test
    fun `a successful sign in clears the failure history`() = runBlocking {
        newUser("forgiven@example.com")
        val id = database.userDao().findByIdentifier("forgiven@example.com")!!.id
        database.userDao().updateCredentials(
            userId = id,
            passwordHash = com.example.util.PasswordHasher.createHash("the real password"),
            token = "",
            now = System.currentTimeMillis()
        )
        val repo = repository()

        // Deliberately *below* the threshold. `FAILURES_BEFORE_BACKOFF` is 5 and the fifth
        // failure is what arms the delay, so signing in successfully afterwards is impossible -
        // correctly so: a delay that a correct guess could wave off would not be a delay. This
        // therefore tests the reachable case, a slip or two followed by the right password.
        repeat(2) { repo.login("forgiven@example.com", "wrong") }
        assertTrue(store.failureCount("forgiven@example.com") > 0)

        val result = repo.login("forgiven@example.com", "the real password")

        assertTrue("the correct password was rejected: $result", result is AuthResult.Success)
        assertEquals(
            "the failure history was not cleared, so the next slip would be throttled",
            0,
            store.failureCount("forgiven@example.com")
        )
    }

    @Test
    fun `an unknown identifier is throttled like a wrong password`() = runBlocking {
        val repo = repository()

        repeat(SessionStore.FAILURES_BEFORE_BACKOFF) { repo.login("nobody@example.com", "whatever") }

        val throttled = repo.login("nobody@example.com", "whatever")
        assertTrue(
            "guessing a non-existent identifier must not be free; the delay is otherwise a " +
                "clean oracle for which accounts exist",
            throttled is AuthResult.Error && throttled.message.contains("Too many failed attempts")
        )
    }

    @Test
    fun `a throttled identifier does not lock out a different one`() = runBlocking {
        val repo = repository()
        repeat(SessionStore.FAILURES_BEFORE_BACKOFF) { repo.login("burnt@example.com", "wrong") }

        newUser("innocent@example.com")
        val id = database.userDao().findByIdentifier("innocent@example.com")!!.id
        database.userDao().updateCredentials(
            userId = id,
            passwordHash = com.example.util.PasswordHasher.createHash("the real password"),
            token = "",
            now = System.currentTimeMillis()
        )

        val result = repo.login("innocent@example.com", "the real password")
        assertTrue("one account's lockout spilled onto another: $result", result is AuthResult.Success)
    }
}
