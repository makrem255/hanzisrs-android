package com.example.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A rung on the proficiency ladder (HSK 1-6, plus a non-assigned level).
 *
 * Global content: nothing here is owned by a learner, so the table is small, read-mostly
 * and safe to share. Seeded by [com.example.data.db.migrations.Migration_1_2] and by
 * [com.example.data.db.seed.ReferenceData].
 */
@Entity(
    tableName = "learning_levels",
    indices = [
        Index(value = ["code"], unique = true),
        Index(value = ["ordinal"], unique = true)
    ]
)
data class LearningLevelEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** Stable external key such as `HSK1`. Safe to persist in AI prompts and exports. */
    val code: String,
    /** Sort order; lower means earlier. Unique so the ladder has no ties. */
    val ordinal: Int,
    val title: String,
    val description: String = "",
    val targetWordCount: Int = 0
)

/**
 * A single written form, independent of any reading or meaning.
 *
 * Splitting the glyph out of the vocabulary entry is what lets one character be shared by
 * several vocabulary rows: 长 carries both `cháng` and `zhǎng`, and both point at the same
 * row here instead of duplicating the glyph and its stroke count.
 */
@Entity(
    tableName = "characters",
    indices = [
        Index(value = ["character"], unique = true),
        Index(value = ["codePoint"])
    ]
)
data class CharacterEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** The glyph itself. Single code point for the CJK ranges this app teaches. */
    val character: String,
    /** Unicode scalar value, so lookups avoid re-scanning text. */
    val codePoint: Int,
    val strokeCount: Int = 0,
    val radical: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * One pinyin reading, stored decomposed so filtering by tone or initial stays indexable.
 *
 * `syllable` + `toneNumber` is the identity: the toneless form alone is not unique, because
 * `ma` is a real reading at four different tones.
 */
@Entity(
    tableName = "pinyin_syllables",
    indices = [
        Index(value = ["syllable", "toneNumber"], unique = true)
    ]
)
data class PinyinSyllableEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** Toneless syllable in ASCII, lower case, e.g. `xue`. */
    val syllable: String,
    /** 0 for neutral tone, 1-4 otherwise. */
    val toneNumber: Int,
    /** Display form with the diacritic, e.g. `xué`. */
    val toneMarked: String,
    /** Consonant onset, e.g. `x`. Empty for a syllable that starts with a vowel. */
    val initial: String = "",
    /** Rime, e.g. `ue`. */
    val final: String = "",
    /** Five-level contour, e.g. `35`; empty when the tone is unknown. */
    val toneContour: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * A vocabulary entry: one character read one way and used to mean something.
 *
 * This is the shared content row that every learner references. The unique key
 * `(characterId, pinyinId)` is what makes a duplicate detectable across all users rather
 * than per user, and it is the anchor for the polymorphic readings of a character.
 */
@Entity(
    tableName = "vocabulary",
    foreignKeys = [
        ForeignKey(
            entity = CharacterEntity::class,
            parentColumns = ["id"],
            childColumns = ["characterId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = PinyinSyllableEntity::class,
            parentColumns = ["id"],
            childColumns = ["pinyinId"],
            // RESTRICT: a reading that is still referenced by vocabulary must not vanish.
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = LearningLevelEntity::class,
            parentColumns = ["id"],
            childColumns = ["levelId"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["characterId", "pinyinId"], unique = true),
        Index(value = ["levelId"]),
        Index(value = ["characterId"]),
        // Leading a cascade from `pinyin_syllables`: the index above starts with characterId,
        // so it cannot answer "which entries use this reading?".
        Index(value = ["pinyinId"]),
        Index(value = ["meaning"])
    ]
)
data class VocabularyEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val characterId: Long,
    val pinyinId: Long,
    val levelId: Long,
    val meaning: String,
    val partOfSpeech: String = "",
    /** Stroke breakdown, kept verbatim from the source; geometry is a later migration. */
    val strokeJson: String = "",
    /** Comma-separated curriculum tags such as `HSK1,Essential`. */
    val tags: String = "",
    /** Lower is more frequent. 0 means unranked. */
    val frequencyRank: Int = 0,
    val provenance: String = StorageValues.ContentProvenance.UNKNOWN.storageValue,
    val isVerified: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * A sentence demonstrating a vocabulary entry in context.
 *
 * Provenance is recorded per sentence rather than inherited from the word, because a
 * curated word very often has a machine-written example attached to it.
 */
@Entity(
    tableName = "example_sentences",
    foreignKeys = [
        ForeignKey(
            entity = VocabularyEntity::class,
            parentColumns = ["id"],
            childColumns = ["vocabularyId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        // One sentence appears once per vocabulary entry however many learners contribute it.
        // Without this, two learners adding the same word would each attach a copy of the
        // same example, and the detail screen would show it twice.
        Index(value = ["vocabularyId", "sentenceCn"], unique = true)
    ]
)
data class ExampleSentenceEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val vocabularyId: Long,
    val sentenceCn: String,
    val sentencePinyin: String = "",
    val sentenceEn: String = "",
    val provenance: String = StorageValues.ContentProvenance.UNKNOWN.storageValue,
    val isVerified: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * A position on the curriculum: what a learner is expected to study at a given level.
 *
 * Kept separate from [VocabularyEntity] because ordering is a property of the course, not of
 * the word. Two learners on the same level share these rows.
 */
@Entity(
    tableName = "learning_items",
    foreignKeys = [
        ForeignKey(
            entity = LearningLevelEntity::class,
            parentColumns = ["id"],
            childColumns = ["levelId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = VocabularyEntity::class,
            parentColumns = ["id"],
            childColumns = ["vocabularyId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        // One word may not occupy two positions in the same level...
        Index(value = ["levelId", "position"], unique = true),
        // ...and two levels may not claim the same position number.
        Index(value = ["levelId", "vocabularyId"], unique = true),
        // Leading the cascade when a shared entry is removed from the curriculum.
        Index(value = ["vocabularyId"])
    ]
)
data class LearningItemEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val levelId: Long,
    val vocabularyId: Long,
    val unit: Int = 1,
    /** Zero-based order inside the level. */
    val position: Int = 0,
    /** 1 (easiest) upward. */
    val difficulty: Int = 1
)
