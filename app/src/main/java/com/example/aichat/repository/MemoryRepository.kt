package com.example.aichat.repository

import android.content.Context
import android.util.Log
import com.example.aichat.data.local.AiSettings
import com.example.aichat.data.local.MemoryDao
import com.example.aichat.data.model.MemoryItem
import kotlinx.coroutines.flow.Flow
import java.security.MessageDigest

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

    private val embeddingService = EmbeddingService { aiSettings.geminiKey }
        // أضف في MemoryRepository

suspend fun getAllSharedMemoriesList(): List<MemoryItem> {
    return memoryDao.getAllSharedMemories()
}

    // ============================================================
    // ✅ حساب Hash للمحتوى لمنع التكرار (مرحلة 3)
    // ============================================================

    private fun calculateHash(content: String): String {
        return try {
            MessageDigest.getInstance("SHA-256")
                .digest(content.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Hash calculation failed: ${e.message}")
            ""
        }
    }

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

        return try {
            val vector = embeddingService.getEmbedding(content)

            if (vector.isNotEmpty()) {
                Log.d(TAG, "✅ Embedding extracted: ${vector.size} dimensions")
                val compressed = embeddingService.vectorToString(vector, compress = true)
                Log.d(TAG, "🗜️ Compressed size: ${compressed.length} chars")
                compressed
            } else {
                Log.w(TAG, "⚠️ Empty embedding returned")
                ""
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Embedding extraction failed: ${e.message}")
            ""
        }
    }

    // ============================================================
    // إضافة ذاكرة مع Embedding
    // ✅ مُصحَّح: إضافة contentHash لمنع التكرار
    // ✅ مُصحَّح: إضافة embeddingModel و embeddingDimensions
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
            Log.w(TAG, "⚠️ Skipping empty content")
            return 0
        }

        // ✅ حساب Hash أولاً
        val hash = calculateHash(cleanContent)

        // ✅ التحقق من التكرار قبل أي عمل
        if (hash.isNotBlank()) {
            val existing = memoryDao.getByHash(hash)
            if (existing != null) {
                Log.d(TAG, "⚠️ Duplicate content detected (id=${existing.id}), skipping")
                return existing.id
            }
        }

        // ✅ استخراج Embedding
        val embedding = extractEmbedding(cleanContent)

        // ✅ تحديد النموذج والأبعاد المستخدمة
        val embeddingModel = if (embedding.isNotBlank()) EmbeddingService.CURRENT_MODEL else ""
        val embeddingDimensions = if (embedding.isNotBlank()) EmbeddingService.CURRENT_DIMENSIONS else 0

        val memory = MemoryItem(
            content = cleanContent,
            embedding = embedding,
            embeddingModel = embeddingModel,
            embeddingDimensions = embeddingDimensions,
            contentHash = hash,
            sourceConversationId = sourceConversationId,
            sourceMessageId = sourceMessageId,
            category = category,
            isShared = isShared
        )

        val id = memoryDao.insertMemory(memory)
        Log.d(TAG, "✅ Memory saved with id=$id, hash=${hash.take(8)}...")
        return id
    }

    

    // ============================================================
    // تعديل ذاكرة مع إعادة حساب الـ Embedding والـ Hash
    // ============================================================

    suspend fun updateMemory(memory: MemoryItem) {
        val cleanContent = memory.content.trim()
        val newEmbedding = extractEmbedding(cleanContent)
        val newHash = calculateHash(cleanContent)

        val embeddingModel = if (newEmbedding.isNotBlank()) EmbeddingService.CURRENT_MODEL else ""
        val embeddingDimensions = if (newEmbedding.isNotBlank()) EmbeddingService.CURRENT_DIMENSIONS else 0

        memoryDao.updateMemory(
            memory.copy(
                content = cleanContent,
                embedding = newEmbedding,
                embeddingModel = embeddingModel,
                embeddingDimensions = embeddingDimensions,
                contentHash = newHash,
                updatedAt = System.currentTimeMillis()
            )
        )

        Log.d(TAG, "✅ Memory updated id=${memory.id}")
    }

    // ============================================================
    // Backfill: استخراج Embeddings والـ Hash للذكريات القديمة
    // استدعها عند حفظ Gemini API Key في الإعدادات
    // ============================================================

    suspend fun backfillEmbeddings(): Int {
        if (aiSettings.geminiKey.isBlank()) return 0

        val allMemories = memoryDao.getAllSharedMemories()

        // ✅ أضف Hash للذكريات التي لا تملكه
        allMemories.filter { it.contentHash.isBlank() }.forEach { memory ->
            val hash = calculateHash(memory.content)
            if (hash.isNotBlank()) {
                memoryDao.updateMemory(memory.copy(contentHash = hash))
            }
        }

        // ✅ أضف Embedding للذكريات التي لا تملكه
        val missing = allMemories.filter { it.embedding.isBlank() }

        if (missing.isEmpty()) {
            Log.d(TAG, "✅ Backfill: all memories already have embeddings")
            return 0
        }

        Log.d(TAG, "🔄 Backfill: processing ${missing.size} memories...")
        var updated = 0

        missing.forEach { memory ->
            val embedding = extractEmbedding(memory.content)
            if (embedding.isNotBlank()) {
                memoryDao.updateMemory(
                    memory.copy(
                        embedding = embedding,
                        embeddingModel = EmbeddingService.CURRENT_MODEL,
                        embeddingDimensions = EmbeddingService.CURRENT_DIMENSIONS
                    )
                )
                updated++
            }
        }

        Log.d(TAG, "✅ Backfill complete: $updated/${missing.size} updated")
        return updated
    }
    // ── أضف هاتين الدالتين في MemoryRepository ───────────────────────────────────

/**
 * التحقق من وجود محتوى بنفس الـ hash (بدون إنشاء embedding)
 * تُستخدم للفلترة المسبقة قبل طلب API
 */
suspend fun existsByHash(hash: String): Boolean {
    if (hash.isBlank()) return false
    return memoryDao.getByHash(hash) != null
}

/**
 * حفظ ذاكرة مع embedding جاهز (من Batch)
 * بدل استدعاء getEmbedding مرة أخرى
 */
suspend fun addMemoryWithEmbedding(
    content:   String,
    embedding: List<Double>,
    category:  String  = "OTHER",
    isShared:  Boolean = true,
    sourceConversationId: Long? = null,
    sourceMessageId:      Long? = null
): Long {
    val cleanContent = content.trim()
    if (cleanContent.isBlank()) return 0

    val hash = calculateHash(cleanContent)

    // تحقق من التكرار
    if (hash.isNotBlank()) {
        val existing = memoryDao.getByHash(hash)
        if (existing != null) {
            Log.d(TAG, "⚠️ Duplicate (id=${existing.id}), skipping")
            return existing.id
        }
    }

    // تحويل embedding إلى String
    val embeddingStr = if (embedding.isNotEmpty()) {
    try {
        EmbeddingService.vectorToString(embedding, compress = true)
    } catch (e: Exception) { "" }
} else ""

    val embeddingModel = if (embeddingStr.isNotBlank()) EmbeddingService.CURRENT_MODEL else ""
    val embeddingDims  = if (embeddingStr.isNotBlank()) EmbeddingService.CURRENT_DIMENSIONS else 0

    val memory = MemoryItem(
        content              = cleanContent,
        embedding            = embeddingStr,
        embeddingModel       = embeddingModel,
        embeddingDimensions  = embeddingDims,
        contentHash          = hash,
        sourceConversationId = sourceConversationId,
        sourceMessageId      = sourceMessageId,
        category             = category,
        isShared             = isShared
    )

    val id = memoryDao.insertMemory(memory)
    Log.d(TAG, "✅ Memory saved with embedding (id=$id)")
    return id
}

    // ============================================================
    // حذف ذاكرة
    // ============================================================

    suspend fun deleteMemory(memory: MemoryItem) {
        memoryDao.deleteMemory(memory)
        Log.d(TAG, "🗑️ Memory deleted id=${memory.id}")
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

    // ============================================================
    // البحث الدلالي
    // ✅ مُصحَّح: التحقق من توافق الأبعاد قبل حساب التشابه
    // ============================================================

    private suspend fun searchWithEmbeddings(
        query: String,
        memories: List<MemoryItem>
    ): List<MemoryItem> {

        val queryVector = try {
            Log.d(TAG, "🔄 Getting query embedding for: $query")
            embeddingService.getEmbedding(query)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Query embedding failed: ${e.message}")
            return fallbackSearch(query, memories)
        }

        if (queryVector.isEmpty()) {
            Log.w(TAG, "⚠️ Empty query vector, using fallback")
            return fallbackSearch(query, memories)
        }

        Log.d(TAG, "✅ Query embedding: ${queryVector.size} dimensions")

        val scored = memories.mapNotNull { memory ->
            if (memory.embedding.isBlank()) return@mapNotNull null

            // ✅ التحقق من توافق الأبعاد قبل الحساب
            if (memory.embeddingDimensions > 0 &&
                memory.embeddingDimensions != EmbeddingService.CURRENT_DIMENSIONS
            ) {
                Log.w(
                    TAG,
                    "⚠️ Dimension mismatch: memory=${memory.embeddingDimensions}, " +
                    "current=${EmbeddingService.CURRENT_DIMENSIONS} → skipping"
                )
                return@mapNotNull null
            }

            val memoryVector = embeddingService.stringToVector(memory.embedding)
            if (memoryVector.isEmpty()) return@mapNotNull null

            val similarity = embeddingService.cosineSimilarity(queryVector, memoryVector)

            Log.d(
                TAG,
                "📊 Similarity: ${String.format("%.3f", similarity)} " +
                "- ${memory.content.take(50)}..."
            )

            if (similarity >= SIMILARITY_THRESHOLD) {
                Pair(memory, similarity)
            } else {
                null
            }
        }

        val semanticResults = scored
            .sortedByDescending { it.second }
            .take(3)
            .map { it.first }

        Log.d(TAG, "✅ Semantic search found: ${semanticResults.size} memories")

        // ✅ إذا لم يجد نتائج دلالية: استخدم Fallback
        return if (semanticResults.isEmpty()) {
            Log.w(TAG, "⚠️ No semantic results, falling back to keyword search")
            fallbackSearch(query, memories)
        } else {
            semanticResults
        }
    }

    // ============================================================
    // Fallback: البحث التقليدي بالكلمات
    // ============================================================

    private fun fallbackSearch(
        query: String,
        memories: List<MemoryItem>
    ): List<MemoryItem> {
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
    // باقي الدوال
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
