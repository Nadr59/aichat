package com.example.aichat.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.aichat.data.model.MemoryItem
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryDao {

    // ============================================================
    // إضافة ذاكرة
    // مُصحَّح: إضافة OnConflictStrategy.IGNORE
    // السبب: إذا تكرر contentHash (مرحلة 3) لن يحدث crash
    // ============================================================

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMemory(memory: MemoryItem): Long

    // ============================================================
    // تعديل ذاكرة
    // ============================================================

    @Update
    suspend fun updateMemory(memory: MemoryItem)

    // ============================================================
    // حذف ذاكرة
    // ============================================================

    @Delete
    suspend fun deleteMemory(memory: MemoryItem)

    // ============================================================
    // جميع الذكريات المشتركة (Flow - للمراقبة المستمرة)
    // ============================================================

    @Query(
        """
        SELECT * FROM memory_items
        WHERE isShared = 1
        ORDER BY updatedAt DESC
        """
    )
    fun getSharedMemories(): Flow<List<MemoryItem>>

    // ============================================================
    // جميع الذكريات المشتركة (List - للبحث والمعالجة)
    // ============================================================

    @Query(
        """
        SELECT * FROM memory_items
        WHERE isShared = 1
        ORDER BY updatedAt DESC
        """
    )
    suspend fun getAllSharedMemories(): List<MemoryItem>

    // ============================================================
    // ✅ مُضاف: البحث بالـ Hash لمنع التكرار (مرحلة 3)
    // السبب: قبل إضافة ذاكرة جديدة نتحقق إذا المحتوى موجود مسبقاً
    // ============================================================

    @Query(
        """
        SELECT * FROM memory_items
        WHERE contentHash = :hash
        LIMIT 1
        """
    )
    suspend fun getByHash(hash: String): MemoryItem?

    // ============================================================
    // ✅ مُضاف: البحث النصي المباشر في المحتوى
    // السبب: بحث سريع بدون Embeddings كـ fallback
    // ============================================================

    @Query(
        """
        SELECT * FROM memory_items
        WHERE isShared = 1
        AND content LIKE '%' || :query || '%'
        ORDER BY updatedAt DESC
        LIMIT :limit
        """
    )
    suspend fun searchByContent(
        query: String,
        limit: Int = 10
    ): List<MemoryItem>

    // ============================================================
    // ذكريات محادثة محددة
    // ============================================================

    @Query(
        """
        SELECT * FROM memory_items
        WHERE sourceConversationId = :conversationId
        ORDER BY updatedAt DESC
        """
    )
    fun getConversationMemories(
        conversationId: Long
    ): Flow<List<MemoryItem>>

    // ============================================================
    // الحصول على ذاكرة محددة بالـ ID
    // ============================================================

    @Query(
        """
        SELECT * FROM memory_items
        WHERE id = :memoryId
        LIMIT 1
        """
    )
    suspend fun getMemoryById(memoryId: Long): MemoryItem?

    // ============================================================
    // حذف جميع ذكريات محادثة محددة
    // ============================================================

    @Query(
        """
        DELETE FROM memory_items
        WHERE sourceConversationId = :conversationId
        """
    )
    suspend fun deleteConversationMemories(conversationId: Long)

    // ============================================================
    // ✅ مُضاف: عدد الذكريات الكلي
    // السبب: إحصائيات + تحديد إذا الذاكرة ممتلئة
    // ============================================================

    @Query(
        """
        SELECT COUNT(*) FROM memory_items
        WHERE isShared = 1
        """
    )
    suspend fun getSharedMemoriesCount(): Int

    // ============================================================
    // ✅ مُضاف: حذف الذكريات القديمة إذا تجاوز الحد
    // السبب: منع تضخم قاعدة البيانات مع الوقت
    // ============================================================

    @Query(
        """
        DELETE FROM memory_items
        WHERE id IN (
            SELECT id FROM memory_items
            WHERE isShared = 1
            ORDER BY updatedAt ASC
            LIMIT :count
        )
        """
    )
    suspend fun deleteOldestMemories(count: Int)
}
