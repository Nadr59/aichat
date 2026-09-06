package com.example.aichat.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "messages")
data class Message(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val conversationId: Long,
    val role: String,
    val content: String,
    val imageBase64: String? = null,
    val generatedImageUrl: String? = null, // رابط الصورة المولّدة
    val messageType: String = "text",      // "text" أو "image_generation"
    val timestamp: Long = System.currentTimeMillis()
)
