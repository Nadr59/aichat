package com.example.aichat.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "memory_items")
data class MemoryItem(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val content: String,

    // Embedding vector (JSON string)
    val embedding: String = "",

    // ✅ مُضاف: معلومات النموذج المستخدم لتوليد الـ Embedding
    // السبب: إذا تغير النموذج مستقبلاً (text-embedding-004 → gemini-embedding-001)
    // الـ vectors القديمة لن تتوافق مع الجديدة → cosineSimilarity = 0 دائماً
    val embeddingModel: String = "text-embedding-004",

    // ✅ مُضاف: عدد أبعاد الـ vector
    // السبب: التحقق من التوافق قبل حساب التشابه
    // text-embedding-004 = 768 بُعد
    val embeddingDimensions: Int = 768,

    val sourceConversationId: Long? = null,
    val sourceMessageId: Long? = null,
    val category: String = "OTHER",
    val isShared: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
