package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.ai.GeminiAiService
import com.example.data.db.AppDatabase
import com.example.data.model.NewWordDraft
import com.example.data.model.StorageValues
import com.example.data.model.UserEntity
import com.example.data.model.WordWithSrs
import com.example.data.repository.SaveWordResult
import com.example.data.repository.WordRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Two rules the app kept breaking about data the learner supplied.
 *
 * **What the learner typed must reach the database.** `NewWordDraft.radical` was validated and
 * carried all the way into the save, and then `WordRepository.resolveCharacterId` hard-coded
 * `radical = ""` when it created the character row. The library projection reads that column and
 * the library and deck screens render it, so every word added through Add Word showed a blank
 * radical cell no matter what had been typed or accepted on the review-and-approve screen.
 *
 * **Absent data must be absent, not invented.** Three separate places supplied a plausible-looking
 * value for a field nobody had: a live model response that omitted `radical` came back with the
 * literal Chinese *word* "radical" and a four-stroke breakdown, and the offline fallback invented
 * a reading, a radical and a five-stroke sequence for any character at all. Those were then
 * stored as model output. A wrong stroke count is worse than a missing one, because the app
 * animates it as though it were how the character is written.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SuppliedDataIntegrityTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var repository: WordRepository
    private lateinit var ai: GeminiAiService
    private var userId: Long = 0

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        // Required before any word is saved: `vocabulary.levelId` is a RESTRICT foreign key
        // onto `learning_levels`, and an in-memory database never runs `SeedCallback`.
        AppDatabase.seedReferenceData(database)
        repository = WordRepository(database)
        // `offlineSampleFor` is a dictionary lookup with no network behind it, so the OkHttp
        // client the constructor builds is never used. It is built regardless because the
        // service exposes both entry points on one type, and the alternative - a second
        // dictionary type - would be a second source of truth for the same words.
        ai = GeminiAiService()
        userId = database.userDao().insertUser(
            UserEntity(
                identifier = "integrity@example.com",
                identifierNormalized = "integrity@example.com",
                passwordHash = "unused",
                displayName = "Integrity",
                token = ""
            )
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun draft(
        hanzi: String,
        radical: String,
        pinyin: String = "xué",
        meaning: String = "to study"
    ) = NewWordDraft(
        userId = userId,
        hanzi = hanzi,
        pinyin = pinyin,
        meaning = meaning,
        hskLevel = 1,
        radical = radical,
        exampleCn = "你好，我在学习中文。",
        examplePy = "Nǐ hǎo, wǒ zài xuéxí zhōngwén.",
        exampleEn = "Hello, I am learning Chinese.",
        strokeJson = "点 (Diǎn), 点 (Diǎn), 撇 (Piě), 点 (Diǎn), 横钩 (Héng Gōu), " +
            "横撇 (Héng Piě), 弯钩 (Wān Gōu), 横 (Héng)",
        source = StorageValues.VocabularySource.MANUAL.storageValue,
        provenance = StorageValues.ContentProvenance.UNKNOWN.storageValue
    )

    // ---- what the learner typed reaches the database -------------------------------------------

    @Test
    fun `a radical typed by the learner is stored on the character row`() = runBlocking {
        val result = repository.saveNewWordWithInitialSrs(
            draft(hanzi = "海", radical = "氵 (water)"),
            initialDueImmediate = true
        )
        assertTrue(
            "save refused: $result",
            result is SaveWordResult.Saved
        )

        val character = database.characterDao().getByCharacter("海")
        assertNotNull("no character row was created for 海", character)
        assertEquals(
            "the radical the learner supplied was discarded on the way to the database",
            "氵 (water)",
            character!!.radical
        )
    }

    @Test
    fun `the stored radical is what the library projection returns`() = runBlocking {
        repository.saveNewWordWithInitialSrs(
            draft(hanzi = "海", radical = "氵 (water)"),
            initialDueImmediate = true
        )

        // This is the path the screens actually read - the repository's own mapping of the
        // projection - so asserting on the character row alone would miss a projection that
        // dropped the column on the way out.
        val library: List<WordWithSrs> = repository.getWordsForUser(userId).first()
        val stored = library.firstOrNull { it.word.hanzi == "海" }
        assertNotNull("海 is missing from the library projection", stored)
        assertEquals(
            "the library projection dropped the radical the learner supplied",
            "氵 (water)",
            stored!!.word.radical
        )
    }

    @Test
    fun `an empty radical is stored as empty rather than as a placeholder`() = runBlocking {
        repository.saveNewWordWithInitialSrs(
            draft(hanzi = "山", radical = ""),
            initialDueImmediate = true
        )

        val character = database.characterDao().getByCharacter("山")
        assertEquals(
            "a blank radical should be blank; the app used to substitute the literal word " +
                "\"部首\", which the library screen rendered as if it were this character's radical",
            "",
            character!!.radical
        )
    }

    @Test
    fun `a radical is trimmed rather than stored with stray whitespace`() = runBlocking {
        repository.saveNewWordWithInitialSrs(
            draft(hanzi = "火", radical = "  火 (fire)  "),
            initialDueImmediate = true
        )
        assertEquals("火 (fire)", database.characterDao().getByCharacter("火")!!.radical)
    }

    @Test
    fun `an already catalogued character keeps the radical it was first seen with`() = runBlocking {
        repository.saveNewWordWithInitialSrs(
            draft(hanzi = "水", radical = "水 (water)"),
            initialDueImmediate = true
        )
        // A different learner contributes a different spelling of the same radical. The
        // character is shared content, so the first catalogue entry stands rather than being
        // silently rewritten by whoever happened to add a word second.
        val second = database.userDao().insertUser(
            UserEntity(
                identifier = "second@example.com",
                identifierNormalized = "second@example.com",
                passwordHash = "unused",
                displayName = "Second",
                token = ""
            )
        )
        repository.saveNewWordWithInitialSrs(
            NewWordDraft(
                userId = second,
                hanzi = "水",
                pinyin = "shuǐ",
                meaning = "water",
                hskLevel = 1,
                radical = "水 (water), variant",
                exampleCn = "多喝水对身体好。",
                examplePy = "Duō hē shuǐ duì shēntǐ hǎo.",
                exampleEn = "Drinking plenty of water is good for your health.",
                strokeJson = "竖钩 (Shù Gōu), 横撇 (Héng Piě), 撇 (Piě), 捺 (Nà)",
                source = StorageValues.VocabularySource.MANUAL.storageValue,
                provenance = StorageValues.ContentProvenance.UNKNOWN.storageValue
            ),
            initialDueImmediate = true
        )

        assertEquals(
            "one learner's contribution overwrote the shared character row for everyone",
            "水 (water)",
            database.characterDao().getByCharacter("水")!!.radical
        )
    }

    // ---- absent data stays absent ----------------------------------------------------------------

    @Test
    fun `offline sample returns real data for a character it actually has`() {
        val sample = ai.offlineSampleFor("爱")
        assertNotNull("爱 is in the built-in dictionary and must resolve", sample)
        assertEquals("爱", sample!!.hanzi)
        assertEquals("ài", sample.pinyin)
        assertEquals(
            "爱 is written with ten strokes; the dictionary says ${sample.strokeCount}",
            10,
            sample.strokeCount
        )
    }

    @Test
    fun `offline sample resolves pinyin to the same real entry`() {
        assertEquals(
            "ai should resolve to the same entry as 爱",
            ai.offlineSampleFor("爱")!!.hanzi,
            ai.offlineSampleFor("ai")!!.hanzi
        )
    }

    @Test
    fun `offline sample returns nothing for a character it does not have`() {
        // Previously this returned pinyin "zì", the literal radical "部首" and a fixed
        // five-stroke breakdown describing no character in particular.
        assertNull(
            "an unknown character was given invented data; it must be absent instead",
            ai.offlineSampleFor("龘")
        )
        assertNull(
            "an unknown multi-character query was given invented data",
            ai.offlineSampleFor("some english words")
        )
    }

    @Test
    fun `every offline entry's stroke count agrees with its own breakdown`() {
        // The specific shape of the fabrication that was removed: a count that contradicts the
        // list beside it. Asserted as an invariant over the whole dictionary rather than against
        // a hand-picked count, because the one entry that failed this - 爱 claimed 10 strokes and
        // listed 9, because the 冖 radical's closing 横钩 had been mislabelled 横撇 and the real
        // 横撇 before 捺 was missing - was a genuine cataloguing error in real data, not in a
        // placeholder. The app animates this list, so it draws the character with one stroke
        // absent and one stroke drawn in the wrong place.
        //
        // Every key in `getPredefinedDictionary`. Adding an entry there means adding it here;
        // `offlineSampleFor` is the only public way in, and a test that only spot-checks is a
        // test that will keep passing as the catalogue grows wrong.
        val keys = listOf(
            "爱", "ai", "人", "ren", "中", "zhong", "大", "da", "水", "shui",
            "日", "月", "山", "火", "书", "猫", "狗", "吃", "喝",
            "老师", "laoshi", "学生", "xuesheng", "学校", "xuexiao"
        )
        assertEquals(
            "a dictionary entry was added without adding it to this test; " +
                "the invariant is no longer being checked over the whole catalogue",
            keys.distinct().size,
            keys.size
        )
        for (query in keys) {
            val sample = ai.offlineSampleFor(query)
                ?: throw AssertionError("dictionary entry for '$query' disappeared")
            val segments = sample.strokeBreakdown.split(',').count { it.trim().isNotEmpty() }
            assertEquals(
                "'$query' (${sample.hanzi}) reports ${sample.strokeCount} strokes but its " +
                    "breakdown lists $segments: \"${sample.strokeBreakdown}\"",
                segments,
                sample.strokeCount,
            )
            assertTrue(
                "'$query' carries the placeholder radical; absent data must be absent",
                sample.radical != "部首",
            )
            if (sample.hanzi.codePointCount(0, sample.hanzi.length) == 1) {
                assertTrue(
                    "'$query' has no stroke breakdown at all, so writing practice would have " +
                        "nothing to animate",
                    sample.strokeBreakdown.isNotBlank(),
                )
            } else {
                // A word is not a character: there is no single stroke sequence to animate,
                // so a word entry must be *consistently* empty (blank list, zero count)
                // rather than carry one character's strokes as the word's. Fabricating a
                // sequence the catalogue cannot verify would be the old bug in a new shape.
                assertTrue(
                    "'$query' is a word and must not carry a guessed stroke breakdown: " +
                        "\"${sample.strokeBreakdown}\"",
                    sample.strokeBreakdown.isBlank(),
                )
                assertEquals(
                    "'$query' is a word with no breakdown, so its count must be zero, " +
                        "not ${sample.strokeCount}",
                    0,
                    sample.strokeCount,
                )
            }
        }
    }
}
