package com.example.aichat.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "memory_items")
data class MemoryItem(

    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    // محتوى المعلومة المحفوظة
    val content: String,

    // المحادثة التي جاءت منها المعلومة، إن وجدت
    val sourceConversationId: Long? = null,

    // الرسالة التي جاءت منها المعلومة، إن وجدت
    val sourceMessageId: Long? = null,

    // نوع الذاكرة: KNOWLEDGE / PROJECT / PREFERENCE / OTHER
    val category: String = "OTHER",

    // true = ذاكرة مشتركة بين النماذج
    // false = ذاكرة محلية
    val isShared: Boolean = true,

    // وقت الإنشاء
    val createdAt: Long = System.currentTimeMillis(),

    // آخر تعديل
    val updatedAt: Long = System.currentTimeMillis()
)
