package com.example.aichat.repository

import com.example.aichat.data.model.MemoryItem

object MemorySearchEngine {

    private val ARABIC_STOP_WORDS = setOf(
        "في", "من", "إلى", "على", "عن", "هو", "هي", "أن", "إن",
        "التي", "الذي", "ما", "لا", "نعم", "هل", "كيف", "لماذا",
        "أين", "متى", "ماذا", "أي", "كل", "بعض", "هذا", "هذه",
        "ذلك", "تلك", "مع", "أو", "لكن", "لأن", "حتى", "إذا",
        "عند", "بعد", "قبل", "فوق", "تحت", "بين", "خلال", "ضد",
        "كان", "يكون", "تكون"
    )

    private val ENGLISH_STOP_WORDS = setOf(
        "the", "is", "are", "was", "were", "a", "an", "in", "on",
        "at", "to", "for", "of", "with", "by", "from", "up", "about",
        "into", "through", "during", "what", "how", "where", "when",
        "why", "which", "who", "this", "that", "these", "those",
        "i", "you", "he", "she", "it", "we", "they", "my", "your",
        "can", "could", "will", "would", "should", "do", "does", "did"
    )

    private fun normalizeText(text: String): String {
        return text
            .replace(Regex("[\u064B-\u0652\u0670]"), "")
            .replace(Regex("[أإآ]"), "ا")
            .replace(Regex("[ةه]"), "ه") // ← توحيد ة و ه
            .replace("ى", "ي")
            .lowercase()
            .replace(Regex("[^a-z0-9\u0600-\u06FF\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun extractKeywords(text: String): Set<String> {
        val normalized = normalizeText(text)
        return normalized.split(Regex("\\s+"))
            .filter { word ->
                word.length >= 2 &&
                word !in ARABIC_STOP_WORDS &&
                word !in ENGLISH_STOP_WORDS
            }
            .toSet()
    }

    private fun calculateRelevance(
        queryKeywords: Set<String>,
        memory: MemoryItem
    ): Double {
        if (queryKeywords.isEmpty()) return 0.0

        val memoryKeywords = extractKeywords(memory.content)
        if (memoryKeywords.isEmpty()) return 0.0

        val commonKeywords = queryKeywords.intersect(memoryKeywords)
        
        // إذا لا توجد كلمات مشتركة
        if (commonKeywords.isEmpty()) return 0.0
        
        val queryMatchRatio = commonKeywords.size.toDouble() / queryKeywords.size
        val memoryMatchRatio = commonKeywords.size.toDouble() / memoryKeywords.size

        // بونص للكلمات الطويلة (مصطلحات تقنية)
        val technicalBonus = commonKeywords.count { it.length >= 5 } * 0.15

        // بونص إذا كانت الفئة مذكورة
        val categoryBonus = if (queryKeywords.any { 
            it == normalizeText(memory.category)
        }) 0.2 else 0.0

        // الدرجة الأساسية - وزن أكبر لتطابق السؤال
        val baseScore = (queryMatchRatio * 0.7) + (memoryMatchRatio * 0.3)
        
        val totalScore = baseScore + technicalBonus + categoryBonus

        return when {
            totalScore > 1.0 -> 1.0
            totalScore < 0.0 -> 0.0
            else -> totalScore
        }
    }

    fun search(
        query: String,
        memories: List<MemoryItem>,
        limit: Int = 8,
        minScore: Double = 0.05
    ): MemorySearchResult {
        
        if (query.isBlank() || memories.isEmpty()) {
            return MemorySearchResult(
                memories = emptyList(),
                averageScore = 0.0,
                hasStrongMatch = false,
                scores = emptyMap()
            )
        }

        val queryKeywords = extractKeywords(query)
        
        if (queryKeywords.isEmpty()) {
            return MemorySearchResult(
                memories = memories.take(limit),
                averageScore = 0.0,
                hasStrongMatch = false,
                scores = emptyMap()
            )
        }

        val scoredMemories = memories.mapNotNull { memory ->
            val score = calculateRelevance(queryKeywords, memory)
            if (score >= minScore) {
                ScoredMemoryItem(memory, score)
            } else {
                null
            }
        }

        val sorted = scoredMemories
            .sortedByDescending { it.score }
            .take(limit)

        val avgScore = if (sorted.isNotEmpty()) {
            sorted.map { it.score }.average()
        } else {
            0.0
        }

        val strongMatch = sorted.any { it.score >= 0.3 }
        val scoresMap = sorted.associate { it.memory.id to it.score }

        return MemorySearchResult(
            memories = sorted.map { it.memory },
            averageScore = avgScore,
            hasStrongMatch = strongMatch,
            scores = scoresMap
        )
    }

    private data class ScoredMemoryItem(
        val memory: MemoryItem,
        val score: Double
    )
}

data class MemorySearchResult(
    val memories: List<MemoryItem>,
    val averageScore: Double,
    val hasStrongMatch: Boolean,
    val scores: Map<Long, Double>
)
