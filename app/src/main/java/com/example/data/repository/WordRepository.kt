package com.example.data.repository

import androidx.room.withTransaction
import com.example.data.db.AppDatabase
import com.example.data.model.SrsReviewEntity
import com.example.data.model.WordEntity
import com.example.data.model.WordWithSrs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow

sealed interface SaveWordResult {
    data class Saved(val wordId: Long) : SaveWordResult
    data object Duplicate : SaveWordResult
}

class WordRepository(private val database: AppDatabase) {
    private val wordDao = database.wordDao()
    private val srsReviewDao = database.srsReviewDao()
    fun getWordsForUser(userId: Long): Flow<List<WordWithSrs>> {
        return wordDao.getWordsWithSrsForUser(userId)
    }

    fun getDueWordsForUser(userId: Long): Flow<List<WordWithSrs>> =
        combine(wordDao.getWordsWithSrsForUser(userId), dueClock()) { words, now ->
            words.filter { (it.srs?.dueDateMillis ?: 0L) <= now }
                .sortedBy { it.srs?.dueDateMillis ?: Long.MIN_VALUE }
        }

    suspend fun findWordByHanzi(userId: Long, hanzi: String): WordEntity? {
        return wordDao.findWordByHanzi(userId, hanzi)
    }

    suspend fun saveNewWordWithInitialSrs(
        word: WordEntity,
        initialDueImmediate: Boolean = true
    ): SaveWordResult = database.withTransaction {
        if (wordDao.findWordByHanzi(word.userId, word.hanzi) != null) {
            return@withTransaction SaveWordResult.Duplicate
        }

        val wordId = wordDao.insertWord(word)
        val now = System.currentTimeMillis()
        val dueTime = if (initialDueImmediate) now else now + 86_400_000L

        val initialSrs = SrsReviewEntity(
            wordId = wordId,
            userId = word.userId,
            intervalDays = 1,
            repetitions = 0,
            easeFactor = 2.5,
            dueDateMillis = dueTime,
            lastReviewMillis = 0L,
            state = "NEW",
            lastRating = 0,
            totalReviews = 0
        )
        srsReviewDao.insertOrUpdateReview(initialSrs)
        SaveWordResult.Saved(wordId)
    }

    suspend fun updateWord(word: WordEntity) {
        wordDao.updateWord(word)
    }

    suspend fun deleteWord(wordId: Long) = database.withTransaction {
        // The foreign-key cascade removes the associated review with the word.
        wordDao.deleteWord(wordId)
    }

    private fun dueClock(): Flow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(60_000L)
        }
    }
}
