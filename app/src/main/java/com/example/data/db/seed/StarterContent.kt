package com.example.data.db.seed

import androidx.room.withTransaction
import com.example.data.db.AppDatabase
import com.example.data.db.CharacterDao
import com.example.data.db.PinyinDao
import com.example.data.model.CharacterEntity
import com.example.data.model.ExampleSentenceEntity
import com.example.data.model.PinyinSyllableEntity
import com.example.data.model.StorageValues
import com.example.data.model.VocabularyEntity
import com.example.data.srs.PinyinAnalyzer

/**
 * The six words a new install starts from, as shared content.
 *
 * They were previously written straight into the per-user `words` table along with invented
 * review intervals and due dates, which meant a brand-new learner was shown a fabricated study
 * history. Here they are ordinary content rows marked [StorageValues.ContentProvenance.CURATED]
 * and verified, with no schedule at all: the first card a learner opens really is `NEW`.
 *
 * Because these rows belong to the content tier, the first learner to enrol from them creates
 * the enrolment and the schedule, and a second learner on the same device reuses the same
 * content row.
 */
object StarterContent {

    data class StarterWord(
        val hanzi: String,
        val pinyin: String,
        val meaning: String,
        val hskLevel: Int,
        val radical: String,
        val exampleCn: String,
        val examplePy: String,
        val exampleEn: String,
        val strokeBreakdown: String
    )

    val words: List<StarterWord> = listOf(
        StarterWord(
            hanzi = "学",
            pinyin = "xué",
            meaning = "to study; to learn",
            hskLevel = 1,
            radical = "子 (child)",
            exampleCn = "我每天在学校学习中文。",
            examplePy = "Wǒ měitiān zài xuéxiào xuéxí zhōngwén.",
            exampleEn = "I study Chinese at school every day.",
            strokeBreakdown = "点 (Diǎn), 点 (Diǎn), 撇 (Piě), 点 (Diǎn), 横钩 (Héng Gōu), 弯钩 (Wān Gōu), 横 (Héng)"
        ),
        StarterWord(
            hanzi = "好",
            pinyin = "hǎo",
            meaning = "good; well; fine",
            hskLevel = 1,
            radical = "女 (woman)",
            exampleCn = "今天天气很好，我们去公园吧。",
            examplePy = "Jīntiān tiānqì hěn hǎo, wǒmen qù gōngyuán ba.",
            exampleEn = "The weather is very good today, let's go to the park.",
            strokeBreakdown = "撇点 (Piě Diǎn), 撇 (Piě), 提 (Tí), 横撇 (Héng Piě), 弯钩 (Wān Gōu), 横 (Héng)"
        ),
        StarterWord(
            hanzi = "你",
            pinyin = "nǐ",
            meaning = "you (singular)",
            hskLevel = 1,
            radical = "亻 (person)",
            exampleCn = "你好！很高兴认识你。",
            examplePy = "Nǐ hǎo! Hěn gāoxìng rènshí nǐ.",
            exampleEn = "Hello! Very nice to meet you.",
            strokeBreakdown = "撇 (Piě), 竖 (Shù), 撇 (Piě), 横钩 (Héng Gōu), 竖钩 (Shù Gōu), 撇 (Piě), 点 (Diǎn)"
        ),
        StarterWord(
            hanzi = "朋",
            pinyin = "péng",
            meaning = "friend; companion",
            hskLevel = 1,
            radical = "月 (moon)",
            exampleCn = "他是我的好朋友。",
            examplePy = "Tā shì wǒ de hǎo péngyǒu.",
            exampleEn = "He is my good friend.",
            strokeBreakdown = "撇 (Piě), 横折钩 (Héng Zhé Gōu), 横 (Héng), 横 (Héng), 撇 (Piě), 横折钩 (Héng Zhé Gōu), 横 (Héng), 横 (Héng)"
        ),
        StarterWord(
            hanzi = "茶",
            pinyin = "chá",
            meaning = "tea",
            hskLevel = 1,
            radical = "艹 (grass)",
            exampleCn = "你想喝绿茶还是红茶？",
            examplePy = "Nǐ xiǎng hē lǜchá háishì hóngchá?",
            exampleEn = "Would you like to drink green tea or black tea?",
            strokeBreakdown = "横 (Héng), 竖 (Shù), 竖 (Shù), 撇 (Piě), 捺 (Nà), 横 (Héng), 撇 (Piě), 竖钩 (Shù Gōu), 撇 (Piě), 点 (Diǎn)"
        ),
        StarterWord(
            hanzi = "明",
            pinyin = "míng",
            meaning = "bright; clear; tomorrow",
            hskLevel = 1,
            radical = "日 (sun)",
            exampleCn = "明天我们要参加中文考试。",
            examplePy = "Míngtiān wǒmen yào cānjiā zhōngwén kǎoshì.",
            exampleEn = "Tomorrow we will take a Chinese exam.",
            strokeBreakdown = "竖 (Shù), 横折 (Héng Zhé), 横 (Héng), 横 (Héng), 撇 (Piě), 横折钩 (Héng Zhé Gōu), 横 (Héng), 横 (Héng)"
        )
    )

    /**
     * Inserts the starter words as content rows if they are not already present.
     *
     * Idempotent, so it can run on every launch. No learner is enrolled here: enrolling is the
     * caller's decision, made through
     * [com.example.data.repository.WordRepository.saveNewWordWithInitialSrs] so that a schedule
     * is created at the same time.
     */
    suspend fun install(database: AppDatabase) {
        val characterDao = database.characterDao()
        val pinyinDao = database.pinyinDao()
        val levelDao = database.learningLevelDao()
        val vocabularyDao = database.vocabularyDao()
        val exampleDao = database.exampleSentenceDao()

        database.withTransaction {
            val hsk1 = levelDao.getByCode("HSK1") ?: return@withTransaction
            val now = System.currentTimeMillis()

            for (word in words) {
                val characterId = characterDao.findOrCreate(word.hanzi, word.radical, now) ?: continue
                val analysis = PinyinAnalyzer.analyze(word.pinyin)
                val pinyinId = pinyinDao.findOrCreate(analysis, now) ?: continue

                val existing = vocabularyDao.getByCharacterAndPinyin(characterId, pinyinId)
                val vocabularyId = existing?.id ?: vocabularyDao.insertIfAbsent(
                    VocabularyEntity(
                        characterId = characterId,
                        pinyinId = pinyinId,
                        levelId = hsk1.id,
                        meaning = word.meaning,
                        strokeJson = word.strokeBreakdown,
                        tags = "HSK1,Essential",
                        provenance = StorageValues.ContentProvenance.CURATED.storageValue,
                        isVerified = true,
                        createdAt = now
                    )
                ).takeIf { it > 0 } ?: continue

                if (exampleDao.getByText(vocabularyId, word.exampleCn) == null) {
                    exampleDao.insertIfAbsent(
                        ExampleSentenceEntity(
                            vocabularyId = vocabularyId,
                            sentenceCn = word.exampleCn,
                            sentencePinyin = word.examplePy,
                            sentenceEn = word.exampleEn,
                            provenance = StorageValues.ContentProvenance.CURATED.storageValue,
                            isVerified = true,
                            createdAt = now
                        )
                    )
                }
            }
        }
    }

    /**
     * Returns the id of the glyph row for [hanzi], inserting it on first sight.
     *
     * `INSERT OR IGNORE` returns -1 when a concurrent writer created the row first, so the
     * fallback re-reads rather than assuming the insert succeeded. Null only if the row is
     * genuinely absent, which for a NOT NULL primary key cannot persist.
     */
    private suspend fun CharacterDao.findOrCreate(hanzi: String, radical: String, now: Long): Long? {
        getByCharacter(hanzi)?.let { return it.id }
        val inserted = insertIfAbsent(
            CharacterEntity(
                character = hanzi,
                codePoint = hanzi.codePointAt(0),
                radical = radical,
                createdAt = now
            )
        )
        return when {
            inserted > 0 -> inserted
            else -> getByCharacter(hanzi)?.id
        }
    }

    /** The reading equivalent of [findOrCreate], carrying the analysed tone and rime split. */
    private suspend fun PinyinDao.findOrCreate(
        analysis: PinyinAnalyzer.Analysis,
        now: Long
    ): Long? {
        get(analysis.syllable, analysis.toneNumber)?.let { return it.id }
        val inserted = insertIfAbsent(
            PinyinSyllableEntity(
                syllable = analysis.syllable,
                toneNumber = analysis.toneNumber,
                toneMarked = analysis.toneMarked,
                initial = analysis.initial,
                final = analysis.final,
                toneContour = analysis.toneContour,
                createdAt = now
            )
        )
        return when {
            inserted > 0 -> inserted
            else -> get(analysis.syllable, analysis.toneNumber)?.id
        }
    }
}
