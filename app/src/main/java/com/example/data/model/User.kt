package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val identifier: String, // Email or Phone number
    val authType: String = "EMAIL", // "EMAIL" or "PHONE"
    val passwordHash: String,
    val displayName: String,
    val token: String,
    val createdAt: Long = System.currentTimeMillis()
)
