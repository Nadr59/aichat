package com.example.aichat.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.example.aichat.data.model.MemoryItem
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryDao {

    // ============================================================
    // إضافة ذاكرة
    // ============================================================

    @Insert
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
    // جميع الذكريات المشتركة
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
    // البحث في الذكريات المشتركة
    // ============================================================

    @Query(
        """
        SELECT * FROM memory_items
        WHERE isShared = 1
        AND (
            content LIKE '%' || :query || '%'
            OR category LIKE '%' || :query || '%'
        )
        ORDER BY updatedAt DESC
        """
    )
    suspend fun searchSharedMemories(query: String): List<MemoryItem>

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
    // الحصول على ذاكرة محددة
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
}
