package com.example.aichat.repository

import android.content.Context
import android.util.Log
import com.example.aichat.data.local.MemoryDao
import com.example.aichat.data.local.AiSettings
import com.example.aichat.data.model.MemoryItem
import com.example.aichat.util.EmbeddingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import kotlin.math.sqrt

class MemoryRepository(
    private val memoryDao: MemoryDao,
    context: Context
) {

    companion object {
        private const val TAG = "MemoryRepository"

        // الحد الأدنى للتشابه الدلالي لقبول الذاكرة.
        private const val SIMILARITY_THRESHOLD = 0.45

        // عدد النتائج النهائية التي نعيدها للمرحلة التالية.
        private const val MAX_RESULTS = 12

        // عدد النتائج الدلالية واللفظية التي نأخذها قبل الدمج.
        private const val SEMANTIC_RESULTS = 8
        private const val KEYWORD_RESULTS = 8
    }

    private val aiSettings = AiSettings(context)

    private val embeddingService = EmbeddingService {
        aiSettings.geminiKey
    }

    // ============================================================
    // Basic memory access
    // ============================================================

    suspend fun getAllSharedMemoriesList(): List<MemoryItem> =
        withContext(Dispatchers.IO) {
            memoryDao.getAllSharedMemories()
        }

    suspend fun getSharedMemories(): List<MemoryItem> =
        withContext(Dispatchers.IO) {
            memoryDao.getAllSharedMemories()
        }

    suspend fun getMemoryById(memoryId: Long): MemoryItem? =
        withContext(Dispatchers.IO) {
            memoryDao.getMemoryById(memoryId)
        }

    // ============================================================
    // Hash
    // ============================================================

    fun calculateHash(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(content.trim().toByteArray(Charsets.UTF_8))

        return bytes.joinToString("") {
            "%02x".format(it)
        }
    }

    // ============================================================
    // Embedding
    // ============================================================

    private suspend fun extractEmbedding(content: String): List<Float> {
        val apiKey = aiSettings.geminiKey.trim()

        if (apiKey.isBlank()) {
            return emptyList()
        }

        return try {
            embeddingService.getEmbedding(content)
        } catch (e: Exception) {
            Log.w(TAG, "Embedding failed: ${e.message}")
            emptyList()
        }
    }

    // ============================================================
    // Add memory
    // ============================================================

    suspend fun addMemory(
        content: String,
        sourceConversationId: Long? = null,
        sourceMessageId: Long? = null,
        category: String = "OTHER",
        isShared: Boolean = true
    ): Long = withContext(Dispatchers.IO) {

        val cleanContent = content.trim()

        if (cleanContent.isBlank()) {
            return@withContext -1L
        }

        val hash = calculateHash(cleanContent)

        // منع التكرار
        val existing = memoryDao.getByHash(hash)

        if (existing != null) {
            Log.d(TAG, "Duplicate memory ignored: id=${existing.id}")
            return@withContext existing.id
        }

        val embedding = extractEmbedding(cleanContent)

        val memory = MemoryItem(
            content = cleanContent,
            embedding = embedding.joinToString(","),
            embeddingModel = "text-embedding-004",
            embeddingDimensions = if (embedding.isNotEmpty()) {
                embedding.size
            } else {
                768
            },
            contentHash = hash,
            sourceConversationId = sourceConversationId,
            sourceMessageId = sourceMessageId,
            category = category,
            isShared = isShared
        )

        val id = memoryDao.insertMemory(memory)

        Log.d(
            TAG,
            "Memory added: id=$id, embedding=${embedding.size}"
        )

        id
    }

    // ============================================================
    // Update memory
    // ============================================================

    suspend fun updateMemory(memory: MemoryItem): Unit =
        withContext(Dispatchers.IO) {

            val cleanContent = memory.content.trim()

            if (cleanContent.isBlank()) {
                return@withContext
            }

            val hash = calculateHash(cleanContent)
            val embedding = extractEmbedding(cleanContent)

            val updated = memory.copy(
                content = cleanContent,
                embedding = embedding.joinToString(","),
                embeddingModel = "text-embedding-004",
                embeddingDimensions = if (embedding.isNotEmpty()) {
                    embedding.size
                } else {
                    768
                },
                contentHash = hash,
                updatedAt = System.currentTimeMillis()
            )

            memoryDao.updateMemory(updated)
        }

    // ============================================================
    // Backfill embeddings
    // ============================================================

    suspend fun backfillEmbeddings(): Int =
        withContext(Dispatchers.IO) {

            val memories = memoryDao.getAllSharedMemories()

            var updatedCount = 0

            for (memory in memories) {

                if (memory.embedding.isNotBlank()) {
                    continue
                }

                val embedding = extractEmbedding(memory.content)

                if (embedding.isEmpty()) {
                    continue
                }

                val updated = memory.copy(
                    embedding = embedding.joinToString(","),
                    embeddingModel = "text-embedding-004",
                    embeddingDimensions = embedding.size,
                    updatedAt = System.currentTimeMillis()
                )

                memoryDao.updateMemory(updated)

                updatedCount++
            }

            Log.d(
                TAG,
                "Backfill completed: updated=$updatedCount"
            )

            updatedCount
        }

    // ============================================================
    // Add memory with an already generated embedding
    // ============================================================

    suspend fun addMemoryWithEmbedding(
        content: String,
        embedding: List<Float>,
        category: String = "OTHER",
        isShared: Boolean = true,
        sourceConversationId: Long? = null,
        sourceMessageId: Long? = null
    ): Long = withContext(Dispatchers.IO) {

        val cleanContent = content.trim()

        if (cleanContent.isBlank()) {
            return@withContext -1L
        }

        val hash = calculateHash(cleanContent)

        val existing = memoryDao.getByHash(hash)

        if (existing != null) {
            Log.d(
                TAG,
                "Duplicate memory ignored: id=${existing.id}"
            )

            return@withContext existing.id
        }

        val memory = MemoryItem(
            content = cleanContent,
            embedding = embedding.joinToString(","),
            embeddingModel = "text-embedding-004",
            embeddingDimensions = if (embedding.isNotEmpty()) {
                embedding.size
            } else {
                768
            },
            contentHash = hash,
            sourceConversationId = sourceConversationId,
            sourceMessageId = sourceMessageId,
            category = category,
            isShared = isShared
        )

        memoryDao.insertMemory(memory)
    }

    // ============================================================
    // Delete
    // ============================================================

    suspend fun deleteMemory(memory: MemoryItem) =
        withContext(Dispatchers.IO) {
            memoryDao.deleteMemory(memory)
        }

    // ============================================================
    // TRUE HYBRID MEMORY SEARCH
    //
    // 1. Semantic search
    // 2. Keyword search
    // 3. Merge both result sets
    // 4. Remove duplicates
    // 5. Prefer semantic matches when both methods find the same item
    // 6. Return a wider candidate set for the curator
    // ============================================================

    suspend fun searchSharedMemories(
        query: String
    ): List<MemoryItem> = withContext(Dispatchers.IO) {

       
