package com.example.aichat.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "messages")
data class Message(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val conversationId: Long,
    val role: String,       // "user" أو "assistant"
    val content: String,
    val imageBase64: String? = null,  // صورة مرفقة إن وجدت
    val timestamp: Long = System.currentTimeMillis()
)
