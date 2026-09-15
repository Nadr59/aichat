package com.example.aichat.repository

import android.content.Context
import com.example.aichat.data.local.MemoryDao
import com.example.aichat.data.local.AiSettings
import com.example.aichat.data.model.MemoryItem
import kotlinx.coroutines.flow.Flow

class MemoryRepository(
    private val memoryDao: MemoryDao,
    private val context: Context
) {

    // ✅ الحصول على Gemini API Key من SharedPreferences مباشرة
    private fun getGeminiApiKey(): String {
        val prefs = context.getSharedPreferences("ai_settings", Context.MODE_PRIVATE)
        return prefs.getString("gemini_api_key", "") ?: ""
    }

    private val embeddingService: EmbeddingService
        get() = EmbeddingService(getGeminiApiKey())

    // ============================================================
    // إضافة ذاكرة مع Embedding
    // ============================================================

    suspend fun addMemory(
        content: String,
        sourceConversationId: Long? = null,
        sourceMessageId: Long? = null,
        category: String = "OTHER",
        isShared: Boolean = true
    ): Long {

        val cleanContent = content.trim()

        if (cleanContent.isBlank()) {
            return 0
        }

        // ✅ استخراج Embedding من Gemini
        val embedding = try {
            val vector = embeddingService.getEmbedding(cleanContent)
            if (vector.isNotEmpty()) {
                embeddingService.vectorToString(vector)
            } else {
                ""
            }
        } catch (e: Exception) {
            ""
        }

        val memory = MemoryItem(
            content = cleanContent,
            embedding = embedding,
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
        useSemanticAnalysis: Boolean = false,
        ollamaUrl: String = "http://127.0.0.1:11434"
    ): List<MemoryItem> {

        val cleanQuery = query.trim()

        if (cleanQuery.isBlank()) {
            return emptyList()
        }

        val allMemories = memoryDao.getAllSharedMemories()

        if (allMemories.isEmpty()) {
            return emptyList()
        }

        // ✅ تحقق: هل الذكريات لها embeddings؟
        val hasEmbeddings = allMemories.any { it.embedding.isNotBlank() }

        return if (hasEmbeddings) {
            searchWithEmbeddings(cleanQuery, allMemories)
        } else {
            val result = MemorySearchEngine.search(
                query = cleanQuery,
                memories = allMemories,
                limit = 8,
                minScore = 0.05
            )
            result.memories
        }
    }

    /**
     * البحث باستخدام Embeddings
     */
    private suspend fun searchWithEmbeddings(
        query: String,
        memories: List<MemoryItem>
    ): List<MemoryItem> {

        val queryVector = try {
            embeddingService.getEmbedding(query)
        } catch (e: Exception) {
            return emptyList()
        }

        if (queryVector.isEmpty()) {
            val result = MemorySearchEngine.search(
                query = query,
                memories = memories,
                limit = 8,
                minScore = 0.05
            )
            return result.memories
        }

        val scored = memories.mapNotNull { memory ->
            if (memory.embedding.isBlank()) {
                return@mapNotNull null
            }

            val memoryVector = embeddingService.stringToVector(memory.embedding)
            
            if (memoryVector.isEmpty()) {
                return@mapNotNull null
            }

            val similarity = embeddingService.cosineSimilarity(
                queryVector, 
                memoryVector
            )
            
            if (similarity >= 0.5) {
                Pair(memory, similarity)
            } else {
                null
            }
        }

        return scored
            .sortedByDescending { it.second }
            .take(8)
            .map { it.first }
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
