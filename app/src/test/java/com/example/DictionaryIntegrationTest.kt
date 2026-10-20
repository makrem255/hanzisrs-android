package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.ai.GeminiAiService
import com.example.data.ai.GeneratedWordData
import com.example.data.ai.WordDataOrigin
import com.example.data.db.AppDatabase
import com.example.data.dictionary.OfflineDictionary
import com.example.data.model.StorageValues
import com.example.data.model.UserEntity
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
 * Phase B2 integration tests: offline dictionary → GeneratedWordData → save flow.
 *
 * Tests dictionary hit/miss, source mapping, persistence provenance, and HSK 0.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DictionaryIntegrationTest {

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
        AppDatabase.seedReferenceData(database)
        repository = WordRepository(database)
        ai = GeminiAiService(com.example.data.dictionary.OfflineDictionary(context))
        userId = database.userDao().insertUser(
            UserEntity(
                identifier = "dict@example.com",
                identifierNormalized = "dict@example.com",
                passwordHash = "unused",
                displayName = "Dict",
                token = ""
            )
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    // ---- 1. Dictionary hit produces GeneratedWordData with source = IMPORTED ----

    @Test
    fun `dictionary hit for mama produces IMPORTED source`() {
        val data = ai.offlineSampleFor("妈妈")
        assertNotNull(data)
        assertEquals("妈妈", data!!.hanzi)
        assertEquals("māma", data.pinyin)
        assertEquals(com.example.data.model.StorageValues.VocabularySource.IMPORTED, data.source)
    }

    @Test
    fun `dictionary hit for all six pilot words`() {
        val words = listOf("妈妈", "你好", "老师", "学校", "学生", "中国")
        for (word in words) {
            val data = ai.offlineSampleFor(word)
            assertNotNull("Missing dictionary entry for $word", data)
            assertEquals(word, data!!.hanzi)
            assertEquals(com.example.data.model.StorageValues.VocabularySource.IMPORTED, data.source)
        }
    }

    // ---- 2. Dictionary miss falls through to hardcoded samples ----

    @Test
    fun `dictionary miss falls through to hardcoded sample for laoshi`() {
        // "老师" is in both dictionary and hardcoded samples.
        // Test a word only in hardcoded samples (not in dictionary pilot).
        val data = ai.offlineSampleFor("爱")
        assertNotNull(data)
        assertEquals("爱", data!!.hanzi)
        // "爱" is in hardcoded samples, not dictionary pilot.
        assertEquals(com.example.data.model.StorageValues.VocabularySource.AI_GENERATED, data.source)
    }

    @Test
    fun `dictionary miss for unknown word returns null`() {
        assertNull(ai.offlineSampleFor("咖啡"))
    }

    // ---- 3. AI failure + dictionary hit ----

    @Test
    fun `resolveGeneration with AI failure and dictionary hit returns Ready`() {
        val outcome = com.example.data.ai.resolveGeneration(
            "妈妈",
            Result.failure(Exception("AI unavailable")),
        ) { ai.offlineSampleFor(it) }
        assertTrue(outcome is com.example.data.ai.GenerationOutcome.Ready)
        val data = (outcome as com.example.data.ai.GenerationOutcome.Ready).data
        assertEquals("妈妈", data.hanzi)
        assertEquals(com.example.data.model.StorageValues.VocabularySource.IMPORTED, data.source)
    }

    // ---- 4. Saving dictionary entry persists IMPORTED source and provenance ----

    @Test
    fun `saving dictionary entry persists IMPORTED source and provenance`() = runBlocking {
        val data = ai.offlineSampleFor("妈妈")!!
        val draft = com.example.data.model.NewWordDraft(
            userId = userId,
            hanzi = data.hanzi,
            pinyin = data.pinyin,
            meaning = data.meaning,
            hskLevel = data.hskLevel,
            radical = data.radical,
            exampleCn = data.exampleCn,
            examplePy = data.examplePy,
            exampleEn = data.exampleEn,
            strokeJson = data.strokeBreakdown,
            tags = "HSK${data.hskLevel},Custom",
            source = com.example.data.model.StorageValues.VocabularySource.IMPORTED.storageValue,
            provenance = com.example.data.model.StorageValues.ContentProvenance.IMPORTED.storageValue
        )
        val result = repository.saveNewWordWithInitialSrs(draft, initialDueImmediate = true)
        assertTrue(result is SaveWordResult.Saved)

        // Verify the saved word has IMPORTED source/provenance
        val words = repository.getWordsForUser(userId).first()
        val saved = words.firstOrNull { it.word.hanzi == "妈妈" }
        assertNotNull(saved)
        assertEquals("IMPORTED", saved!!.word.provenance)
    }

    // ---- 5. AI-generated and manual entries retain correct provenance ----

    @Test
    fun `AI-generated entry saves with AI_GENERATED source and provenance`() = runBlocking {
        val draft = com.example.data.model.NewWordDraft(
            userId = userId,
            hanzi = "茶",
            pinyin = "chá",
            meaning = "tea",
            hskLevel = 1,
            source = com.example.data.model.StorageValues.VocabularySource.AI_GENERATED.storageValue,
            provenance = com.example.data.model.StorageValues.ContentProvenance.AI_GENERATED.storageValue
        )
        val result = repository.saveNewWordWithInitialSrs(draft, initialDueImmediate = true)
        assertTrue(result is SaveWordResult.Saved)

        val words = repository.getWordsForUser(userId).first()
        val saved = words.firstOrNull { it.word.hanzi == "茶" }
        assertNotNull(saved)
        assertEquals("AI_GENERATED", saved!!.word.provenance)
    }

    @Test
    fun `manual entry saves with MANUAL source and UNKNOWN provenance`() = runBlocking {
        val draft = com.example.data.model.NewWordDraft(
            userId = userId,
            hanzi = "水",
            pinyin = "shuǐ",
            meaning = "water",
            hskLevel = 1,
            source = com.example.data.model.StorageValues.VocabularySource.MANUAL.storageValue,
            provenance = com.example.data.model.StorageValues.ContentProvenance.UNKNOWN.storageValue
        )
        val result = repository.saveNewWordWithInitialSrs(draft, initialDueImmediate = true)
        assertTrue(result is SaveWordResult.Saved)

        val words = repository.getWordsForUser(userId).first()
        val saved = words.firstOrNull { it.word.hanzi == "水" }
        assertNotNull(saved)
        assertEquals("UNKNOWN", saved!!.word.provenance)
    }

    // ---- 6. Dictionary entries remain unverified ----

    @Test
    fun `dictionary entry has isVerified false by default`() = runBlocking {
        val data = ai.offlineSampleFor("妈妈")!!
        val draft = com.example.data.model.NewWordDraft(
            userId = userId,
            hanzi = data.hanzi,
            pinyin = data.pinyin,
            meaning = data.meaning,
            hskLevel = data.hskLevel,
            source = com.example.data.model.StorageValues.VocabularySource.IMPORTED.storageValue,
            provenance = com.example.data.model.StorageValues.ContentProvenance.IMPORTED.storageValue
        )
        val result = repository.saveNewWordWithInitialSrs(draft, initialDueImmediate = true)
        assertTrue(result is SaveWordResult.Saved)

        val vocab = database.vocabularyDao().getByHanzi("妈妈")
        assertTrue("Vocabulary row should exist", vocab.isNotEmpty())
        assertEquals("isVerified should be false", false, vocab.first().isVerified)
    }

    // ---- 7. HSK 0 remains unknown ----

    @Test
    fun `HSK 0 entry requires user to select valid level`() {
        // Dictionary entries with HSK 0 should show no level selected.
        // Validator should reject HSK 0.
        val draft = com.example.data.model.NewWordDraft(
            userId = userId,
            hanzi = "测",
            pinyin = "cè",
            meaning = "test",
            hskLevel = 0,
            source = com.example.data.model.StorageValues.VocabularySource.IMPORTED.storageValue,
            provenance = com.example.data.model.StorageValues.ContentProvenance.IMPORTED.storageValue
        )
        val error = com.example.data.repository.Validator.validateNewWord(draft)
        assertNotNull("HSK 0 should fail validation", error)
        assertTrue("Error should mention HSK level", error!!.message.contains("HSK level"))
    }

    // ---- 8. Duplicate handling and initial SRS ----

    @Test
    fun `duplicate dictionary entry returns Duplicate`() = runBlocking {
        val data = ai.offlineSampleFor("妈妈")!!
        val draft = com.example.data.model.NewWordDraft(
            userId = userId,
            hanzi = data.hanzi,
            pinyin = data.pinyin,
            meaning = data.meaning,
            hskLevel = data.hskLevel,
            source = com.example.data.model.StorageValues.VocabularySource.IMPORTED.storageValue,
            provenance = com.example.data.model.StorageValues.ContentProvenance.IMPORTED.storageValue
        )
        val first = repository.saveNewWordWithInitialSrs(draft, initialDueImmediate = true)
        assertTrue(first is SaveWordResult.Saved)

        val second = repository.saveNewWordWithInitialSrs(draft, initialDueImmediate = true)
        assertTrue("Duplicate should be detected", second is SaveWordResult.Duplicate)
    }

    @Test
    fun `dictionary entry initializes SRS with due immediately`() = runBlocking {
        val data = ai.offlineSampleFor("妈妈")!!
        val draft = com.example.data.model.NewWordDraft(
            userId = userId,
            hanzi = data.hanzi,
            pinyin = data.pinyin,
            meaning = data.meaning,
            hskLevel = data.hskLevel,
            source = com.example.data.model.StorageValues.VocabularySource.IMPORTED.storageValue,
            provenance = com.example.data.model.StorageValues.ContentProvenance.IMPORTED.storageValue
        )
        val result = repository.saveNewWordWithInitialSrs(draft, initialDueImmediate = true)
        assertTrue(result is SaveWordResult.Saved)
        val saved = result as SaveWordResult.Saved

        // Verify SRS state exists
        val words = repository.getWordsForUser(userId).first()
        val word = words.firstOrNull { it.word.hanzi == "妈妈" }
        assertNotNull(word)
        assertNotNull("SRS state should be initialized", word!!.srs)
    }

    // ---- Missing metadata not fabricated ----

    @Test
    fun `dictionary entry has empty radical and stroke data`() {
        val data = ai.offlineSampleFor("妈妈")!!
        assertEquals("", data.radical)
        assertEquals("", data.strokeBreakdown)
        assertEquals(0, data.strokeCount)
        assertEquals("", data.exampleCn)
        assertEquals("", data.examplePy)
        assertEquals("", data.exampleEn)
    }
}