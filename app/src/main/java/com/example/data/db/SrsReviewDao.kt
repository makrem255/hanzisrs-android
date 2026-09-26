package com.example.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.model.SrsReviewEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SrsReviewDao {
    @Query("SELECT * FROM srs_reviews WHERE wordId = :wordId LIMIT 1")
    suspend fun getReviewForWord(wordId: Long): SrsReviewEntity?

    @Query("SELECT * FROM srs_reviews WHERE userId = :userId")
    fun getAllReviewsForUser(userId: Long): Flow<List<SrsReviewEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateReview(review: SrsReviewEntity): Long

    @Update
    suspend fun updateReview(review: SrsReviewEntity)

    @Query("DELETE FROM srs_reviews WHERE wordId = :wordId")
    suspend fun deleteReviewForWord(wordId: Long)
}
