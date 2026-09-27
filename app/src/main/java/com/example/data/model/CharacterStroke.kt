package com.example.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * One stroke of one character, in writing order.
 *
 * Stroke data belongs to the *character*, not to the vocabulary entry. 长 read as `cháng` and as
 * `zhǎng` is written identically, so storing the breakdown on `vocabulary` duplicated it per
 * reading and let two copies disagree. This table has one row per stroke and the character as its
 * only parent, which is also the shape a writing-practice system needs: to score one stroke at a
 * time, or to attach geometry to a stroke, there has to be a row to attach it to.
 *
 * `(characterId, position)` is the primary key, so the sequence is unique per character and
 * `ORDER BY position` is served by the key rather than a sort.
 */
@Entity(
    tableName = "character_strokes",
    primaryKeys = ["characterId", "position"],
    foreignKeys = [
        ForeignKey(
            entity = CharacterEntity::class,
            parentColumns = ["id"],
            childColumns = ["characterId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        // Deliberately none beyond the primary key: `characterId` is its leading column, so
        // "every stroke of this character in order" is a prefix scan of the key.
    ]
)
data class CharacterStrokeEntity(
    val characterId: Long,
    /**
     * 1-based position in the standard writing order.
     *
     * 1-based because that is how stroke order is numbered when it is taught, and a UI that
     * prints "stroke 1 of 7" should not have to add one to a stored zero-based index.
     */
    val position: Int,
    /** The stroke's Chinese name, e.g. `横`. */
    val nameCn: String,
    /** Its pinyin, e.g. `héng`. Empty when the name could not be decomposed. */
    val namePinyin: String = ""
)

/**
 * A character and everything the app knows about how it is written, with no reading attached.
 *
 * This is the character tier. A character is the unit of writing; a vocabulary entry is the unit
 * of meaning, and it points *at* a character rather than owning one. [associatedVocabularyId] and
 * [readings] are the relationships back the other way, resolved by the repository.
 *
 * Deliberately nullable rather than defaulted: the brief for this work says stroke and structure
 * information is used "where available", and inventing a plausible structure or stroke count for
 * a character nobody has data for is worse than admitting it is unknown. An empty list is a true
 * statement; a fabricated `左右结构` is a false one that a learner would study.
 */
data class CharacterDetail(
    val id: Long,
    val character: String,
    val codePoint: Int,
    val strokeCount: Int,
    val radical: String,
    /**
     * Compositional structure, e.g. `左右结构` (left-right).
     *
     * Empty when unknown. No screen may render a placeholder for it: "structure unknown" is
     * information, "structure: ?" is noise.
     */
    val structure: String,
    val strokes: List<CharacterStrokeEntity>,
    /** Every meaning this character carries, one per vocabulary entry that uses it. */
    val associatedVocabularyId: List<VocabularySummary>,
    /** Every reading of this character, one per vocabulary entry that uses it. */
    val readings: List<CharacterReading>
) {
    val hasStrokeData: Boolean get() = strokes.isNotEmpty()

    /**
     * Whether the stored stroke count and the stored strokes agree.
     *
     * They are populated by different routes - the count from the dataset, the sequence
     * migrated from a comma-separated blob - so they can disagree, and when they do the sequence
     * is the one that was actually authored, so it wins.
     */
    val hasConsistentStrokeCount: Boolean
        get() = strokes.isEmpty() || strokeCount == 0 || strokes.size == strokeCount
}

/** One meaning a character has, as offered by the vocabulary tier. */
data class VocabularySummary(
    val vocabularyId: Long,
    val meaning: String,
    val levelId: Long
)

/** One reading of a character: a syllable plus the tone it is read with. */
data class CharacterReading(
    val vocabularyId: Long,
    val syllable: String,
    val toneNumber: Int,
    val toneMarked: String
)
