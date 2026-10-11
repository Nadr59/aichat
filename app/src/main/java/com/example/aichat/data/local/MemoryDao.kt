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
    // إضافة ذاكرة (واحدة)
    // ============================================================

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMemory(memory: MemoryItem): Long

    // ============================================================
    // ✅ إضافة عدة ذكريات دفعة واحدة (مفيد جداً مع chunking)
    // ============================================================

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMemories(memories: List<MemoryItem>): List<Long>

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
    // جميع الذكريات المشتركة (Flow)
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
    // جميع الذكريات المشتركة - List
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
    // البحث بالـ Hash لمنع التكرار (واحد)
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
    // ✅ البحث بمجموعة Hashes (مفيد لتسريع dedup مع chunking)
    // ============================================================

    @Query(
        """
        SELECT * FROM memory_items
        WHERE contentHash IN (:hashes)
        """
    )
    suspend fun getByHashes(hashes: List<String>): List<MemoryItem>

    // ============================================================
    // البحث النصي المباشر في المحتوى
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
    // ذكريات محادثة محددة (غير مشتركة فقط)
    // ============================================================

    @Query(
        """
        SELECT * FROM memory_items
        WHERE sourceConversationId = :conversationId
        AND isShared = 0
        ORDER BY updatedAt DESC
        """
    )
    fun getConversationMemories(conversationId: Long): Flow<List<MemoryItem>>

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
    // ✅ حذف جميع ذكريات محادثة محددة (غير مشتركة فقط)
    // (تصحيح لمنع حذف shared بالخطأ إذا كانت مربوطة بمحادثة)
    // ============================================================

    @Query(
        """
        DELETE FROM memory_items
        WHERE sourceConversationId = :conversationId
        AND isShared = 0
        """
    )
    suspend fun deleteConversationMemories(conversationId: Long)

    // ============================================================
    // عدد الذكريات المشتركة
    // ============================================================

    @Query(
        """
        SELECT COUNT(*) FROM memory_items
        WHERE isShared = 1
        """
    )
    suspend fun getSharedMemoriesCount(): Int

    // ============================================================
    // حذف الذكريات القديمة إذا تجاوز الحد
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
