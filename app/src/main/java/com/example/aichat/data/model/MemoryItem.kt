package com.example.aichat.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "memory_items")
data class MemoryItem(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    val content: String,
    
    // ✅ Embedding vector (JSON string)
    val embedding: String = "",
    
    val sourceConversationId: Long? = null,
    val sourceMessageId: Long? = null,
    val category: String = "OTHER",
    val isShared: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
