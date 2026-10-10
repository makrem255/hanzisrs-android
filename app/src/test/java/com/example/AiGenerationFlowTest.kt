package com.example

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.ai.GeneratedWordData
import com.example.data.ai.GenerationOutcome
import com.example.data.ai.GeminiAiService
import com.example.data.ai.WordDataOrigin
import com.example.data.ai.resolveGeneration
import com.example.data.db.AppDatabase
import com.example.data.model.NewWordDraft
import com.example.data.model.StorageValues
import com.example.data.model.UserEntity
import com.example.data.repository.SaveWordResult
import com.example.data.repository.Validator
import com.example.data.repository.WordRepository
import com.example.data.srs.PinyinAnalyzer
import com.example.ui.viewmodel.AiGenerationState
import com.example.ui.viewmodel.MainViewModel
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
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
 * The "老师" bug: Add Word failed a valid multi-character word with "No offline sample"
 * without ever attempting generation, and the validator refused the word outright.
 *
 * The flow under test is `generateWord`: online attempt first, offline sample as fallback,
 * honest error only when both are exhausted. Pure decisions go through [resolveGeneration]
 * so they need no network; persistence goes through a real in-memory database; and one test
 * drives the view model itself, because the reported symptom was the screen's error state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AiGenerationFlowTest {

    private val ai = GeminiAiService()

    private fun geminiData(hanzi: String) = GeneratedWordData(
        hanzi = hanzi,
        pinyin = "pīn",
        meaning = "meaning",
        hskLevel = 1,
        radical = "",
        exampleCn = "",
        examplePy = "",
        exampleEn = "",
        strokeBreakdown = "",
        strokeCount = 0,
        origin = WordDataOrigin.GEMINI,
    )

    // ---- the reported regression ---------------------------------------------------------

    @Test
    fun `offline dictionary contains laoshi`() {
        val sample = ai.offlineSampleFor("老师")

        assertNotNull("老师 is a first-year word and must resolve", sample)
        assertEquals("老师", sample!!.hanzi)
        assertEquals("lǎoshī", sample.pinyin)
        assertEquals("teacher", sample.meaning)
    }

    @Test
    fun `offline dictionary contains xuesheng and xuexiao`() {
        assertEquals("xuéshēng", ai.offlineSampleFor("学生")!!.pinyin)
        assertEquals("xuéxiào", ai.offlineSampleFor("学校")!!.pinyin)
    }

    @Test
    fun `offline dictionary still answers nothing for words it does not have`() {
        assertNull(ai.offlineSampleFor("咖啡"))
    }

    // ---- resolveGeneration: online first, sample as fallback --------------------------------

    @Test
    fun `online success wins without consulting the dictionary`() {
        var consulted = false
        val outcome = resolveGeneration("老师", Result.success(geminiData("老师"))) {
            consulted = true
            null
        }

        assertTrue(outcome is GenerationOutcome.Ready)
        assertEquals(WordDataOrigin.GEMINI, (outcome as GenerationOutcome.Ready).data.origin)
        assertTrue("a success must not need the fallback", !consulted)
    }

    @Test
    fun `online failure falls back to a matching sample`() {
        val outcome = resolveGeneration(
            "老师",
            Result.failure(Exception("AI word generation is unavailable")),
        ) { ai.offlineSampleFor(it) }

        assertTrue(outcome is GenerationOutcome.Ready)
        assertEquals("老师", (outcome as GenerationOutcome.Ready).data.hanzi)
    }

    @Test
    fun `online failure with no sample reports both halves`() {
        val outcome = resolveGeneration(
            "咖啡",
            Result.failure(Exception("AI word generation is unavailable")),
        ) { ai.offlineSampleFor(it) }

        assertTrue(outcome is GenerationOutcome.Failed)
        val message = (outcome as GenerationOutcome.Failed).message
        assertTrue("must name the AI failure, got: $message", "unavailable" in message)
        assertTrue("must name the missing sample, got: $message", "咖啡" in message)
        assertTrue("must offer the manual path, got: $message", "by hand" in message)
    }

    // ---- the view model end to end ------------------------------------------------------------

    @Test
    fun `generateWord resolves laoshi to review without a backend configured`() {
        // This build carries no AI backend URL, so the online attempt fails fast and the
        // fallback must answer. That is exactly the configuration that produced the report.
        Dispatchers.setMain(Dispatchers.Unconfined)
        try {
            val vm = MainViewModel(ApplicationProvider.getApplicationContext<Application>())
            vm.generateWord("老师")
            val terminal = runBlocking {
                withTimeout(15_000) {
                    vm.aiState.first { it is AiGenerationState.ReadyForReview || it is AiGenerationState.Error }
                }
            }
            assertTrue(
                "expected review data, got $terminal",
                terminal is AiGenerationState.ReadyForReview,
            )
            assertEquals(
                "老师",
                (terminal as AiGenerationState.ReadyForReview).wordData.hanzi,
            )
        } finally {
            Dispatchers.resetMain()
        }
    }

    // ---- multi-character validation --------------------------------------------------------------

    @Test
    fun `validator accepts short words and still refuses the rest`() {
        fun draft(hanzi: String, pinyin: String) = NewWordDraft(
            userId = 1L,
            hanzi = hanzi,
            pinyin = pinyin,
            meaning = "meaning",
        )

        assertNull(Validator.validateNewWord(draft("学", "xué")))
        assertNull(Validator.validateNewWord(draft("老师", "lǎoshī")))
        assertNull(Validator.validateNewWord(draft("学生", "xuéshēng")))
        assertNull(Validator.validateNewWord(draft("学校", "xuéxiào")))

        assertNotNull("blank is not a word", Validator.validateNewWord(draft("", "xué")))
        assertNotNull(
            "five characters is a phrase, not a word",
            Validator.validateNewWord(draft("学生们好啊呀", "xuéshēngmenhǎoāya")),
        )
        assertNotNull(
            "digits are not pinyin",
            Validator.validateNewWord(draft("老师", "l40sh1")),
        )
        assertNotNull(
            "latin prose is not Chinese text",
            Validator.validateNewWord(draft("hello", "hello")),
        )
    }

    // ---- phrase pinyin is preserved, not re-marked --------------------------------------------------

    @Test
    fun `phrase readings are detected and kept verbatim`() {
        assertTrue(PinyinAnalyzer.isPhrase("lǎoshī"))
        assertTrue(PinyinAnalyzer.isPhrase("wǒ men"))
        assertTrue(PinyinAnalyzer.isPhrase("xuéshēng"))
        assertTrue(!PinyinAnalyzer.isPhrase("ài"))
        assertTrue(!PinyinAnalyzer.isPhrase("zhang"))

        assertTrue(PinyinAnalyzer.isPinyinToken("lǎoshī"))
        assertTrue(!PinyinAnalyzer.isPinyinToken("l40sh1"))
        assertTrue(!PinyinAnalyzer.isPinyinToken(""))
        assertEquals(2, PinyinAnalyzer.countToneMarks("lǎoshī"))
        assertEquals(0, PinyinAnalyzer.countToneMarks("laoshi"))
    }

    // ---- persistence: multi-character words save once and read back -----------------------------------

    private lateinit var database: AppDatabase
    private lateinit var words: WordRepository

    @Before
    fun setUpDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        runBlocking { AppDatabase.seedReferenceData(database) }
        words = WordRepository(database, ZoneOffset.UTC) { 1_760_000_000_000L }
    }

    @After
    fun tearDownDatabase() {
        database.close()
    }

    private suspend fun newUser(): Long = database.userDao().insertUser(
        UserEntity(
            identifier = "flow@test.dev",
            identifierNormalized = "flow@test.dev",
            authType = StorageValues.AuthType.EMAIL.storageValue,
            passwordHash = "unused-by-these-tests",
            displayName = "flow",
            token = "token-flow",
        )
    )

    private fun draft(userId: Long, hanzi: String, pinyin: String) = NewWordDraft(
        userId = userId,
        hanzi = hanzi,
        pinyin = pinyin,
        meaning = "meaning $hanzi",
        hskLevel = 1,
    )

    @Test
    fun `laoshi saves reads back and does not duplicate`() = runBlocking {
        val userId = newUser()

        val first = words.saveNewWordWithInitialSrs(draft(userId, "老师", "lǎoshī"))
        assertTrue("a valid word must save, got $first", first is SaveWordResult.Saved)

        val stored = words.findWordByHanzi(userId, "老师")
        assertNotNull(stored)
        assertEquals(
            "the phrase reading must survive storage with both tones",
            "lǎoshī",
            stored!!.word.pinyin,
        )
        assertEquals("老师", stored.word.hanzi)

        val character = database.characterDao().getByCharacter("老师")
        assertNotNull(character)
        assertEquals(
            "the word must be filed under its own string, never under its first character",
            "老师",
            character!!.character,
        )

        val second = words.saveNewWordWithInitialSrs(draft(userId, "老师", "lǎoshī"))
        assertTrue("re-adding the same word must report a duplicate, got $second", second is SaveWordResult.Duplicate)
    }

    @Test
    fun `single characters still save exactly as before`() = runBlocking {
        val userId = newUser()

        val saved = words.saveNewWordWithInitialSrs(draft(userId, "学", "xué"))
        assertTrue(saved is SaveWordResult.Saved)
        assertEquals("xué", words.findWordByHanzi(userId, "学")!!.word.pinyin)
    }
}
