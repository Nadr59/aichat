package com.example.aichat.repository

import com.example.aichat.data.model.MemoryItem

object MemorySearchEngine {

    private val ARABIC_STOP_WORDS = setOf(
        "في", "من", "إلى", "على", "عن", "هو", "هي", "أن", "إن",
        "التي", "الذي", "ما", "لا", "نعم", "هل", "كيف", "لماذا",
        "أين", "متى", "ماذا", "أي", "كل", "بعض", "هذا", "هذه",
        "ذلك", "تلك", "مع", "أو", "لكن", "لأن", "حتى", "إذا",
        "عند", "بعد", "قبل", "فوق", "تحت", "بين", "خلال", "ضد",
        "كان", "يكون", "تكون", "وهو", "وهي", "وما", "ومن", "وفي"
    )

    private val ENGLISH_STOP_WORDS = setOf(
        "the", "is", "are", "was", "were", "a", "an", "in", "on",
        "at", "to", "for", "of", "with", "by", "from", "up", "about",
        "into", "through", "during", "what", "how", "where", "when",
        "why", "which", "who", "this", "that", "these", "those",
        "i", "you", "he", "she", "it", "we", "they", "my", "your",
        "can", "could", "will", "would", "should", "do", "does", "did",
        "and", "or", "but", "not", "so", "if", "as", "be", "has", "have"
    )

    // ============================================================
    // تطبيع النص
    // مُصحَّح: توحيد ة → ه كان خاطئاً (يُغير معنى الكلمات)
    // الصحيح: إزالة الحركات فقط وتوحيد أشكال الألف
    // ============================================================

    private fun normalizeText(text: String): String {
        return text
            // إزالة الحركات (تشكيل)
            .replace(Regex("[\u064B-\u0652\u0670\u0640]"), "")
            // توحيد أشكال الألف
            .replace(Regex("[أإآ]"), "ا")
            // توحيد ى → ي
            .replace("ى", "ي")
            // ✅ مُصحَّح: إزالة توحيد ة→ه (كان يُخرّب معنى الكلمات)
            // مثال: "طلبة" كانت تصبح "طلبه" وتفشل مقارنتها بـ "طلاب"
            .lowercase()
            .replace(Regex("[^a-z0-9\u0600-\u06FF\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    // ============================================================
    // استخراج الكلمات المفتاحية
    // ✅ مُحسَّن: إضافة ArabicStemmer لتوسيع التطابق
    // ============================================================

    private fun extractKeywords(text: String): Set<String> {
        val normalized = normalizeText(text)
        val tokens = normalized.split(Regex("\\s+"))
            .filter { word ->
                word.length >= 2 &&
                word !in ARABIC_STOP_WORDS &&
                word !in ENGLISH_STOP_WORDS
            }
            .toSet()

        // ✅ إضافة جذور الكلمات العربية لتوسيع البحث
        // مثال: "التقنية" → {"التقنية", "تقني"}
        return ArabicStemmer.stemTokens(tokens)
    }

    // ============================================================
    // حساب درجة الصلة
    // ✅ مُحسَّن: حساب أوضح ومنطق أفضل
    // ============================================================

    private fun calculateRelevance(
        queryKeywords: Set<String>,
        memory: MemoryItem
    ): Double {
        if (queryKeywords.isEmpty()) return 0.0

        val memoryKeywords = extractKeywords(memory.content)
        if (memoryKeywords.isEmpty()) return 0.0

        // الكلمات المشتركة (بعد Stemming كلا الطرفين)
        val commonKeywords = queryKeywords.intersect(memoryKeywords)
        if (commonKeywords.isEmpty()) return 0.0

        // نسبة تغطية السؤال
        val queryMatchRatio = commonKeywords.size.toDouble() / queryKeywords.size

        // نسبة تغطية الذاكرة
        val memoryMatchRatio = commonKeywords.size.toDouble() / memoryKeywords.size

        // ✅ بونص للكلمات الطويلة (مصطلحات تقنية أو متخصصة)
        val technicalBonus = commonKeywords.count { it.length >= 5 } * 0.1

        // ✅ بونص إذا تطابقت الفئة
        val categoryBonus = if (queryKeywords.any {
            it == normalizeText(memory.category)
        }) 0.15 else 0.0

        // ✅ بونص إذا ظهرت الكلمة في بداية الذاكرة (أهمية أعلى)
        val positionBonus = if (commonKeywords.any { kw ->
            normalizeText(memory.content).startsWith(kw)
        }) 0.1 else 0.0

        // الدرجة الأساسية: وزن أكبر لتغطية السؤال
        val baseScore = (queryMatchRatio * 0.65) + (memoryMatchRatio * 0.35)

        val totalScore = baseScore + technicalBonus + categoryBonus + positionBonus

        return totalScore.coerceIn(0.0, 1.0)
    }

    // ============================================================
    // البحث الرئيسي
    // ============================================================

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

        android.util.Log.d(
            "MemorySearch",
            "🔑 Query keywords (with stems): $queryKeywords"
        )

        if (queryKeywords.isEmpty()) {
            // إذا لم توجد كلمات مفيدة: أرجع الأحدث
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

        android.util.Log.d(
            "MemorySearch",
            "📊 Results: ${sorted.size} | avgScore: ${
                String.format("%.3f", avgScore)
            } | strongMatch: $strongMatch"
        )

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

// ============================================================
// نتيجة البحث
// ============================================================

data class MemorySearchResult(
    val memories: List<MemoryItem>,
    val averageScore: Double,
    val hasStrongMatch: Boolean,
    val scores: Map<Long, Double>
)
