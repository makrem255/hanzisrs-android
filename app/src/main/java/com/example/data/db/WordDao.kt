package com.example.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.example.data.model.WordEntity
import com.example.data.model.WordWithSrs
import kotlinx.coroutines.flow.Flow

@Dao
interface WordDao {
    @Transaction
    @Query("SELECT * FROM words WHERE userId = :userId ORDER BY id DESC")
    fun getWordsWithSrsForUser(userId: Long): Flow<List<WordWithSrs>>

    @Query("SELECT * FROM words WHERE userId = :userId AND hanzi = :hanzi LIMIT 1")
    suspend fun findWordByHanzi(userId: Long, hanzi: String): WordEntity?

    @Query("SELECT * FROM words WHERE id = :wordId LIMIT 1")
    suspend fun getWordById(wordId: Long): WordEntity?

    @Transaction
    @Query("SELECT * FROM words WHERE id = :wordId LIMIT 1")
    suspend fun getWordWithSrsById(wordId: Long): WordWithSrs?

    // A duplicate character should never silently replace a learner's notes or review history.
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertWord(word: WordEntity): Long

    @Update
    suspend fun updateWord(word: WordEntity)

    @Query("DELETE FROM words WHERE id = :wordId")
    suspend fun deleteWord(wordId: Long)
}
