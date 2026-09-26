package com.example.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "words",
    foreignKeys = [
        ForeignKey(
            entity = UserEntity::class,
            parentColumns = ["id"],
            childColumns = ["userId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("userId"),
        Index(value = ["userId", "hanzi"], unique = true)
    ]
)
data class WordEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val userId: Long,
    val hanzi: String,
    val pinyin: String,
    val meaning: String,
    val hskLevel: Int = 1,
    val radical: String = "",
    val exampleCn: String = "",
    val examplePy: String = "",
    val exampleEn: String = "",
    val strokeJson: String = "", // JSON representation of stroke paths & directions
    val tags: String = "",
    val createdAt: Long = System.currentTimeMillis()
)
