package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.db.AppDatabase
import com.example.data.model.NewWordDraft
import com.example.data.model.StorageValues
import com.example.data.model.UserEntity
import com.example.data.repository.DashboardRepository
import com.example.data.repository.SaveWordResult
import com.example.data.repository.SrsRepository
import com.example.data.repository.WordRepository
import com.example.data.srs.SrsRating
import com.example.data.srs.StudyDay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Which study day a review belongs to, from both ends of the write and the read.
 *
 * ### The bug this exists for
 *
 * The two sides of this boundary disagreed. `SrsRepository` bucketed a review with
 * `floorDiv(millis, 86_400_000)` — UTC — while `DashboardRepository` converted through the
 * learner's `ZoneId`. Under UTC the two implementations are identical, which is why
 * `DashboardRepositoryTest` pinned its zone to `ZoneOffset.UTC` and stayed green through all of
 * it. Outside UTC they are not: a review answered between local midnight and 00:00 UTC was
 * written into one day's row and read back out of another's, so the learner studied and the
 * dashboard showed nothing.
 *
 * Every case below is at a local-midnight boundary, in a zone with a non-zero offset, because a
 * test at UTC cannot fail for this reason no matter how wrong the code is.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StudyDayTest {

    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        runBlocking { AppDatabase.seedReferenceData(database) }
    }

    @After
    fun tearDown() {
        database.close()
    }

    // ---- the shared function ----------------------------------------------------------------------------

    /**
     * Zones chosen for the property, not for familiarity.
     *
     * `Asia/Shanghai` is UTC+8, so its local day starts *before* the UTC one; `America/Chicago`
     * is UTC−6 in January, so its local day starts *after*. An implementation that buckets in UTC
     * disagrees with both, in opposite directions, and no single UTC-based test would catch it.
     */
    private val eastOfUtc = ZoneId.of("Asia/Shanghai")
    private val westOfUtc = ZoneId.of("America/Chicago")

    /**
     * When the words are enrolled, so the roll-up row that write creates is on a day this test
     * chose rather than on whatever day the machine is running.
     *
     * Pinned because `saveNewWordWithInitialSrs` writes `daily_stats` — the whole reason it takes
     * a `zone`. Left on the wall clock it opens a row on the real today, which lands in
     * `recentDays` of the dashboard these tests read, and the boundaries under test stop being
     * the only thing in the history.
     */
    private val ENROLLED_AT = Instant.parse("2026-02-28T12:00:00Z").toEpochMilli()

    @Test
    fun `a local midnight belongs to the local day, in both directions`() {
        // 2026-03-01T00:30+08:00 — already the 1st locally, but still the 28th in UTC.
        val shanghaiMidnight = Instant.parse("2026-02-28T16:30:00Z").toEpochMilli()
        assertEquals(
            "a review just after local midnight in UTC+8 is on the 1st locally, not the 28th",
            LocalDate.of(2026, 3, 1).toEpochDay(),
            StudyDay.epochDayOf(shanghaiMidnight, eastOfUtc).toLong()
        )
        assertEquals(
            "and on the 28th in UTC, which is what the old write path computed",
            LocalDate.of(2026, 2, 28).toEpochDay(),
            StudyDay.epochDayOf(shanghaiMidnight, ZoneOffset.UTC).toLong()
        )

        // 2026-03-01T23:30−06:00 — still the 1st locally, but already the 2nd in UTC.
        val chicagoNight = Instant.parse("2026-03-02T05:30:00Z").toEpochMilli()
        assertEquals(
            "a review just before local midnight in UTC-6 is on the 1st locally, not the 2nd",
            LocalDate.of(2026, 3, 1).toEpochDay(),
            StudyDay.epochDayOf(chicagoNight, westOfUtc).toLong()
        )
        assertEquals(
            "and on the 2nd in UTC",
            LocalDate.of(2026, 3, 2).toEpochDay(),
            StudyDay.epochDayOf(chicagoNight, ZoneOffset.UTC).toLong()
        )
    }

    @Test
    fun `the zone genuinely changes the answer, so a passing UTC test proves nothing`() {
        val instant = Instant.parse("2026-02-28T16:30:00Z").toEpochMilli()

        assertEquals(
            "the two zones must disagree here or none of the boundary tests above mean anything",
            true,
            StudyDay.epochDayOf(instant, eastOfUtc) != StudyDay.epochDayOf(instant, westOfUtc)
        )
    }

    @Test
    fun `the end of a local day is the start of the next, across a daylight-saving change`() {
        // US DST began 2026-03-08, making that local day 23 hours long. A day boundary computed
        // as `epochDay * 86_400_000` is 86,400,000ms out here — an hour of "due today" either
        // silently included or silently dropped.
        val dstDay = LocalDate.of(2026, 3, 8).toEpochDay().toInt()
        val start = StudyDay.startOfEpochDayMillis(dstDay, westOfUtc)
        val end = StudyDay.endOfEpochDayMillis(dstDay, westOfUtc)

        assertEquals(
            "the 8th is 23 hours long in Chicago, not 24",
            23L * 60L * 60L * 1000L,
            end - start
        )
        assertEquals(
            "and the next day starts exactly where it ended",
            end,
            StudyDay.startOfEpochDayMillis(dstDay + 1, westOfUtc)
        )
    }

    // ---- the two sides of the write/read boundary --------------------------------------------------------

    /**
     * The test the original code would have failed.
     *
     * It writes a review through `SrsRepository` in a learner's own zone and then reads the
     * dashboard back for that same instant. Before the fix the two used different functions and
     * this pair disagreed across every instant near local midnight.
     */
    @Test
    fun `a review answered just after local midnight is counted on the day it happened`() = runTest {
        listOf(
            "UTC+8, where local midnight is 16:30 UTC the day before" to ZoneId.of("Asia/Shanghai"),
            "UTC-6, where local midnight is 06:00 UTC the same day" to ZoneId.of("America/Chicago")
        ).forEach { (label, zone) ->
            val userId = newUser("alice-${label.hashCode()}@example.com")
            val words = WordRepository(database, zone) { ENROLLED_AT }
            val enrolmentId = enrol(words, userId, "海", "hai", "sea")

            // 20 minutes after this zone's local midnight: the exact instant where UTC bucketing
            // and local bucketing part company.
            val localMidnight = LocalDate.of(2026, 3, 1)
                .atStartOfDay(zone).toInstant().toEpochMilli()
            val reviewAt = localMidnight + 20L * 60L * 1000L

            SrsRepository(database, zone).processReview(
                userVocabularyId = enrolmentId,
                userId = userId,
                rating = SrsRating.GOOD,
                now = reviewAt
            )

            val snapshot =
                DashboardRepository(database, zone) { reviewAt }.observeDashboard(userId).first()

            assertEquals(
                "in $label the review was written to one day and read back from another",
                1,
                snapshot.today?.reviewsCompleted ?: 0
            )
            assertEquals(
                "in $label it landed on 1 March locally",
                LocalDate.of(2026, 3, 1).toEpochDay().toInt(),
                snapshot.today?.epochDay
            )
        }
    }

    /**
     * The other direction: a review from *yesterday* must not leak into today.
     *
     * The bug could have been fixed by making both sides UTC, which would have satisfied the test
     * above. This pins the half that rules that out — a stale day stays stale.
     */
    @Test
    fun `a review from yesterday is not counted as today`() = runTest {
        val zone = eastOfUtc
        val userId = newUser("bob@shanghai")
        val words = WordRepository(database, zone) { ENROLLED_AT }
        val enrolmentId = enrol(words, userId, "山", "shan", "mountain")

        val yesterday = LocalDate.of(2026, 3, 1)
            .atStartOfDay(zone).toInstant().toEpochMilli() + 20L * 60L * 1000L
        val today = LocalDate.of(2026, 3, 2)
            .atStartOfDay(zone).toInstant().toEpochMilli() + 20L * 60L * 1000L

        SrsRepository(database, zone).processReview(
            userVocabularyId = enrolmentId,
            userId = userId,
            rating = SrsRating.GOOD,
            now = yesterday
        )

        val snapshot =
            DashboardRepository(database, zone) { today }.observeDashboard(userId).first()

        assertEquals(
            "yesterday's review must not appear in today's total",
            0,
            snapshot.today?.reviewsCompleted ?: 0
        )
        assertEquals(
            "and yesterday is still counted on its own day, so it was not lost either",
            1,
            snapshot.recentDays.firstOrNull { it.epochDay == LocalDate.of(2026, 3, 1).toEpochDay().toInt() }
                ?.reviewsCompleted ?: 0
        )
    }

    // ---- helpers ------------------------------------------------------------------------------------------

    private suspend fun newUser(identifier: String): Long = database.userDao().insertUser(
        UserEntity(
            identifier = identifier,
            identifierNormalized = identifier.lowercase(),
            authType = StorageValues.AuthType.EMAIL.storageValue,
            passwordHash = "unused-by-these-tests",
            displayName = identifier.substringBefore("@"),
            token = "token-$identifier"
        )
    )

    private suspend fun enrol(
        words: WordRepository,
        userId: Long,
        hanzi: String,
        pinyin: String,
        meaning: String
    ): Long = (words.saveNewWordWithInitialSrs(
        NewWordDraft(
            userId = userId,
            hanzi = hanzi,
            pinyin = pinyin,
            meaning = meaning,
            hskLevel = 1,
            radical = "rad",
            exampleCn = "句子 $hanzi",
            examplePy = "juzi $pinyin",
            exampleEn = "a sentence",
            strokeJson = "横"
        )
    ) as SaveWordResult.Saved).userVocabularyId
}
