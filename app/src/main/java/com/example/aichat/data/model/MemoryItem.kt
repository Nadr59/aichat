package com.example.aichat.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// ✅ مُضاف: Index على contentHash لمنع التكرار وتسريع البحث
@Entity(
    tableName = "memory_items",
    indices = [
        Index(value = ["contentHash"], unique = true)
    ]
)
data class MemoryItem(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val content: String,

    // Embedding vector (JSON string)
    val embedding: String = "",

    // معلومات النموذج المستخدم لتوليد الـ Embedding
    // السبب: إذا تغير النموذج مستقبلاً (text-embedding-004 → gemini-embedding-001)
    // الـ vectors القديمة لن تتوافق مع الجديدة → cosineSimilarity = 0 دائماً
    val embeddingModel: String = "text-embedding-004",

    // عدد أبعاد الـ vector
    // السبب: التحقق من التوافق قبل حساب التشابه
    // text-embedding-004 = 768 بُعد
    val embeddingDimensions: Int = 768,

    // ✅ مُضاف: Hash للمحتوى لمنع حفظ نفس النص مرتين
    // SHA-256 → 64 حرف hex
    // فارغ للذكريات القديمة قبل Migration 4→5
    val contentHash: String = "",

    val sourceConversationId: Long? = null,
    val sourceMessageId: Long? = null,
    val category: String = "OTHER",
    val isShared: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
