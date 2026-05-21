package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey
    val uid: String,
    val name: String,
    val email: String,
    val phoneNumber: String? = null,
    val providerId: String, // "email", "google", "phone"
    val avatar: String,
    val joinedDate: String,
    val isTemp: Boolean = false
)
