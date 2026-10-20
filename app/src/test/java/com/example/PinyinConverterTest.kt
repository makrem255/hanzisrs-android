package com.example

import com.example.data.srs.PinyinConverter
import org.junit.Assert.assertEquals
import org.junit.Test

class PinyinConverterTest {

    @Test
    fun `tone 1`() = assertEquals("mā", PinyinConverter.fromNumbered("ma1"))

    @Test
    fun `tone 2`() = assertEquals("má", PinyinConverter.fromNumbered("ma2"))

    @Test
    fun `tone 3`() = assertEquals("mǎ", PinyinConverter.fromNumbered("ma3"))

    @Test
    fun `tone 4`() = assertEquals("mà", PinyinConverter.fromNumbered("ma4"))

    @Test
    fun `neutral tone 0`() = assertEquals("ma", PinyinConverter.fromNumbered("ma0"))

    @Test
    fun `neutral tone 5`() = assertEquals("ma", PinyinConverter.fromNumbered("ma5"))

    @Test
    fun `iu marks u`() = assertEquals("iú", PinyinConverter.fromNumbered("iu2"))

    @Test
    fun `ui marks i`() = assertEquals("uǐ", PinyinConverter.fromNumbered("ui3"))

    @Test
    fun `nu3 regular u`() = assertEquals("nǔ", PinyinConverter.fromNumbered("nu3"))

    @Test
    fun `nu-colon-3 to nü3`() = assertEquals("nǚ", PinyinConverter.fromNumbered("nu:3"))

    @Test
    fun `nv3 to nü3`() = assertEquals("nǚ", PinyinConverter.fromNumbered("nv3"))

    @Test
    fun `u-colon to ü`() = assertEquals("nǚ", PinyinConverter.fromNumbered("nu:3"))

    @Test
    fun `multi-syllable mama`() = assertEquals("māma", PinyinConverter.fromNumbered("ma1 ma5"))

    @Test
    fun `multi-syllable nihao`() = assertEquals("nǐhǎo", PinyinConverter.fromNumbered("ni3 hao3"))

    @Test
    fun `multi-syllable xuesheng`() = assertEquals("xuésheng", PinyinConverter.fromNumbered("xue2 sheng5"))

    @Test
    fun `uppercase normalized`() = assertEquals("zhōngguó", PinyinConverter.fromNumbered("Zhong1 guo2"))

    @Test
    fun `empty input`() = assertEquals("", PinyinConverter.fromNumbered(""))

    @Test
    fun `invalid input returned as-is`() = assertEquals("xyz", PinyinConverter.fromNumbered("xyz"))

    @Test
    fun `numeric input returned as-is`() = assertEquals("123", PinyinConverter.fromNumbered("123"))

    @Test
    fun `a takes priority over o and e`() = assertEquals("bǎo", PinyinConverter.fromNumbered("bao3"))

    @Test
    fun `o takes priority over e`() = assertEquals("zǒu", PinyinConverter.fromNumbered("zou3"))

    @Test
    fun `e takes priority over i and u`() = assertEquals("shěi", PinyinConverter.fromNumbered("shei3"))
}