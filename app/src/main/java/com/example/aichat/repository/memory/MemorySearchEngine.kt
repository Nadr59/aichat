package com.example.aichat.repository.memory

import com.example.aichat.data.MemoryItem
import kotlin.math.max

/**
 * محرك بحث محلي ذكي للذاكرة المشتركة
 * يدعم العربية والإنجليزية بدون مكتبات خارجية
 */
object MemorySearchEngine {

    /**
     * الكلمات الشائعة التي يتم تجاهلها في البحث
     */
    private val ARABIC_STOP_WORDS = setOf(
        "في", "من", "إلى", "على", "عن", "هو", "هي", "أن", "إن",
        "التي", "الذي", "ما", "لا", "نعم", "هل", "كيف", "لماذا",
        "أين", "متى", "ماذا", "أي", "كل", "بعض", "هذا", "هذه",
        "ذلك", "تلك", "مع", "أو", "لكن", "لأن", "حتى", "إذا",
        "عند", "بعد", "قبل", "فوق", "تحت", "بين", "خلال", "ضد"
    )

    private val ENGLISH_STOP_WORDS = setOf(
        "the", "is", "are", "was", "were", "a", "an", "in", "on",
        "at", "to", "for", "of", "with", "by", "from", "up", "about",
        "into", "through", "during", "what", "how", "where", "when",
        "why", "which", "who", "this", "that", "these", "those",
        "i", "you", "he", "she", "it", "we", "they", "my", "your",
        "can", "could", "will", "would", "should", "do", "does", "did"
    )

    /**
     * تطبيع النص العربي والإنجليزي
     */
    private fun normalizeText(text: String): String {
        return text
            // إزالة التشكيل العربي
            .replace(Regex("[\u064B-\u0652\u0670]"), "")
            // توحيد الهمزات
            .replace(Regex("[أإآ]"), "ا")
            .replace("ة", "ه")
            .replace("ى", "ي")
            // تحويل إلى lowercase
            .lowercase()
            // إزالة علامات الترقيم والأرقام
            .replace(Regex("[^a-z0-9\u0600-\u06FF\\s]"), " ")
            // إزالة المسافات المتعددة
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * استخراج الكلمات المفتاحية (بعد إزالة الكلمات الشائعة)
     */
    private fun extractKeywords(text: String): Set<String> {
        val normalized = normalizeText(text)
        return normalized.split("\\s+".toRegex())
            .filter { word ->
                word.length >= 2 && // كلمات أطول من حرف واحد
                        word !in ARABIC_STOP_WORDS &&
                        word !in ENGLISH_STOP_WORDS
            }
            .toSet()
    }

    /**
     * حساب درجة الصلة بين السؤال والذاكرة
     * 
     * الحساب:
     * - تطابق الكلمات المفتاحية (الوزن الأكبر)
     * - طول النص المشترك
     * - تطابق الفئة إذا كانت واضحة
     * 
     * النتيجة: من 0.0 (لا صلة) إلى 1.0 (صلة قوية جداً)
     */
    private fun calculateRelevance(
        queryKeywords: Set<String>,
        memory: MemoryItem
    ): Double {
        if (queryKeywords.isEmpty()) return 0.0

        val memoryKeywords = extractKeywords(memory.content)
        if (memoryKeywords.isEmpty()) return 0.0

        // 1. حساب الكلمات المشتركة
        val commonKeywords = queryKeywords.intersect(memoryKeywords)
        val keywordMatchRatio = commonKeywords.size.toDouble() / queryKeywords.size

        // 2. حساب النسبة من محتوى الذاكرة
        val memoryMatchRatio = commonKeywords.size.toDouble() / memoryKeywords.size

        // 3. بونص للكلمات الطويلة (كلمات تقنية أو مصطلحات خاصة)
        val technicalBonus = commonKeywords
            .filter { it.length >= 5 } // كلمات مثل "kotlin", "compose", "android"
            .size * 0.1

        // 4. بونص إذا كانت الفئة مذكورة
        val categoryBonus = if (queryKeywords.contains(memory.category.lowercase())) {
            0.15
        } else {
            0.0
        }

        // الدرجة النهائية (أقصى قيمة 1.0)
        val baseScore = (keywordMatchRatio * 0.6) + (memoryMatchRatio * 0.4)
        val totalScore = baseScore + technicalBonus + categoryBonus

        return totalScore.coerceIn(0.0, 1.0)
    }

    /**
     * البحث الرئيسي
     * 
     * @param query سؤال المستخدم
     * @param memories جميع الذكريات المشتركة
     * @param limit الحد الأقصى لعدد الذكريات المُعادة (افتراضي: 8)
     * @param minScore الحد الأدنى للدرجة (افتراضي: 0.1)
     * @return نتيجة البحث مع معلومات التقييم
     */
    fun search(
        query: String,
        memories: List<MemoryItem>,
        limit: Int = 8,
        minScore: Double = 0.1
    ): SearchResult {
        if (query.isBlank() || memories.isEmpty()) {
            return SearchResult(
                memories = emptyList(),
                averageScore = 0.0,
                hasStrongMatch = false,
                scores = emptyMap()
            )
        }

        val queryKeywords = extractKeywords(query)
        
        if (queryKeywords.isEmpty()) {
            // إذا لم نستخرج كلمات مفتاحية (السؤال كله كلمات شائعة)
            // نرجع أحدث الذكريات فقط
            return SearchResult(
                memories = memories.take(limit),
                averageScore = 0.0,
                hasStrongMatch = false,
                scores = emptyMap()
            )
        }

        // حساب الدرجة لكل ذاكرة
        val scoredMemories = memories.map { memory ->
            val score = calculateRelevance(queryKeywords, memory)
            ScoredMemory(memory, score)
        }

        // ترتيب حسب الدرجة (الأعلى أولاً)
        val sortedMemories = scoredMemories
            .filter { it.score >= minScore }
            .sortedByDescending { it.score }
            .take(limit)

        // حساب متوسط الدرجة
        val averageScore = if (sortedMemories.isNotEmpty()) {
            sortedMemories.map { it.score }.average()
        } else {
            0.0
        }

        // تحديد إذا كان هناك تطابق قوي
        val hasStrongMatch = sortedMemories.any { it.score >= 0.4 }

        // بناء خريطة الدرجات للتحليل
        val scores = sortedMemories.associate { 
            it.memory.id to it.score 
        }

        return SearchResult(
            memories = sortedMemories.map { it.memory },
            averageScore = averageScore,
            hasStrongMatch = hasStrongMatch,
            scores = scores
        )
    }

    /**
     * ذاكرة مع درجتها (للاستخدام الداخلي)
     */
    private data class ScoredMemory(
        val memory: MemoryItem,
        val score: Double
    )
}

/**
 * نتيجة البحث
 * 
 * @property memories الذكريات المرتبة حسب الصلة
 * @property averageScore متوسط درجة الصلة (للتقييم)
 * @property hasStrongMatch هل وجدنا تطابق قوي؟ (للقرار بشأن استخدام Qwen لاحقاً)
 * @property scores خريطة من معرّف الذاكرة إلى درجتها (للتحليل والـ Debug)
 */
data class SearchResult(
    val memories: List<MemoryItem>,
    val averageScore: Double,
    val hasStrongMatch: Boolean,
    val scores: Map<Long, Double>
)
