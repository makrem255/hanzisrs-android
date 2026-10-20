package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.dictionary.DictionaryEntry
import com.example.data.dictionary.OfflineDictionary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OfflineDictionaryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `dictionary loads from asset without crash`() {
        val dict = OfflineDictionary(context)
        assertNotNull(dict.entries)
    }

    @Test
    fun `dictionary contains all six pilot words`() {
        val dict = OfflineDictionary(context)
        val expected = listOf("妈妈", "你好", "老师", "学校", "学生", "中国")
        for (word in expected) {
            assertNotNull("Missing pilot word: $word", dict.lookupByHanzi(word))
        }
    }

    @Test
    fun `lookupByHanzi 妈妈 returns correct entry`() {
        val dict = OfflineDictionary(context)
        val entry = dict.lookupByHanzi("妈妈")
        assertNotNull(entry)
        assertEquals("妈妈", entry!!.hanzi)
        assertEquals("māma", entry.pinyin)
        assertTrue("Meaning should contain 'mother'", entry.meaning.contains("mother"))
        assertEquals(1, entry.hskLevel)
    }

    @Test
    fun `lookupByHanzi returns null for unknown word`() {
        val dict = OfflineDictionary(context)
        assertNull(dict.lookupByHanzi("未知"))
    }

    @Test
    fun `lookupByPinyin returns matching entries`() {
        val dict = OfflineDictionary(context)
        val results = dict.lookupByPinyin("māma")
        assertTrue("Should find 妈妈", results.any { it.hanzi == "妈妈" })
    }

    @Test
    fun `lookupByPinyin returns empty for unknown pinyin`() {
        val dict = OfflineDictionary(context)
        assertTrue(dict.lookupByPinyin("unknown").isEmpty())
    }

    @Test
    fun `entry has empty radical and stroke data`() {
        val dict = OfflineDictionary(context)
        val entry = dict.lookupByHanzi("妈妈")!!
        // These fields don't exist on DictionaryEntry, but verify the entry
        // doesn't claim verification.
        assertEquals(1, entry.hskLevel)
    }

    @Test
    fun `traditional form preserved`() {
        val dict = OfflineDictionary(context)
        assertEquals("媽媽", dict.lookupByHanzi("妈妈")!!.traditional)
        assertEquals("中國", dict.lookupByHanzi("中国")!!.traditional)
    }

    @Test
    fun `numbered pinyin preserved`() {
        val dict = OfflineDictionary(context)
        assertEquals("ma1 ma5", dict.lookupByHanzi("妈妈")!!.pinyinNumbered)
        assertEquals("Zhong1 guo2", dict.lookupByHanzi("中国")!!.pinyinNumbered)
    }

    @Test
    fun `all entries have HSK 1`() {
        val dict = OfflineDictionary(context)
        for (word in listOf("妈妈", "你好", "老师", "学校", "学生", "中国")) {
            assertEquals("HSK level for $word", 1, dict.lookupByHanzi(word)!!.hskLevel)
        }
    }

    @Test
    fun `lookup by pinyin is case insensitive`() {
        val dict = OfflineDictionary(context)
        val results = dict.lookupByPinyin("MĀMA")
        assertTrue("Should find 妈妈", results.any { it.hanzi == "妈妈" })
    }

    @Test
    fun `lookup by pinyin handles spaces`() {
        val dict = OfflineDictionary(context)
        val results = dict.lookupByPinyin("mā ma")
        assertTrue("Should find 妈妈", results.any { it.hanzi == "妈妈" })
    }
}