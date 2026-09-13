package com.example.aichat.repository

import com.example.aichat.data.local.MemoryDao
import com.example.aichat.data.model.MemoryItem
import kotlinx.coroutines.flow.Flow

class MemoryRepository(
    private val memoryDao: MemoryDao
) {

    // ============================================================
    // إضافة ذاكرة
    // ============================================================

    suspend fun addMemory(
        content: String,
        sourceConversationId: Long? = null,
        sourceMessageId: Long? = null,
        category: String = "OTHER",
        isShared: Boolean = true
    ): Long {

        val memory = MemoryItem(
            content = content.trim(),
            sourceConversationId = sourceConversationId,
            sourceMessageId = sourceMessageId,
            category = category,
            isShared = isShared
        )

        return memoryDao.insertMemory(memory)
    }

    // ============================================================
    // تعديل ذاكرة
    // ============================================================

    suspend fun updateMemory(memory: MemoryItem) {
        memoryDao.updateMemory(
            memory.copy(
                content = memory.content.trim(),
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    // ============================================================
    // حذف ذاكرة
    // ============================================================

    suspend fun deleteMemory(memory: MemoryItem) {
        memoryDao.deleteMemory(memory)
    }

    // ============================================================
    // جميع الذكريات المشتركة
    // ============================================================

    fun getSharedMemories(): Flow<List<MemoryItem>> {
        return memoryDao.getSharedMemories()
    }

    // ============================================================
    // البحث في الذكريات المشتركة
    // ============================================================

    suspend fun searchSharedMemories(
        query: String
    ): List<MemoryItem> {

        val cleanQuery = query.trim()

        if (cleanQuery.isBlank()) {
            return emptyList()
        }

        return memoryDao.searchSharedMemories(cleanQuery)
    }

    // ============================================================
    // ذكريات محادثة محددة
    // ============================================================

    fun getConversationMemories(
        conversationId: Long
    ): Flow<List<MemoryItem>> {
        return memoryDao.getConversationMemories(conversationId)
    }

    // ============================================================
    // الحصول على ذاكرة محددة
    // ============================================================

    suspend fun getMemoryById(
        memoryId: Long
    ): MemoryItem? {
        return memoryDao.getMemoryById(memoryId)
    }

    // ============================================================
    // حذف ذكريات محادثة
    // ============================================================

    suspend fun deleteConversationMemories(
        conversationId: Long
    ) {
        memoryDao.deleteConversationMemories(conversationId)
    }
}
