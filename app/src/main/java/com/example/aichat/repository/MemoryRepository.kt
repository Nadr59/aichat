package com.example.aichat.repository

import android.content.Context
import com.example.aichat.data.local.AiSettings
import com.example.aichat.data.local.MemoryDao
import com.example.aichat.data.model.MemoryItem
import kotlinx.coroutines.flow.Flow

class MemoryRepository(
    private val memoryDao: MemoryDao,
    private val context: Context
) {

    // ✅ استخدام AiSettings مباشرة
    private val aiSettings = AiSettings(context)

    private val embeddingService: EmbeddingService
        get() = EmbeddingService(aiSettings.geminiKey)

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

        // ✅ محاولة استخراج Embedding
        val embedding = try {
            val apiKey = aiSettings.geminiKey
            
            if (apiKey.isBlank()) {
                android.util.Log.w("MemoryRepository", "⚠️ Skipping embedding - Gemini API key not configured")
                ""
            } else {
                android.util.Log.d("MemoryRepository", "🔄 Extracting embedding for: ${cleanContent.take(50)}...")
                val vector = embeddingService.getEmbedding(cleanContent)
                
                if (vector.isNotEmpty()) {
                    android.util.Log.d("MemoryRepository", "✅ Embedding extracted: ${vector.size} dimensions")
                    embeddingService.vectorToString(vector)
                } else {
                    android.util.Log.w("MemoryRepository", "⚠️ Empty embedding returned")
                    ""
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MemoryRepository", "❌ Embedding extraction failed: ${e.message}")
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
            android.util.Log.d("MemoryRepository", "⚪ Empty query")
            return emptyList()
        }

        val allMemories = memoryDao.getAllSharedMemories()

        if (allMemories.isEmpty()) {
            android.util.Log.d("MemoryRepository", "⚪ No memories in database")
            return emptyList()
        }

        android.util.Log.d("MemoryRepository", "📚 Total memories: ${allMemories.size}")

        // ✅ تحقق: كم ذاكرة لها embeddings؟
        val withEmbeddings = allMemories.count { it.embedding.isNotBlank() }
        android.util.Log.d("MemoryRepository", "🧠 Memories with embeddings: $withEmbeddings/${allMemories.size}")

        val hasEmbeddings = withEmbeddings > 0
        val hasApiKey = aiSettings.geminiKey.isNotBlank()

        return if (hasEmbeddings && hasApiKey) {
            android.util.Log.d("MemoryRepository", "🔍 Using semantic search (Embeddings)")
            searchWithEmbeddings(cleanQuery, allMemories)
        } else {
            if (!hasApiKey) {
                android.util.Log.w("MemoryRepository", "⚠️ Gemini API key not configured, using traditional search")
            }
            android.util.Log.d("MemoryRepository", "🔍 Using traditional search (Keywords)")
            val result = MemorySearchEngine.search(
                query = cleanQuery,
                memories = allMemories,
                limit = 8,
                minScore = 0.05
            )
            android.util.Log.d("MemoryRepository", "📊 Found: ${result.memories.size} memories")
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
            android.util.Log.d("MemoryRepository", "🔄 Getting query embedding for: $query")
            embeddingService.getEmbedding(query)
        } catch (e: Exception) {
            android.util.Log.e("MemoryRepository", "❌ Query embedding failed: ${e.message}")
            return fallbackSearch(query, memories)
        }

        if (queryVector.isEmpty()) {
            android.util.Log.w("MemoryRepository", "⚠️ Empty query vector, using fallback")
            return fallbackSearch(query, memories)
        }

        android.util.Log.d("MemoryRepository", "✅ Query embedding: ${queryVector.size} dimensions")

        val scored = memories.mapNotNull { memory ->
            if (memory.embedding.isBlank()) {
                return@mapNotNull null
            }

            val memoryVector = embeddingService.stringToVector(memory.embedding)
            
            if (memoryVector.isEmpty()) {
                return@mapNotNull null
            }

            val similarity = embeddingService.cosineSimilarity(queryVector, memoryVector)
            
            android.util.Log.d("MemoryRepository", "📊 Similarity: ${String.format("%.3f", similarity)} - ${memory.content.take(50)}...")
            
            if (similarity >= 0.5) {
                Pair(memory, similarity)
            } else {
                null
            }
        }

        val results = scored
            .sortedByDescending { it.second }
            .take(8)
            .map { it.first }

        android.util.Log.d("MemoryRepository", "✅ Found ${results.size} semantically similar memories")

        return results
    }

    /**
     * Fallback للبحث التقليدي
     */
    private fun fallbackSearch(query: String, memories: List<MemoryItem>): List<MemoryItem> {
        android.util.Log.d("MemoryRepository", "🔄 Falling back to traditional keyword search")
        val result = MemorySearchEngine.search(
            query = query,
            memories = memories,
            limit = 8,
            minScore = 0.05
        )
        android.util.Log.d("MemoryRepository", "📊 Fallback found: ${result.memories.size} memories")
        return result.memories
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
