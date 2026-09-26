package com.example.data.repository

import com.example.data.db.SrsReviewDao
import com.example.data.model.SrsReviewEntity
import com.example.data.srs.SrsAlgorithm
import com.example.data.srs.SrsCalculationResult
import com.example.data.srs.SrsRating
import kotlinx.coroutines.flow.Flow

data class SrsStats(
    val totalWords: Int,
    val dueToday: Int,
    val newCount: Int,
    val learningCount: Int,
    val reviewCount: Int,
    val masteredCount: Int,
    val retentionRate: Int,
    val dailyStreakDays: Int
)

class SrsRepository(private val srsReviewDao: SrsReviewDao) {

    fun getAllReviewsForUser(userId: Long): Flow<List<SrsReviewEntity>> {
        return srsReviewDao.getAllReviewsForUser(userId)
    }

    suspend fun getReviewForWord(wordId: Long): SrsReviewEntity? {
        return srsReviewDao.getReviewForWord(wordId)
    }

    suspend fun processReview(
        wordId: Long,
        userId: Long,
        rating: SrsRating
    ): SrsCalculationResult {
        val existing = srsReviewDao.getReviewForWord(wordId)
        val now = System.currentTimeMillis()
        val result = SrsAlgorithm.calculateNextReview(existing, rating, now)

        val updatedEntity = (existing ?: SrsReviewEntity(
            wordId = wordId,
            userId = userId
        )).copy(
            intervalDays = result.intervalDays,
            repetitions = result.repetitions,
            easeFactor = result.easeFactor,
            dueDateMillis = result.dueDateMillis,
            lastReviewMillis = now,
            state = result.state,
            lastRating = rating.value,
            totalReviews = (existing?.totalReviews ?: 0) + 1
        )

        srsReviewDao.insertOrUpdateReview(updatedEntity)
        return result
    }

    suspend fun resetReview(wordId: Long, userId: Long) {
        val existing = srsReviewDao.getReviewForWord(wordId)
        val resetEntity = (existing ?: SrsReviewEntity(
            wordId = wordId,
            userId = userId
        )).copy(
            intervalDays = 1,
            repetitions = 0,
            easeFactor = 2.5,
            dueDateMillis = System.currentTimeMillis(),
            lastReviewMillis = 0L,
            state = "NEW",
            lastRating = 0
        )
        srsReviewDao.insertOrUpdateReview(resetEntity)
    }
}
