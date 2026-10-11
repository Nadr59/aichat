package com.example.aichat.repository

import android.content.Context
import android.util.Log
import com.example.aichat.data.local.AiSettings
import com.example.aichat.data.local.MemoryDao
import com.example.aichat.data.model.MemoryItem
import kotlinx.coroutines.flow.Flow
import java.security.MessageDigest
import kotlin.math.min

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

        // ----------------------------
        // ✅ Chunking (Fixed-length)
        // ----------------------------
        private const val CHUNKING_ENABLED = true

        // حجم القطعة (حروف). خليه < 1200 لتفادي prompts طويلة لاحقاً.
        private const val CHUNK_SIZE = 900

        // تداخل بين القطع لتجنب ضياع سياق عند القطع.
        private const val CHUNK_OVERLAP = 140

        // أقل طول مسموح للقطعة بعد التقطيع (لتجنب قطع صغيرة جداً).
        private const val MIN_CHUNK_LEN = 220

        // نافذة بحث عن "حد قطع طبيعي" قبل نهاية القطعة
        private const val BREAK_LOOKBACK_WINDOW = 120
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

    private fun calculateHash(content: String): String {
        return try {
            MessageDigest.getInstance("SHA-256")
                .digest(content.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Hash calculation failed: ${e.message}")
            ""
        }
    }

    // ============================================================
    // استخراج Embedding
    // ============================================================

    private suspend fun extractEmbedding(content: String): String {
        val apiKey = aiSettings.geminiKey

        if (apiKey.isBlank()) {
            Log.w(TAG, "Skipping embedding - Gemini API key not configured")
            return ""
        }

        return try {
            val vector = embeddingService.getEmbedding(content)

            if (vector.isNotEmpty()) {
                val compressed =
                    EmbeddingService.vectorToString(vector, compress = true)

                Log.d(TAG, "Embedding extracted: ${vector.size} dimensions")
                compressed
            } else {
                Log.w(TAG, "Empty embedding returned")
                ""
            }

        } catch (e: Exception) {
            Log.e(TAG, "Embedding extraction failed: ${e.message}")
            ""
        }
    }

    // ============================================================
    // ✅ Chunking helpers
    // ============================================================

    private fun normalizeForStorage(text: String): String {
        // تنظيف بسيط بدون تغيير المعنى:
        // - trim
        // - إزالة null chars
        return text.replace("\u0000", "").trim()
    }

    /**
     * تقسيم بطول ثابت مع overlap + محاولة القطع على حدود طبيعية.
     * ثابت/Deterministic حتى يساعد dedup بالـ hash.
     */
    private fun splitIntoChunksFixed(text: String): List<String> {
        val t = normalizeForStorage(text)
        if (t.isBlank()) return emptyList()
        if (!CHUNKING_ENABLED) return listOf(t)
        if (t.length <= CHUNK_SIZE) return listOf(t)

        val chunks = mutableListOf<String>()
        val n = t.length

        var start = 0
        while (start < n) {
            val targetEnd = min(start + CHUNK_SIZE, n)
            var end = targetEnd

            // حاول إيجاد نقطة قطع طبيعية قبل النهاية
            val minEnd = (start + MIN_CHUNK_LEN).coerceAtMost(n)
            val lookbackStart = (targetEnd - BREAK_LOOKBACK_WINDOW).coerceAtLeast(minEnd)

            if (lookbackStart < targetEnd) {
                val slice = t.substring(lookbackStart, targetEnd)
                val cut = findBestCutIndex(slice)
                if (cut >= 0) {
                    end = lookbackStart + cut
                }
            }

            if (end <= start) end = targetEnd

            val chunk = normalizeForStorage(t.substring(start, end))
            if (chunk.isNotBlank()) {
                chunks.add(chunk)
            }

            if (end >= n) break

            // overlap
            val nextStart = (end - CHUNK_OVERLAP).coerceAtLeast(start + 1)
            start = nextStart
        }

        // دمج القطعة الأخيرة إن كانت قصيرة جداً
        if (chunks.size >= 2 && chunks.last().length < (MIN_CHUNK_LEN / 2)) {
            val last = chunks.removeAt(chunks.lastIndex)
            chunks[chunks.lastIndex] = normalizeForStorage(chunks.last() + "\n" + last)
        }

        return chunks
    }

    /**
     * يرجّع index داخل slice حيث نقطع.
     * نفضّل: "\n\n" ثم "\n" ثم ". " ثم "،" ثم "," ثم " " ثم أي محرف.
     */
    private fun findBestCutIndex(slice: String): Int {
        if (slice.isBlank()) return -1

        val priorities = listOf(
            "\n\n",
            "\n",
            ". ",
            "؟",
            "!",
            "،",
            ",",
            " "
        )

        for (token in priorities) {
            val idx = slice.lastIndexOf(token)
            if (idx > 0) {
                // نقطع بعد التوكن كي لا نخسر الفاصل
                return idx + token.length
            }
        }

        // لا يوجد حد طبيعي
        return -1
    }

    // ============================================================
    // إضافة ذاكرة (يدعم chunking)
    // ============================================================

    suspend fun addMemory(
        content: String,
        sourceConversationId: Long? = null,
        sourceMessageId: Long? = null,
        category: String = "OTHER",
        isShared: Boolean = true
    ): Long {

        val cleanContent = normalizeForStorage(content)

        if (cleanContent.isBlank()) {
            Log.w(TAG, "Skipping empty content")
            return 0
        }

        // ✅ إذا النص طويل -> chunking
        val chunks = splitIntoChunksFixed(cleanContent)

        if (chunks.size == 1) {
            // المسار القديم (قطعة واحدة)
            return insertSingleMemoryIfNotDuplicate(
                cleanContent = chunks.first(),
                sourceConversationId = sourceConversationId,
                sourceMessageId = sourceMessageId,
                category = category,
                isShared = isShared
            )
        }

        // إدخال عدة قطع
        Log.d(TAG, "Chunking content: len=${cleanContent.length} -> chunks=${chunks.size}")

        val ids = mutableListOf<Long>()
        for (chunk in chunks) {
            val id = insertSingleMemoryIfNotDuplicate(
                cleanContent = chunk,
                sourceConversationId = sourceConversationId,
                sourceMessageId = sourceMessageId,
                category = category,
                isShared = isShared
            )
            if (id > 0) ids.add(id)
        }

        val firstId = ids.firstOrNull() ?: 0L
        Log.d(TAG, "Chunked memory saved: count=${ids.size} firstId=$firstId")
        return firstId
    }

    private suspend fun insertSingleMemoryIfNotDuplicate(
        cleanContent: String,
        sourceConversationId: Long?,
        sourceMessageId: Long?,
        category: String,
        isShared: Boolean
    ): Long {
        val hash = calculateHash(cleanContent)

        if (hash.isNotBlank()) {
            val existing = memoryDao.getByHash(hash)
            if (existing != null) {
                Log.d(TAG, "Duplicate content detected (id=${existing.id}), skipping")
                return existing.id
            }
        }

        val embedding = extractEmbedding(cleanContent)

        val embeddingModel =
            if (embedding.isNotBlank()) EmbeddingService.CURRENT_MODEL else ""

        val embeddingDimensions =
            if (embedding.isNotBlank()) EmbeddingService.CURRENT_DIMENSIONS else 0

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
        Log.d(TAG, "Memory saved with id=$id len=${cleanContent.length}")
        return id
    }

    // ============================================================
    // تعديل ذاكرة
    // ============================================================

    suspend fun updateMemory(memory: MemoryItem) {

        val cleanContent = normalizeForStorage(memory.content)

        val newEmbedding = extractEmbedding(cleanContent)
        val newHash = calculateHash(cleanContent)

        val embeddingModel =
            if (newEmbedding.isNotBlank()) EmbeddingService.CURRENT_MODEL else ""

        val embeddingDimensions =
            if (newEmbedding.isNotBlank()) EmbeddingService.CURRENT_DIMENSIONS else 0

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

        Log.d(TAG, "Memory updated id=${memory.id}")
    }

    // ============================================================
    // Backfill Embeddings
    // ============================================================

    suspend fun backfillEmbeddings(): Int {

        if (aiSettings.geminiKey.isBlank()) {
            return 0
        }

        val allMemories = memoryDao.getAllSharedMemories()

        // إصلاح Hash للذكريات القديمة.
        allMemories
            .filter { it.contentHash.isBlank() }
            .forEach { memory ->
                val hash = calculateHash(memory.content)
                if (hash.isNotBlank()) {
                    memoryDao.updateMemory(
                        memory.copy(contentHash = hash)
                    )
                }
            }

        val missing = allMemories.filter { it.embedding.isBlank() }

        if (missing.isEmpty()) {
            Log.d(TAG, "Backfill: all memories already have embeddings")
            return 0
        }

        Log.d(TAG, "Backfill: processing ${missing.size} memories...")

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

        Log.d(TAG, "Backfill complete: $updated/${missing.size} updated")
        return updated
    }

    // ============================================================
    // التحقق من Hash
    // ============================================================

    suspend fun existsByHash(hash: String): Boolean {
        if (hash.isBlank()) return false
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

        val cleanContent = normalizeForStorage(content)
        if (cleanContent.isBlank()) return 0

        // ✅ إذا المحتوى طويل لا نستطيع استخدام embedding "واحد" له.
        // نرجع لمسار addMemory الذي سيُقسم ويحسب embeddings لكل قطعة.
        if (CHUNKING_ENABLED && cleanContent.length > CHUNK_SIZE) {
            Log.w(TAG, "addMemoryWithEmbedding: content too long for a single embedding -> falling back to addMemory() with chunking")
            return addMemory(
                content = cleanContent,
                category = category,
                isShared = isShared,
                sourceConversationId = sourceConversationId,
                sourceMessageId = sourceMessageId
            )
        }

        val hash = calculateHash(cleanContent)
        if (hash.isNotBlank()) {
            val existing = memoryDao.getByHash(hash)
            if (existing != null) {
                Log.d(TAG, "Duplicate (id=${existing.id}), skipping")
                return existing.id
            }
        }

        val embeddingStr =
            if (embedding.isNotEmpty()) {
                try {
                    EmbeddingService.vectorToString(embedding, compress = true)
                } catch (e: Exception) {
                    Log.e(TAG, "Embedding compression failed: ${e.message}")
                    ""
                }
            } else {
                ""
            }

        val embeddingModel =
            if (embeddingStr.isNotBlank()) EmbeddingService.CURRENT_MODEL else ""

        val embeddingDims =
            if (embeddingStr.isNotBlank()) EmbeddingService.CURRENT_DIMENSIONS else 0

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

        val id = memoryDao.insertMemory(memory)

        Log.d(TAG, "Memory saved with embedding (id=$id)")
        return id
    }

    // ============================================================
    // حذف ذاكرة
    // ============================================================

    suspend fun deleteMemory(memory: MemoryItem) {
        memoryDao.deleteMemory(memory)
        Log.d(TAG, "Memory deleted id=${memory.id}")
    }

    // ============================================================
    // Flow للذكريات المشتركة
    // ============================================================

    fun getSharedMemories(): Flow<List<MemoryItem>> {
        return memoryDao.getSharedMemories()
    }

    // ============================================================
    // البحث الهجين
    // ============================================================

    suspend fun searchSharedMemories(query: String): List<MemoryItem> {

        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) {
            Log.d(TAG, "Empty query")
            return emptyList()
        }

        val allMemories = memoryDao.getAllSharedMemories()
        if (allMemories.isEmpty()) {
            Log.d(TAG, "No memories in database")
            return emptyList()
        }

        Log.d(TAG, "Total memories: ${allMemories.size}")

        val withEmbeddings = allMemories.count { it.embedding.isNotBlank() }
        Log.d(TAG, "Memories with embeddings: $withEmbeddings/${allMemories.size}")

        val hasEmbeddings = withEmbeddings > 0
        val hasApiKey = aiSettings.geminiKey.isNotBlank()

        // Keyword search دائماً
        val keywordResults = fallbackSearch(cleanQuery, allMemories)

        // Semantic search إذا كان متاحاً
        val semanticResults =
            if (hasEmbeddings && hasApiKey) {
                searchWithEmbeddings(cleanQuery, allMemories)
            } else {
                emptyList()
            }

        // إذا لم يتوفر Semantic نستخدم Keyword فقط.
        if (semanticResults.isEmpty()) {
            Log.d(TAG, "No semantic results, using keyword results")
            return keywordResults.take(MAX_RESULTS)
        }

        val finalResults =
            mergeWithRrf(
                semanticResults = semanticResults,
                keywordResults = keywordResults
            )

        Log.d(
            TAG,
            "RRF search completed: semantic=${semanticResults.size}, keyword=${keywordResults.size}, final=${finalResults.size}"
        )

        return finalResults
    }

    private fun mergeWithRrf(
        semanticResults: List<MemoryItem>,
        keywordResults: List<MemoryItem>
    ): List<MemoryItem> {

        if (semanticResults.isEmpty() && keywordResults.isEmpty()) {
            return emptyList()
        }

        val memoriesById = LinkedHashMap<Long, MemoryItem>()

        semanticResults.forEach { memory -> memoriesById[memory.id] = memory }
        keywordResults.forEach { memory -> memoriesById[memory.id] = memory }

        val scores = mutableMapOf<Long, Double>()

        // Semantic ranks
        semanticResults.forEachIndexed { index, memory ->
            val rank = index + 1
            val score = 1.0 / (RRF_K + rank)
            scores[memory.id] = (scores[memory.id] ?: 0.0) + score
        }

        // Keyword ranks
        keywordResults.forEachIndexed { index, memory ->
            val rank = index + 1
            val score = 1.0 / (RRF_K + rank)
            scores[memory.id] = (scores[memory.id] ?: 0.0) + score
        }

        return memoriesById.keys
            .sortedByDescending { id -> scores[id] ?: 0.0 }
            .take(MAX_RESULTS)
            .mapNotNull { id -> memoriesById[id] }
    }

    private suspend fun searchWithEmbeddings(
        query: String,
        memories: List<MemoryItem>
    ): List<MemoryItem> {

        val queryVector =
            try {
                Log.d(TAG, "Getting query embedding for: $query")
                embeddingService.getEmbedding(query)
            } catch (e: Exception) {
                Log.e(TAG, "Query embedding failed: ${e.message}")
                return emptyList()
            }

        if (queryVector.isEmpty()) {
            Log.w(TAG, "Empty query vector")
            return emptyList()
        }

        Log.d(TAG, "Query embedding: ${queryVector.size} dimensions")

        val scored =
            memories.mapNotNull { memory ->
                if (memory.embedding.isBlank()) return@mapNotNull null

                if (memory.embeddingDimensions > 0 &&
                    memory.embeddingDimensions != EmbeddingService.CURRENT_DIMENSIONS
                ) {
                    Log.w(
                        TAG,
                        "Dimension mismatch: memory=${memory.embeddingDimensions}, current=${EmbeddingService.CURRENT_DIMENSIONS}"
                    )
                    return@mapNotNull null
                }

                val memoryVector = EmbeddingService.stringToVector(memory.embedding)
                if (memoryVector.isEmpty()) return@mapNotNull null

                val similarity =
                    EmbeddingService.cosineSimilarity(queryVector, memoryVector)

                if (similarity >= SIMILARITY_THRESHOLD) Pair(memory, similarity) else null
            }

        val semanticResults =
            scored
                .sortedByDescending { it.second }
                .take(SEMANTIC_RESULTS)
                .map { it.first }

        Log.d(TAG, "Semantic search found: ${semanticResults.size} memories")
        return semanticResults
    }

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

        Log.d(TAG, "Keyword search found: ${result.memories.size} memories")
        return result.memories
    }

    // ============================================================
    // ذكريات المحادثة
    // ============================================================

    fun getConversationMemories(conversationId: Long): Flow<List<MemoryItem>> {
        return memoryDao.getConversationMemories(conversationId)
    }

    // ============================================================
    // الحصول على ذاكرة واحدة
    // ============================================================

    suspend fun getMemoryById(memoryId: Long): MemoryItem? {
        return memoryDao.getMemoryById(memoryId)
    }

    // ============================================================
    // حذف ذكريات المحادثة
    // ============================================================

    suspend fun deleteConversationMemories(conversationId: Long) {
        memoryDao.deleteConversationMemories(conversationId)
    }
}
