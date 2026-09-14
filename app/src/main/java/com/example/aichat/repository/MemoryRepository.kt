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
    query: String,
    useSemanticAnalysis: Boolean = true,
    ollamaUrl: String = "http://127.0.0.1:11434"
): List<MemoryItem> {

    val cleanQuery = query.trim()

    if (cleanQuery.isBlank()) {
        return emptyList()
    }

    val allMemories = memoryDao.getAllSharedMemories()
    
    val localResult = MemorySearchEngine.search(
        query = cleanQuery,
        memories = allMemories,
        limit = 8,
        minScore = 0.05  // ← خفضناه من 0.1 إلى 0.05
    )

    if (localResult.memories.isEmpty()) {
        return emptyList()
    }

    if (localResult.hasStrongMatch || localResult.averageScore >= 0.3) {
        return localResult.memories
    }

    if (useSemanticAnalysis) {
        return try {
            SemanticMemoryAnalyzer.analyzeMemories(
                query = cleanQuery,
                candidates = localResult.memories,
                ollamaUrl = ollamaUrl
            )
        } catch (e: Exception) {
            localResult.memories
        }
    }

    return localResult.memories
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
