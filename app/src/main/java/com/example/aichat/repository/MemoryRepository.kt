package com.example.aichat.repository

import android.content.Context
import android.util.Log
import com.example.aichat.data.local.AiSettings
import com.example.aichat.data.local.MemoryDao
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

        private const val SIMILARITY_THRESHOLD = 0.45

        // عدد النتائج النهائية التي يرسلها البحث إلى المرحلة التالية.
        private const val MAX_RESULTS = 12

        // عدد المرشحين من كل نوع بحث قبل RRF.
        private const val SEMANTIC_RESULTS = 8
        private const val KEYWORD_RESULTS = 8

        // ثابت RRF القياسي.
        private const val RRF_K = 60.0
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

    suspend fun getMemoryById(
        memoryId: Long
    ): MemoryItem? =
        withContext(Dispatchers.IO) {
            memoryDao.getMemoryById(memoryId)
        }

    // ============================================================
    // Hash
    // ============================================================

    fun calculateHash(
        content: String
    ): String {

        val digest = MessageDigest.getInstance("SHA-256")

        val bytes = digest.digest(
            content.trim().toByteArray(Charsets.UTF_8)
        )

        return bytes.joinToString("") {
            "%02x".format(it)
        }
    }

    // ============================================================
    // Embedding
    // ============================================================

    private suspend fun extractEmbedding(
        content: String
    ): List<Float> {

        val apiKey = aiSettings.geminiKey.trim()

        if (apiKey.isBlank()) {
            return emptyList()
        }

        return try {
            embeddingService.getEmbedding(content)
        } catch (e: Exception) {
            Log.w(
                TAG,
                "Embedding failed: ${e.message}"
            )

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

        val existing = memoryDao.getByHash(hash)

        if (existing != null) {

            Log.d(
                TAG,
                "Duplicate memory ignored: id=${existing.id}"
            )

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

    suspend fun updateMemory(
        memory: MemoryItem
    ) = withContext(Dispatchers.IO) {

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

                val embedding = extractEmbedding(
                    memory.content
                )

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

    suspend fun deleteMemory(
        memory: MemoryItem
    ) = withContext(Dispatchers.IO) {
        memoryDao.deleteMemory(memory)
    }

    // ============================================================
    // HYBRID MEMORY SEARCH WITH RRF
    //
    // Semantic Search + Keyword Search
    //              ↓
    //         RRF Ranking
    //              ↓
    //       Remove duplicates
    //              ↓
    //        Top 12 candidates
    // ============================================================

    suspend fun searchSharedMemories(
        query: String
    ): List<MemoryItem> = withContext(Dispatchers.IO) {

        val cleanQuery = query.trim()

        if (cleanQuery.isBlank()) {
            return@withContext emptyList()
        }

        val allMemories = memoryDao.getAllSharedMemories()

        if (allMemories.isEmpty()) {
            return@withContext emptyList()
        }

        val apiKey = aiSettings.geminiKey.trim()

        // --------------------------------------------------------
        // Keyword search
        // --------------------------------------------------------

        val keywordResults = try {

            fallbackSearch(
                query = cleanQuery,
                memories = allMemories
            )

        } catch (e: Exception) {

            Log.w(
                TAG,
                "Keyword search failed: ${e.message}"
            )

            emptyList()
        }

        // --------------------------------------------------------
        // Semantic search
        // --------------------------------------------------------

        val semanticResults =
            if (apiKey.isNotBlank()) {

                try {

                    searchWithEmbeddings(
                        query = cleanQuery,
                        memories = allMemories
                    )

                } catch (e: Exception) {

                    Log.w(
                        TAG,
                        "Semantic search failed: ${e.message}"
                    )

                    emptyList()
                }

            } else {
                emptyList()
            }

        // --------------------------------------------------------
        // RRF
        // --------------------------------------------------------

        val finalResults = mergeWithRrf(
            semanticResults = semanticResults,
            keywordResults = keywordResults
        )

        Log.d(
            TAG,
            "RRF search: " +
                "semantic=${semanticResults.size}, " +
                "keyword=${keywordResults.size}, " +
                "final=${finalResults.size}"
        )

        finalResults
    }

    // ============================================================
    // Reciprocal Rank Fusion
    //
    // RRF(d) =
    //      1 / (K + semanticRank)
    //    + 1 / (K + keywordRank)
    //
    // Memory found highly in both searches gets a stronger score.
    // ============================================================

    private fun mergeWithRrf(
        semanticResults: List<MemoryItem>,
        keywordResults: List<MemoryItem>
    ): List<MemoryItem> {

        if (semanticResults.isEmpty() &&
            keywordResults.isEmpty()
        ) {
            return emptyList()
        }

        val memoriesById = LinkedHashMap<Long, MemoryItem>()

        semanticResults.forEach { memory ->
            memoriesById[memory.id] = memory
        }

        keywordResults.forEach { memory ->
            memoriesById[memory.id] = memory
        }

        val scores = mutableMapOf<Long, Double>()

        semanticResults.forEachIndexed { index, memory ->

            val rank = index + 1

            val score =
                1.0 / (RRF_K + rank)

            scores[memory.id] =
                (scores[memory.id] ?: 0.0) + score
        }

        keywordResults.forEachIndexed { index, memory ->

            val rank = index + 1

            val score =
                1.0 / (RRF_K + rank)

            scores[memory.id] =
                (scores[memory.id] ?: 0.0) + score
        }

        return memoriesById.keys
            .sortedByDescending { id ->
                scores[id] ?: 0.0
            }
            .take(MAX_RESULTS)
            .mapNotNull { id ->
                memoriesById[id]
            }
    }

    // ============================================================
    // Semantic search
    // ============================================================

    private suspend fun searchWithEmbeddings(
        query: String,
        memories: List<MemoryItem>
    ): List<MemoryItem> {

        if (memories.isEmpty()) {
            return emptyList()
        }

        val queryEmbedding = try {

            embeddingService.getEmbedding(
                query
            )

        } catch (e: Exception) {

            Log.w(
                TAG,
                "Query embedding failed: ${e.message}"
            )

            return emptyList()
        }

        if (queryEmbedding.isEmpty()) {
            return emptyList()
        }

        val scored =
            mutableListOf<Pair<MemoryItem, Float>>()

        for (memory in memories) {

            if (memory.embedding.isBlank()) {
                continue
            }

            val memoryEmbedding =
                parseEmbedding(
                    memory.embedding
                )

            if (memoryEmbedding.isEmpty()) {
                continue
            }

            if (memoryEmbedding.size !=
                queryEmbedding.size
            ) {
                continue
            }

            val similarity =
                cosineSimilarity(
                    queryEmbedding,
                    memoryEmbedding
                )

            if (similarity >=
                SIMILARITY_THRESHOLD
            ) {

                scored.add(
                    memory to similarity
                )
            }
        }

        return scored
            .sortedByDescending {
                it.second
            }
            .take(SEMANTIC_RESULTS)
            .map {
                it.first
            }
    }

    // ============================================================
    // Keyword search
    // ============================================================

    private fun fallbackSearch(
        query: String,
        memories: List<MemoryItem>
    ): List<MemoryItem> {

        if (memories.isEmpty()) {
            return emptyList()
        }

        return MemorySearchEngine.search(
            query = query,
            memories = memories,
            limit = KEYWORD_RESULTS,
            minScore = 0.05
        )
    }

    // ============================================================
    // Parse embedding
    // ============================================================

    private fun parseEmbedding(
        value: String
    ): List<Float> {

        if (value.isBlank()) {
            return emptyList()
        }

        return try {

            value
                .split(",")
                .mapNotNull { item ->
                    item.trim().toFloatOrNull()
                }

        } catch (e: Exception) {

            Log.w(
                TAG,
                "Invalid embedding: ${e.message}"
            )

            emptyList()
        }
    }

    // ============================================================
    // Cosine similarity
    // ============================================================

    private fun cosineSimilarity(
        a: List<Float>,
        b: List<Float>
    ): Float {

        if (a.isEmpty() || b.isEmpty()) {
            return 0f
        }

        if (a.size != b.size) {
            return 0f
        }

        var dotProduct = 0.0
        var magnitudeA = 0.0
        var magnitudeB = 0.0

        for (i in a.indices) {

            val valueA = a[i].toDouble()
            val valueB = b[i].toDouble()

            dotProduct += valueA * valueB
            magnitudeA += valueA * valueA
            magnitudeB += valueB * valueB
        }

        if (magnitudeA == 0.0 ||
            magnitudeB == 0.0
        ) {
            return 0f
        }

        val denominator =
            sqrt(magnitudeA) *
                sqrt(magnitudeB)

        return (
            dotProduct / denominator
        ).toFloat()
    }

    // ============================================================
    // Conversation memories
    // ============================================================

    fun getConversationMemories(
        conversationId: Long
    ) = memoryDao.getConversationMemories(
        conversationId
    )

    suspend fun deleteConversationMemories(
        conversationId: Long
    ) = withContext(Dispatchers.IO) {

        memoryDao.deleteConversationMemories(
            conversationId
        )
    }

    // ============================================================
    // Duplicate check
    // ============================================================

    suspend fun existsByHash(
        hash: String
    ): Boolean = withContext(Dispatchers.IO) {

        memoryDao.getByHash(hash) != null
    }
}
