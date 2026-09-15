package com.example.aichat.repository

import android.content.Context
import android.util.Log
import com.example.aichat.data.local.AiSettings
import com.example.aichat.data.local.MemoryDao
import com.example.aichat.data.model.MemoryItem
import kotlinx.coroutines.flow.Flow

class MemoryRepository(
    private val memoryDao: MemoryDao,
    private val context: Context
) {

    companion object {
        private const val TAG = "MemoryRepository"
        private const val SIMILARITY_THRESHOLD = 0.45
        private const val MAX_RESULTS = 8
    }

    private val aiSettings = AiSettings(context)

    private val embeddingService: EmbeddingService
        get() = EmbeddingService(aiSettings.geminiKey)

    // ============================================================
    // استخراج Embedding (منطق موحد للحفظ والتعديل)
    // ============================================================

    private suspend fun extractEmbedding(content: String): String {
        val apiKey = aiSettings.geminiKey

        if (apiKey.isBlank()) {
            Log.w(TAG, "⚠️ Skipping embedding - Gemini API key not configured")
            return ""
        }

        Log.d(TAG, "🔄 Extracting embedding for: ${content.take(50)}...")
        val vector = embeddingService.getEmbedding(content)

        return if (vector.isNotEmpty()) {
            Log.d(TAG, "✅ Embedding extracted: ${vector.size} dimensions")
            embeddingService.vectorToString(vector)
        } else {
            Log.w(TAG, "⚠️ Empty embedding returned")
            ""
        }
    }

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
        if (cleanContent.isBlank()) return 0

        val embedding = extractEmbedding(cleanContent)

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
    // تعديل ذاكرة (✅ مع إعادة حساب الـ Embedding)
    // ============================================================

    suspend fun updateMemory(memory: MemoryItem) {
        val cleanContent = memory.content.trim()
        val newEmbedding = extractEmbedding(cleanContent)

        memoryDao.updateMemory(
            memory.copy(
                content = cleanContent,
                embedding = newEmbedding,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    // ============================================================
    // ✅ Backfill: استخراج Embeddings للذكريات القديمة
    // استدعها عند حفظ Gemini API Key في الإعدادات
    // ============================================================

    suspend fun backfillEmbeddings(): Int {
        if (aiSettings.geminiKey.isBlank()) return 0

        val missing = memoryDao.getAllSharedMemories()
            .filter { it.embedding.isBlank() }

        if (missing.isEmpty()) {
            Log.d(TAG, "✅ Backfill: all memories already have embeddings")
            return 0
        }

        Log.d(TAG, "🔄 Backfill: processing ${missing.size} memories...")
        var updated = 0

        missing.forEach { memory ->
            val embedding = extractEmbedding(memory.content)
            if (embedding.isNotBlank()) {
                memoryDao.updateMemory(memory.copy(embedding = embedding))
                updated++
            }
        }

        Log.d(TAG, "✅ Backfill complete: $updated/${missing.size} updated")
        return updated
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
            Log.d(TAG, "⚪ Empty query")
            return emptyList()
        }

        val allMemories = memoryDao.getAllSharedMemories()

        if (allMemories.isEmpty()) {
            Log.d(TAG, "⚪ No memories in database")
            return emptyList()
        }

        Log.d(TAG, "📚 Total memories: ${allMemories.size}")

        val withEmbeddings = allMemories.count { it.embedding.isNotBlank() }
        Log.d(TAG, "🧠 Memories with embeddings: $withEmbeddings/${allMemories.size}")

        val hasEmbeddings = withEmbeddings > 0
        val hasApiKey = aiSettings.geminiKey.isNotBlank()

        return if (hasEmbeddings && hasApiKey) {
            Log.d(TAG, "🔍 Using hybrid search (Embeddings + Keywords)")
            searchWithEmbeddings(cleanQuery, allMemories)
        } else {
            if (!hasApiKey) {
                Log.w(TAG, "⚠️ Gemini API key not configured, using traditional search")
            }
            Log.d(TAG, "🔍 Using traditional search (Keywords)")
            fallbackSearch(cleanQuery, allMemories)
        }
    }

    /**
     * ✅ البحث الهجين:
     * - دلالي للذكريات التي تملك embedding
     * - تقليدي للذكريات التي لا تملكه
     * - Fallback كامل عند صفر نتائج
     */
    private suspend fun searchWithEmbeddings(
        query: String,
        memories: List<MemoryItem>
    ): List<MemoryItem> {

        val (withEmb, withoutEmb) = memories.partition { it.embedding.isNotBlank() }

        val queryVector = embeddingService.getEmbedding(query)

        if (queryVector.isEmpty()) {
            Log.w(TAG, "⚠️ Empty query vector, using full fallback")
            return fallbackSearch(query, memories)
        }

        Log.d(TAG, "✅ Query embedding: ${queryVector.size} dimensions")

        // البحث الدلالي
        val semanticResults = withEmb.mapNotNull { memory ->
            val memoryVector = embeddingService.stringToVector(memory.embedding)
            if (memoryVector.isEmpty()) return@mapNotNull null

            val similarity = embeddingService.cosineSimilarity(queryVector, memoryVector)
            Log.d(TAG, "📊 Similarity: ${String.format("%.3f", similarity)} - ${memory.content.take(50)}...")

            if (similarity >= SIMILARITY_THRESHOLD) Pair(memory, similarity) else null
        }
            .sortedByDescending { it.second }
            .map { it.first }

        // ✅ البحث التقليدي على الذكريات بلا embedding (كانت غير مرئية سابقاً)
        val keywordResults = if (withoutEmb.isNotEmpty()) {
            MemorySearchEngine.search(
                query = query,
                memories = withoutEmb,
                limit = MAX_RESULTS,
                minScore = 0.05
            ).memories
        } else {
            emptyList()
        }

        val combined = (semanticResults + keywordResults)
            .distinctBy { it.id }
            .take(MAX_RESULTS)

        // ✅ Fallback نهائي: صفر نتائج → بحث تقليدي على الكل
        if (combined.isEmpty()) {
            Log.w(TAG, "⚠️ No semantic matches above $SIMILARITY_THRESHOLD, trying full fallback")
            return fallbackSearch(query, memories)
        }

        Log.d(TAG, "✅ Found ${combined.size} memories (${semanticResults.size} semantic + ${keywordResults.size} keyword)")
        return combined
    }

    /**
     * Fallback للبحث التقليدي
     */
    private fun fallbackSearch(query: String, memories: List<MemoryItem>): List<MemoryItem> {
        val result = MemorySearchEngine.search(
            query = query,
            memories = memories,
            limit = MAX_RESULTS,
            minScore = 0.05
        )
        Log.d(TAG, "📊 Keyword search found: ${result.memories.size} memories")
        return result.memories
    }

    // ============================================================
    // باقي الدوال (بلا تغيير)
    // ============================================================

    fun getConversationMemories(conversationId: Long): Flow<List<MemoryItem>> {
        return memoryDao.getConversationMemories(conversationId)
    }

    suspend fun getMemoryById(memoryId: Long): MemoryItem? {
        return memoryDao.getMemoryById(memoryId)
    }

    suspend fun deleteConversationMemories(conversationId: Long) {
        memoryDao.deleteConversationMemories(conversationId)
    }
}
