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

        // عدد النتائج النهائية بعد دمج البحثين.
        private const val MAX_RESULTS = 12

        // عدد النتائج الأولية من كل محرك بحث.
        private const val SEMANTIC_RESULTS = 8
        private const val KEYWORD_RESULTS = 8

        // ثابت Reciprocal Rank Fusion.
        private const val RRF_K = 60.0
    }

    private val aiSettings = AiSettings(context)

    private val embeddingService =
        EmbeddingService { aiSettings.geminiKey }

    // ============================================================
    // جميع الذكريات المشتركة
    // ============================================================

    suspend fun getAllSharedMemoriesList(): List<MemoryItem> {
        return memoryDao.getAllSharedMemories()
    }

    // ============================================================
    // Hash
    // ============================================================

    private fun calculateHash(
        content: String
    ): String {

        return try {

            MessageDigest.getInstance("SHA-256")
                .digest(
                    content.toByteArray(Charsets.UTF_8)
                )
                .joinToString("") {
                    "%02x".format(it)
                }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Hash calculation failed: ${e.message}"
            )

            ""
        }
    }

    // ============================================================
    // استخراج Embedding
    // ============================================================

    private suspend fun extractEmbedding(
        content: String
    ): String {

        val apiKey = aiSettings.geminiKey

        if (apiKey.isBlank()) {

            Log.w(
                TAG,
                "Skipping embedding - Gemini API key not configured"
            )

            return ""
        }

        return try {

            val vector =
                embeddingService.getEmbedding(content)

            if (vector.isNotEmpty()) {

                val compressed =
                    EmbeddingService.vectorToString(
                        vector,
                        compress = true
                    )

                Log.d(
                    TAG,
                    "Embedding extracted: ${vector.size} dimensions"
                )

                compressed

            } else {

                Log.w(
                    TAG,
                    "Empty embedding returned"
                )

                ""
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Embedding extraction failed: ${e.message}"
            )

            ""
        }
    }

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

        val cleanContent = content.trim()

        if (cleanContent.isBlank()) {

            Log.w(
                TAG,
                "Skipping empty content"
            )

            return 0
        }

        val hash = calculateHash(cleanContent)

        if (hash.isNotBlank()) {

            val existing =
                memoryDao.getByHash(hash)

            if (existing != null) {

                Log.d(
                    TAG,
                    "Duplicate content detected " +
                        "(id=${existing.id}), skipping"
                )

                return existing.id
            }
        }

        val embedding =
            extractEmbedding(cleanContent)

        val embeddingModel =
            if (embedding.isNotBlank()) {
                EmbeddingService.CURRENT_MODEL
            } else {
                ""
            }

        val embeddingDimensions =
            if (embedding.isNotBlank()) {
                EmbeddingService.CURRENT_DIMENSIONS
            } else {
                0
            }

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

        val id =
            memoryDao.insertMemory(memory)

        Log.d(
            TAG,
            "Memory saved with id=$id"
        )

        return id
    }

    // ============================================================
    // تعديل ذاكرة
    // ============================================================

    suspend fun updateMemory(
        memory: MemoryItem
    ) {

        val cleanContent =
            memory.content.trim()

        val newEmbedding =
            extractEmbedding(cleanContent)

        val newHash =
            calculateHash(cleanContent)

        val embeddingModel =
            if (newEmbedding.isNotBlank()) {
                EmbeddingService.CURRENT_MODEL
            } else {
                ""
            }

        val embeddingDimensions =
            if (newEmbedding.isNotBlank()) {
                EmbeddingService.CURRENT_DIMENSIONS
            } else {
                0
            }

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

        Log.d(
            TAG,
            "Memory updated id=${memory.id}"
        )
    }

    // ============================================================
    // Backfill Embeddings
    // ============================================================

    suspend fun backfillEmbeddings(): Int {

        if (aiSettings.geminiKey.isBlank()) {
            return 0
        }

        val allMemories =
            memoryDao.getAllSharedMemories()

        // إصلاح Hash للذكريات القديمة.
        allMemories
            .filter {
                it.contentHash.isBlank()
            }
            .forEach { memory ->

                val hash =
                    calculateHash(memory.content)

                if (hash.isNotBlank()) {

                    memoryDao.updateMemory(
                        memory.copy(
                            contentHash = hash
                        )
                    )
                }
            }

        val missing =
            allMemories.filter {
                it.embedding.isBlank()
            }

        if (missing.isEmpty()) {

            Log.d(
                TAG,
                "Backfill: all memories already have embeddings"
            )

            return 0
        }

        Log.d(
            TAG,
            "Backfill: processing ${missing.size} memories..."
        )

        var updated = 0

        missing.forEach { memory ->

            val embedding =
                extractEmbedding(memory.content)

            if (embedding.isNotBlank()) {

                memoryDao.updateMemory(
                    memory.copy(
                        embedding = embedding,
                        embeddingModel =
                            EmbeddingService.CURRENT_MODEL,
                        embeddingDimensions =
                            EmbeddingService.CURRENT_DIMENSIONS
                    )
                )

                updated++
            }
        }

        Log.d(
            TAG,
            "Backfill complete: " +
                "$updated/${missing.size} updated"
        )

        return updated
    }

    // ============================================================
    // التحقق من Hash
    // ============================================================

    suspend fun existsByHash(
        hash: String
    ): Boolean {

        if (hash.isBlank()) {
            return false
        }

        return memoryDao.getByHash(hash) != null
    }

    // ============================================================
    // حفظ ذاكرة مع Embedding جاهز
    // ============================================================

    suspend fun addMemoryWithEmbedding(
        content: String,
        embedding: List<Double>,
        category: String = "OTHER",
        isShared: Boolean = true,
        sourceConversationId: Long? = null,
        sourceMessageId: Long? = null
    ): Long {

        val cleanContent =
            content.trim()

        if (cleanContent.isBlank()) {
            return 0
        }

        val hash =
            calculateHash(cleanContent)

        if (hash.isNotBlank()) {

            val existing =
                memoryDao.getByHash(hash)

            if (existing != null) {

                Log.d(
                    TAG,
                    "Duplicate (id=${existing.id}), skipping"
                )

                return existing.id
            }
        }

        val embeddingStr =
            if (embedding.isNotEmpty()) {

                try {

                    EmbeddingService.vectorToString(
                        embedding,
                        compress = true
                    )

                } catch (e: Exception) {

                    Log.e(
                        TAG,
                        "Embedding compression failed: " +
                            e.message
                    )

                    ""
                }

            } else {
                ""
            }

        val embeddingModel =
            if (embeddingStr.isNotBlank()) {
                EmbeddingService.CURRENT_MODEL
            } else {
                ""
            }

        val embeddingDims =
            if (embeddingStr.isNotBlank()) {
                EmbeddingService.CURRENT_DIMENSIONS
            } else {
                0
            }

        val memory = MemoryItem(
            content = cleanContent,
            embedding = embeddingStr,
            embeddingModel = embeddingModel,
            embeddingDimensions = embeddingDims,
            contentHash = hash,
            sourceConversationId = sourceConversationId,
            sourceMessageId = sourceMessageId,
            category = category,
            isShared = isShared
        )

        val id =
            memoryDao.insertMemory(memory)

        Log.d(
            TAG,
            "Memory saved with embedding (id=$id)"
        )

        return id
    }

    // ============================================================
    // حذف ذاكرة
    // ============================================================

    suspend fun deleteMemory(
        memory: MemoryItem
    ) {

        memoryDao.deleteMemory(memory)

        Log.d(
            TAG,
            "Memory deleted id=${memory.id}"
        )
    }

    // ============================================================
    // Flow للذكريات المشتركة
    //
    // مهم: يجب أن يبقى Flow لأن ChatViewModel يستخدم stateIn().
    // ============================================================

    fun getSharedMemories():
        Flow<List<MemoryItem>> {

        return memoryDao.getSharedMemories()
    }

    // ============================================================
    // البحث الهجين
    //
    // Semantic + Keyword
    //          ↓
    //         RRF
    //          ↓
    //      Top 12
    // ============================================================

    suspend fun searchSharedMemories(
        query: String
    ): List<MemoryItem> {

        val cleanQuery =
            query.trim()

        if (cleanQuery.isBlank()) {

            Log.d(
                TAG,
                "Empty query"
            )

            return emptyList()
        }

        val allMemories =
            memoryDao.getAllSharedMemories()

        if (allMemories.isEmpty()) {

            Log.d(
                TAG,
                "No memories in database"
            )

            return emptyList()
        }

        Log.d(
            TAG,
            "Total memories: ${allMemories.size}"
        )

        val withEmbeddings =
            allMemories.count {
                it.embedding.isNotBlank()
            }

        Log.d(
            TAG,
            "Memories with embeddings: " +
                "$withEmbeddings/${allMemories.size}"
        )

        val hasEmbeddings =
            withEmbeddings > 0

        val hasApiKey =
            aiSettings.geminiKey.isNotBlank()

        // --------------------------------------------------------
        // Keyword search دائماً
        // --------------------------------------------------------

        val keywordResults =
            fallbackSearch(
                cleanQuery,
                allMemories
            )

        // --------------------------------------------------------
        // Semantic search إذا كان متاحاً
        // --------------------------------------------------------

        val semanticResults =
            if (hasEmbeddings && hasApiKey) {

                searchWithEmbeddings(
                    cleanQuery,
                    allMemories
                )

            } else {

                emptyList()
            }

        // --------------------------------------------------------
        // إذا لم يتوفر Semantic نستخدم Keyword فقط.
        // --------------------------------------------------------

        if (semanticResults.isEmpty()) {

            Log.d(
                TAG,
                "No semantic results, " +
                    "using keyword results"
            )

            return keywordResults
                .take(MAX_RESULTS)
        }

        // --------------------------------------------------------
        // RRF
        // --------------------------------------------------------

        val finalResults =
            mergeWithRrf(
                semanticResults = semanticResults,
                keywordResults = keywordResults
            )

        Log.d(
            TAG,
            "RRF search completed: " +
                "semantic=${semanticResults.size}, " +
                "keyword=${keywordResults.size}, " +
                "final=${finalResults.size}"
        )

        return finalResults
    }

    // ============================================================
    // RRF - Reciprocal Rank Fusion
    //
    // score =
    // 1 / (K + semanticRank)
    // +
    // 1 / (K + keywordRank)
    //
    // الذاكرة التي تظهر في البحثين تحصل على مجموع الدرجتين.
    // ============================================================

    private fun mergeWithRrf(
        semanticResults: List<MemoryItem>,
        keywordResults: List<MemoryItem>
    ): List<MemoryItem> {

        if (
            semanticResults.isEmpty() &&
            keywordResults.isEmpty()
        ) {
            return emptyList()
        }

        val memoriesById =
            LinkedHashMap<Long, MemoryItem>()

        semanticResults.forEach { memory ->

            memoriesById[memory.id] =
                memory
        }

        keywordResults.forEach { memory ->

            memoriesById[memory.id] =
                memory
        }

        val scores =
            mutableMapOf<Long, Double>()

        // --------------------------------------------------------
        // Semantic ranks
        // --------------------------------------------------------

        semanticResults.forEachIndexed {
            index,
            memory
            ->

            val rank =
                index + 1

            val score =
                1.0 /
                    (RRF_K + rank)

            scores[memory.id] =
                (scores[memory.id] ?: 0.0) +
                    score
        }

        // --------------------------------------------------------
        // Keyword ranks
        // --------------------------------------------------------

        keywordResults.forEachIndexed {
            index,
            memory
            ->

            val rank =
                index + 1

            val score =
                1.0 /
                    (RRF_K + rank)

            scores[memory.id] =
                (scores[memory.id] ?: 0.0) +
                    score
        }

        // --------------------------------------------------------
        // ترتيب نهائي
        // --------------------------------------------------------

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
    // البحث الدلالي
    // ============================================================

    private suspend fun searchWithEmbeddings(
        query: String,
        memories: List<MemoryItem>
    ): List<MemoryItem> {

        val queryVector =
            try {

                Log.d(
                    TAG,
                    "Getting query embedding for: $query"
                )

                embeddingService.getEmbedding(
                    query
                )

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Query embedding failed: ${e.message}"
                )

                return emptyList()
            }

        if (queryVector.isEmpty()) {

            Log.w(
                TAG,
                "Empty query vector"
            )

            return emptyList()
        }

        Log.d(
            TAG,
            "Query embedding: " +
                "${queryVector.size} dimensions"
        )

        val scored =
            memories.mapNotNull { memory ->

                if (memory.embedding.isBlank()) {
                    return@mapNotNull null
                }

                if (
                    memory.embeddingDimensions > 0 &&
                    memory.embeddingDimensions !=
                        EmbeddingService.CURRENT_DIMENSIONS
                ) {

                    Log.w(
                        TAG,
                        "Dimension mismatch: " +
                            "memory=${memory.embeddingDimensions}, " +
                            "current=" +
                            "${EmbeddingService.CURRENT_DIMENSIONS}"
                    )

                    return@mapNotNull null
                }

                val memoryVector =
                    EmbeddingService.stringToVector(
                        memory.embedding
                    )

                if (memoryVector.isEmpty()) {
                    return@mapNotNull null
                }

                val similarity =
                    EmbeddingService.cosineSimilarity(
                        queryVector,
                        memoryVector
                    )

                if (
                    similarity >=
                        SIMILARITY_THRESHOLD
                ) {

                    Pair(
                        memory,
                        similarity
                    )

                } else {

                    null
                }
            }

        val semanticResults =
            scored
                .sortedByDescending {
                    it.second
                }
                .take(SEMANTIC_RESULTS)
                .map {
                    it.first
                }

        Log.d(
            TAG,
            "Semantic search found: " +
                "${semanticResults.size} memories"
        )

        return semanticResults
    }

    // ============================================================
    // البحث بالكلمات
    // ============================================================

    private fun fallbackSearch(
        query: String,
        memories: List<MemoryItem>
    ): List<MemoryItem> {

        val result =
            MemorySearchEngine.search(
                query = query,
                memories = memories,
                limit = KEYWORD_RESULTS,
                minScore = 0.05
            )

        Log.d(
            TAG,
            "Keyword search found: " +
                "${result.memories.size} memories"
        )

        return result.memories
    }

    // ============================================================
    // ذكريات المحادثة
    // ============================================================

    fun getConversationMemories(
        conversationId: Long
    ): Flow<List<MemoryItem>> {

        return memoryDao.getConversationMemories(
            conversationId
        )
    }

    // ============================================================
    // الحصول على ذاكرة واحدة
    // ============================================================

    suspend fun getMemoryById(
        memoryId: Long
    ): MemoryItem? {

        return memoryDao.getMemoryById(
            memoryId
        )
    }

    // ============================================================
    // حذف ذكريات المحادثة
    // ============================================================

    suspend fun deleteConversationMemories(
        conversationId: Long
    ) {

        memoryDao.deleteConversationMemories(
            conversationId
        )
    }
}
