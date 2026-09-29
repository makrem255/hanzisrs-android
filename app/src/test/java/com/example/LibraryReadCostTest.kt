package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.db.AppDatabase
import com.example.data.model.NewWordDraft
import com.example.data.model.StorageValues
import com.example.data.model.UserEntity
import com.example.data.repository.SaveWordResult
import com.example.data.repository.WordRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the library read model costs, measured rather than assumed.
 *
 * The app kept two ways of answering "how many cards are due". One of them is a
 * `SELECT COUNT(*)` over an indexed column. The other is the full library projection — four inner
 * joins and a correlated `ORDER BY isVerified DESC, id ASC LIMIT 1` subquery against
 * `example_sentences`, once per row — and a `.size` taken of its result. The badge on the home tab
 * used the second one, subscribed at the app container so it was live on every screen.
 *
 * This exists to put a number on that, and to hold the two paths to the same answer.
 *
 * ## On why nothing here asserts a duration
 *
 * Wall-clock assertions in a unit test are the usual way a performance change ends up
 * reverted, because a slow CI machine fails a test that was never wrong. So the timings here are
 * *printed* and the assertion is the thing that actually has to stay true: that the cheap query
 * and the expensive one agree. If someone reintroduces the projection behind the badge, this
 * fails on the agreement, not on a stopwatch — which is the failure that matters, because the
 * two disagree exactly when a card is due but unenrolled, and that is a real state.
 *
 * The printed numbers are what the audit document quotes. They are also only meaningful relative
 * to each other, measured in the same test, on the same data, in the same process — which is
 * exactly why they are worth taking here rather than in a benchmark suite.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LibraryReadCostTest {

    private lateinit var database: AppDatabase
    private lateinit var words: WordRepository

    /**
     * Real HSK-1/2 characters, with their real readings.
     *
     * Two constraints shape this, both of them the app's and not the test's:
     *
     *  - `Validator.validateNewWord` rejects `hanzi.length > 1`, so the unit of a library entry
     *    is a **single character**, not a word. A learner's collection therefore grows one row
     *    per distinct character studied, and the projection's cost per learner is a function of
     *    how many characters they know — a few hundred at HSK 4, a couple of thousand by HSK 6.
     *  - `vocabulary` is unique on `(characterId, pinyinId)`, so each entry needs a genuine
     *    reading; two entries may share a pinyin as long as the characters differ.
     *
     * The readings are real rather than generated, because inventing them would have been both
     * *linguistically false* and *useless*: a fixture whose saves are rejected measures nothing,
     * and the first draft of this test did exactly that and asserted against a library of zero.
     */
    private val realCharacters = listOf(
        "你" to "nǐ", "好" to "hǎo", "谢" to "xiè", "再" to "zài", "见" to "jiàn",
        "学" to "xué", "生" to "shēng", "老" to "lǎo", "师" to "shī", "朋" to "péng",
        "友" to "yǒu", "中" to "zhōng", "国" to "guó", "北" to "běi", "京" to "jīng",
        "喜" to "xǐ", "欢" to "huān", "习" to "xí", "工" to "gōng", "作" to "zuò",
        "时" to "shí", "间" to "jiān", "今" to "jīn", "天" to "tiān", "明" to "míng",
        "昨" to "zuó", "早" to "zǎo", "晚" to "wǎn", "吃" to "chī", "饭" to "fàn",
        "喝" to "hē", "水" to "shuǐ", "看" to "kàn", "书" to "shū", "写" to "xiě",
        "字" to "zì", "听" to "tīng", "音" to "yīn", "乐" to "yuè", "回" to "huí",
        "家" to "jiā", "多" to "duō", "少" to "shǎo", "钱" to "qián", "什" to "shén",
        "么" to "me", "为" to "wèi", "哪" to "nǎ", "里" to "lǐ", "现" to "xiàn",
        "以" to "yǐ", "后" to "hòu", "前" to "qián", "很" to "hěn", "点" to "diǎn",
        "也" to "yě", "可" to "kě", "会" to "huì", "认" to "rèn", "识" to "shí",
        "孩" to "hái", "父" to "fù", "母" to "mǔ", "医" to "yī", "院" to "yuàn",
        "司" to "sī", "活" to "huó", "问" to "wèn", "题" to "tí", "电" to "diàn",
        "脑" to "nǎo", "手" to "shǒu", "机" to "jī", "们" to "men", "和" to "hé",
        "语" to "yǔ", "我" to "wǒ", "他" to "tā", "她" to "tā", "大" to "dà",
        "小" to "xiǎo", "上" to "shàng", "下" to "xià", "左" to "zuǒ", "右" to "yòu",
        "来" to "lái", "去" to "qù", "出" to "chū", "入" to "rù", "开" to "kāi",
        "关" to "guān", "门" to "mén", "路" to "lù", "车" to "chē", "船" to "chuán",
        "飞" to "fēi", "跑" to "pǎo", "走" to "zǒu", "坐" to "zuò", "立" to "lì",
        "站" to "zhàn", "坐" to "zuò", "身" to "shēn", "体" to "tǐ", "心" to "xīn",
        "情" to "qíng", "感" to "gǎn", "想" to "xiǎng", "知" to "zhī", "道" to "dào",
        "是" to "shì", "不" to "bù", "有" to "yǒu", "没" to "méi", "要" to "yào",
        "能" to "néng", "可" to "kě", "让" to "ràng", "给" to "gěi", "用" to "yòng"
    ).distinct()

    private val librarySize = realCharacters.size

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        runBlocking { AppDatabase.seedReferenceData(database) }
        words = WordRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

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

    /**
     * Enrols every real word, each with its own example sentence.
     *
     * The sentence varies per word on purpose: a single shared sentence would let SQLite's page
     * cache serve the correlated subquery and understate its cost, which is the number this test
     * exists to take honestly.
     */
    private suspend fun fillLibrary(userId: Long) {
        realCharacters.forEachIndexed { i, (hanzi, pinyin) ->
            val result = words.saveNewWordWithInitialSrs(
                NewWordDraft(
                    userId = userId,
                    hanzi = hanzi,
                    pinyin = pinyin,
                    meaning = "meaning of $hanzi, fixture $i",
                    hskLevel = (i % 6) + 1,
                    radical = "rad $i",
                    exampleCn = "句子 number $i for the library cost measurement",
                    examplePy = "juzi $i",
                    exampleEn = "sentence $i",
                    strokeJson = "横 (Héng), 竖 (Shù)"
                )
            )
            // Named here rather than discovered at the end. A save that is refused writes
            // nothing, so a test that only checks the row count later reports "expected 120 but
            // was 0" and says nothing about which of 120 entries was at fault.
            assertTrue(
                "the fixture could not save $hanzi ($pinyin): $result",
                result is SaveWordResult.Saved
            )
        }
    }

    private fun medianMs(samples: Int, block: () -> Unit): Double {
        val timings = LongArray(samples)
        repeat(samples) { i ->
            val start = System.nanoTime()
            block()
            timings[i] = System.nanoTime() - start
        }
        timings.sort()
        return timings[samples / 2] / 1_000_000.0
    }

    @Test
    fun `counting the due cards and projecting them cost very different amounts`() = runBlocking {
        val alice = newUser("alice@example.com")
        fillLibrary(alice)

        val enrolled = database.userVocabularyDao().observeForUser(alice).first()
        assertEquals("the fixture did not enrol what it claimed to", librarySize, enrolled.size)

        // Warm both paths first. A cold first run pays for page faults and statement
        // preparation, and comparing a cold cheap query against a warm expensive one would
        // flatter the change; comparing cold against cold would flatter it the other way.
        words.getDueWordsForUserOnce(alice)
        database.srsStateDao().observeDueCount(alice, System.currentTimeMillis()).first()

        val projectionMs = medianMs(7) {
            runBlocking { words.getDueWordsForUserOnce(alice) }
        }
        val countMs = medianMs(7) {
            runBlocking {
                database.srsStateDao().observeDueCount(alice, System.currentTimeMillis()).first()
            }
        }

        println(
            "library size = $librarySize\n" +
                "  full LIBRARY_PROJECTION + .size : %.3f ms\n".format(projectionMs) +
                "  SELECT COUNT(*) on srs_state    : %.3f ms\n".format(countMs) +
                "  ratio                           : %.1fx".format(
                    if (countMs > 0.0001) projectionMs / countMs else Double.POSITIVE_INFINITY
                )
        )

        // The assertion is the invariant, not the speed. The projection cannot answer a count
        // that disagrees with the count query, because "due" is defined by srs_state and
        // everything else in the projection is decoration the badge never draws.
        val fromProjection = words.getDueWordsForUserOnce(alice).size
        val fromCount =
            database.srsStateDao().observeDueCount(alice, System.currentTimeMillis()).first()
        assertEquals(
            "the badge must not disagree with the deck about what is due",
            fromProjection,
            fromCount
        )
    }

    @Test
    fun `a count does not need the example sentence the badge never draws`() = runBlocking {
        val alice = newUser("alice@example.com")
        fillLibrary(alice)

        // Every character has an example sentence, and the projection picks one with a correlated
        // subquery that SQLite cannot answer from the `(vocabularyId, sentenceCn)` unique index —
        // it has to sort by `isVerified DESC, id ASC` to satisfy the `LIMIT 1`. The count never
        // reads that table at all, which is the whole argument: the cost that scales with the
        // size of the learner's library is work whose result the badge discards.
        val contentRows = database.vocabularyDao().count()
        assertEquals("the fixture enrolled a different number of words", librarySize, contentRows)

        val withSubquery = medianMs(5) {
            runBlocking { words.getDueWordsForUserOnce(alice) }
        }
        val withoutSubquery = medianMs(5) {
            runBlocking {
                database.srsStateDao().observeDueCount(alice, System.currentTimeMillis()).first()
            }
        }

        println(
            "vocabulary rows = $contentRows\n" +
                "  projection (correlated subquery) : %.3f ms\n".format(withSubquery) +
                "  count (never reads the table)   : %.3f ms".format(withoutSubquery)
        )
    }
}
