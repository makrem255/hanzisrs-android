package com.example.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.example.data.model.CharacterEntity
import com.example.data.model.LearningLevelEntity
import com.example.data.model.PinyinSyllableEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LearningLevelDao {

    @Query("SELECT * FROM learning_levels ORDER BY ordinal ASC")
    fun observeAll(): Flow<List<LearningLevelEntity>>

    @Query("SELECT * FROM learning_levels ORDER BY ordinal ASC")
    suspend fun getAll(): List<LearningLevelEntity>

    @Query("SELECT * FROM learning_levels WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): LearningLevelEntity?

    @Query("SELECT * FROM learning_levels WHERE code = :code LIMIT 1")
    suspend fun getByCode(code: String): LearningLevelEntity?

    /**
     * Resolves the level for an HSK number, clamping to the nearest level the app defines.
     *
     * `levelId` on `vocabulary` is NOT NULL with a RESTRICT foreign key, so an unresolvable
     * number must never reach the insert. The table holds a handful of rows, so ordering by
     * distance is cheap and, unlike a range predicate, it cannot return nothing.
     */
    @Query("SELECT * FROM learning_levels ORDER BY ABS(ordinal - :hskLevel) ASC, ordinal ASC LIMIT 1")
    suspend fun getNearestToHsk(hskLevel: Int): LearningLevelEntity?

    /** IGNORE: a level row that already exists must keep its id, which the app references. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(level: LearningLevelEntity): Long

    @Upsert
    suspend fun upsertAll(levels: List<LearningLevelEntity>)
}

@Dao
interface CharacterDao {

    @Query("SELECT * FROM characters WHERE character = :character LIMIT 1")
    suspend fun getByCharacter(character: String): CharacterEntity?

    @Query("SELECT * FROM characters WHERE codePoint = :codePoint LIMIT 1")
    suspend fun getByCodePoint(codePoint: Int): CharacterEntity?

    /**
     * IGNORE rather than REPLACE: the unique index on `character` is what stops a second copy
     * of the same glyph, and REPLACE would delete the existing row and cascade away the
     * vocabulary entries that point at it.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(character: CharacterEntity): Long

    @Query("SELECT COUNT(*) FROM characters")
    suspend fun count(): Int
}

@Dao
interface PinyinDao {

    @Query("SELECT * FROM pinyin_syllables WHERE syllable = :syllable AND toneNumber = :toneNumber LIMIT 1")
    suspend fun get(syllable: String, toneNumber: Int): PinyinSyllableEntity?

    @Query("SELECT * FROM pinyin_syllables ORDER BY syllable ASC, toneNumber ASC")
    suspend fun getAll(): List<PinyinSyllableEntity>

    /** See [CharacterDao.insertIfAbsent] for why this is IGNORE and not REPLACE. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(syllable: PinyinSyllableEntity): Long

    @Query("SELECT COUNT(*) FROM pinyin_syllables")
    suspend fun count(): Int
}
