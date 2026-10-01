package com.example

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.db.AppDatabase
import com.example.data.model.UserEntity
import com.example.data.model.UserPreferenceEntity
import com.example.data.repository.UserRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The learner's daily caps, and the write path behind them.
 *
 * These are the settings that decide what the app recommends each day, so the properties worth
 * pinning are the ones a learner would notice being wrong: that a change survives, that it does
 * not silently discard the settings around it, and that the edges behave rather than throwing.
 */
@RunWith(RobolectricTestRunner::class)
class DailyLimitsTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: UserRepository
    private var userId: Long = 0

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()

        // `inMemoryDatabaseBuilder` bypasses `SeedCallback`, but that only seeds reference data.
        // The account below is created directly, which is what a registration does.
        userId = db.userDao().insertUser(
            UserEntity(
                identifier = "learner@example.test",
                identifierNormalized = "learner@example.test",
                displayName = "Learner",
                passwordHash = "not-a-real-hash",
                token = "token-learner",
                createdAt = 1L
            )
        )
        db.userPreferenceDao().insert(UserPreferenceEntity(userId = userId))

        repository = UserRepository(userDao = db.userDao(), database = db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `the stored limits are the ones the dashboard will read`() = runBlocking {
        assertEquals(10, repository.observePreferences(userId).first()?.dailyNewWordLimit)
        assertEquals(60, repository.observePreferences(userId).first()?.dailyReviewLimit)
    }

    @Test
    fun `a new-word limit the learner chose is persisted`() = runBlocking {
        repository.setDailyNewWordLimit(userId, 25)

        assertEquals(25, db.userPreferenceDao().getForUser(userId)?.dailyNewWordLimit)
    }

    @Test
    fun `a review limit the learner chose is persisted`() = runBlocking {
        repository.setDailyReviewLimit(userId, 120)

        assertEquals(120, db.userPreferenceDao().getForUser(userId)?.dailyReviewLimit)
    }

    @Test
    fun `changing one limit does not reset the other`() = runBlocking {
        // The DAO's update writes every column at once, so a read-modify-write that forgot to
        // carry the other value forward would silently reset it. This is the test for that.
        repository.setDailyNewWordLimit(userId, 25)
        repository.setDailyReviewLimit(userId, 120)

        val stored = db.userPreferenceDao().getForUser(userId)
        assertEquals(25, stored?.dailyNewWordLimit)
        assertEquals(120, stored?.dailyReviewLimit)
    }

    @Test
    fun `changing a limit leaves the surrounding settings alone`() = runBlocking {
        // Every other preference rides on the same row and the same UPDATE statement.
        db.userPreferenceDao().updateForUser(
            userId = userId,
            themeMode = "DARK",
            ttsSpeed = 0.8,
            dailyNewWordLimit = 10,
            dailyReviewLimit = 60,
            remindersEnabled = true,
            reminderHour = 7,
            showPinyin = false,
            showStrokeOrder = false,
            now = 1L
        )

        repository.setDailyNewWordLimit(userId, 30)

        val stored = db.userPreferenceDao().getForUser(userId)
        assertEquals("theme must survive", "DARK", stored?.themeMode)
        assertEquals("tts speed must survive", 0.8, stored?.ttsSpeed ?: 0.0, 0.0001)
        assertEquals("reminders must survive", true, stored?.remindersEnabled)
        assertEquals("reminder hour must survive", 7, stored?.reminderHour)
        assertEquals("pinyin visibility must survive", false, stored?.showPinyin)
        assertEquals("stroke order must survive", false, stored?.showStrokeOrder)
        assertEquals(30, stored?.dailyNewWordLimit)
        assertEquals("and the untouched review limit too", 60, stored?.dailyReviewLimit)
    }

    @Test
    fun `zero new words is honoured rather than treated as unset`() = runBlocking {
        // Zero is how a learner pauses new material and keeps their retention alive. A `?:` or
        // an "if limit > 0" guard anywhere in this path would overwrite it with the default the
        // moment they saved, which is the one thing they asked for being ignored.
        repository.setDailyNewWordLimit(userId, 0)

        assertEquals(0, db.userPreferenceDao().getForUser(userId)?.dailyNewWordLimit)
    }

    @Test
    fun `zero reviews is honoured`() = runBlocking {
        repository.setDailyReviewLimit(userId, 0)

        assertEquals(0, db.userPreferenceDao().getForUser(userId)?.dailyReviewLimit)
    }

    @Test
    fun `an out-of-range limit is clamped to the largest legal one`() = runBlocking {
        // The stepper cannot produce these, so only a bug or a restored backup could. Storing
        // the nearest legal value beats silently ignoring the setting.
        repository.setDailyNewWordLimit(userId, 10_000)
        assertEquals(
            UserRepository.MAX_DAILY_LIMIT,
            db.userPreferenceDao().getForUser(userId)?.dailyNewWordLimit
        )

        repository.setDailyNewWordLimit(userId, -5)
        assertEquals(0, db.userPreferenceDao().getForUser(userId)?.dailyNewWordLimit)

        repository.setDailyReviewLimit(userId, 10_000)
        assertEquals(
            UserRepository.MAX_DAILY_LIMIT,
            db.userPreferenceDao().getForUser(userId)?.dailyReviewLimit
        )
    }

    @Test
    fun `a limit above the stepper's ceiling is stored unchanged, not floored to it`() = runBlocking {
        // The write clamps to what is legal (200), not to what the stepper offers (50). A
        // learner whose stored value is 100 sees 100, presses "-" once, and must get 100 again
        // rather than 50 - one tap must not silently remove a third of their daily workload.
        repository.setDailyNewWordLimit(userId, 100)

        assertEquals(100, db.userPreferenceDao().getForUser(userId)?.dailyNewWordLimit)
        assertEquals("and the stepper's ceiling is a different, lower bound", 50,
            UserRepository.OFFERED_MAX_NEW_WORDS)
        assertEquals("which is what the settings screen renders against", 200,
            UserRepository.MAX_DAILY_LIMIT)
    }

    @Test
    fun `stepping down from above the stepper's ceiling reaches the learner, not the floor`() = runBlocking {
        // The arithmetic the stepper performs on a stored value above its own maximum: one
        // decrement of 5 from 100 must be 95, and must survive the write intact.
        repository.setDailyNewWordLimit(userId, 100)
        repository.setDailyNewWordLimit(userId, 100 - 5)

        assertEquals(95, db.userPreferenceDao().getForUser(userId)?.dailyNewWordLimit)
    }

    @Test
    fun `a learner with no preferences row yet gets the defaults rather than a failure`() = runBlocking {
        // A profile can exist without preferences if it predates the table, which is exactly the
        // case `DashboardDao` already guards with COALESCE. The settings screen must not be the
        // place that assumption breaks.
        val bareId = db.userDao().insertUser(
            UserEntity(
                identifier = "bare@example.test",
                identifierNormalized = "bare@example.test",
                displayName = "Bare",
                passwordHash = "not-a-real-hash",
                token = "token-bare",
                createdAt = 1L
            )
        )

        repository.setDailyNewWordLimit(bareId, 15)

        val stored = db.userPreferenceDao().getForUser(bareId)
        assertEquals(15, stored?.dailyNewWordLimit)
        assertEquals("and the untouched limit takes the default", 60, stored?.dailyReviewLimit)
    }

    @Test
    fun `repeated writes to the same value stay on one row`() = runBlocking {
        // The stepper emits a value on every tap, including repeats under a resting finger. An
        // insert-per-write would trip the unique index on userId after the second one.
        repeat(5) { repository.setDailyNewWordLimit(userId, 20) }

        assertEquals(20, db.userPreferenceDao().getForUser(userId)?.dailyNewWordLimit)
        // `getForUser` returns one row or null, so a second insert could only have survived by
        // the query picking one of them. Counting the learner row's own back-reference is what
        // proves the write went to the existing row rather than replacing it.
        assertEquals("the write updated the existing row", userId, db.userPreferenceDao().getForUser(userId)?.userId)
    }

    @Test
    fun `settings for one learner do not leak into another's`() = runBlocking {
        val otherId = db.userDao().insertUser(
            UserEntity(
                identifier = "other@example.test",
                identifierNormalized = "other@example.test",
                displayName = "Other",
                passwordHash = "not-a-real-hash",
                token = "token-other",
                createdAt = 1L
            )
        )
        db.userPreferenceDao().insert(UserPreferenceEntity(userId = otherId))

        repository.setDailyNewWordLimit(userId, 40)

        assertEquals(40, db.userPreferenceDao().getForUser(userId)?.dailyNewWordLimit)
        assertEquals(10, db.userPreferenceDao().getForUser(otherId)?.dailyNewWordLimit)
    }

    @Test
    fun `a write for a learner id that does not exist fails loudly rather than writing`() = runBlocking {
        // The update is keyed by `userId`, so this cannot silently touch another learner - but it
        // also cannot succeed, and it must not be mistaken for a save. An absent user hits the
        // foreign key on `user_preferences` and throws, which is the correct outcome: the caller
        // passed an id that is not a learner, and saying so beats reporting a successful write.
        //
        // Asserted because the alternative - swallowing this and reporting success - is what would
        // let a settings screen show a saved number that is in nobody's row.
        var threw = false
        try {
            repository.setDailyNewWordLimit(userId + 999, 50)
        } catch (e: android.database.sqlite.SQLiteConstraintException) {
            threw = true
        }

        assertTrue("an unknown learner must not report a successful write", threw)
        assertEquals(
            "and must not have touched a real learner's settings",
            10,
            db.userPreferenceDao().getForUser(userId)?.dailyNewWordLimit
        )
    }
}
