package com.example.data.model

import androidx.room.Embedded
import androidx.room.Relation

data class WordWithSrs(
    @Embedded val word: WordEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "wordId"
    )
    val srs: SrsReviewEntity?
) {
    val isDue: Boolean
        get() {
            val due = srs?.dueDateMillis ?: 0L
            return due <= System.currentTimeMillis()
        }

    val state: String
        get() = srs?.state ?: "NEW"
}
