package com.example.aichat.repository

/**
 * Stemmer عربي بسيط وفعال
 * يزيل البادئات واللواحق الشائعة للوصول لجذر الكلمة
 *
 * مثال:
 * "التقنية" → "تقني"
 * "تقنية"   → "تقني"
 * "تقنيات"  → "تقني"
 * النتيجة: التطابق يحدث حتى لو كتب المستخدم بأشكال مختلفة
 */
object ArabicStemmer {

    // البادئات الشائعة مرتبة من الأطول للأقصر
    private val PREFIXES = listOf(
        "استـ", "وال", "بال", "كال", "فال", "لل",
        "وب", "وك", "ول", "وف", "ست",
        "ال", "و", "ب", "ل", "ك", "ف", "س"
    )

    // اللواحق الشائعة مرتبة من الأطول للأقصر
    private val SUFFIXES = listOf(
        "ونها", "تهم", "تها", "وها",
        "ات", "ون", "ين", "ان", "تن", "تم",
        "ها", "هم", "هن", "كم", "كن", "نا", "ني",
        "ية", "ية", "وا",
        "ه", "ي", "ا", "ة", "ت", "ن"
    )

    // الحد الأدنى لطول الجذر بعد الإزالة
    private const val MIN_STEM_LENGTH = 2

    /**
     * استخراج جذر كلمة عربية واحدة
     */
    fun stem(word: String): String {
        if (word.length <= MIN_STEM_LENGTH) return word

        var stemmed = word

        // إزالة البادئات (واحدة فقط - الأطول أولاً)
        for (prefix in PREFIXES) {
            if (stemmed.startsWith(prefix) &&
                stemmed.length - prefix.length >= MIN_STEM_LENGTH
            ) {
                stemmed = stemmed.removePrefix(prefix)
                break
            }
        }

        // إزالة اللواحق (واحدة فقط - الأطول أولاً)
        for (suffix in SUFFIXES) {
            if (stemmed.endsWith(suffix) &&
                stemmed.length - suffix.length >= MIN_STEM_LENGTH
            ) {
                stemmed = stemmed.removeSuffix(suffix)
                break
            }
        }

        return stemmed
    }

    /**
     * تطبيق Stemming على مجموعة كلمات
     * يرجع الكلمات الأصلية + الجذور معاً لتوسيع البحث
     */
    fun stemTokens(tokens: Set<String>): Set<String> {
        val result = mutableSetOf<String>()
        for (token in tokens) {
            result.add(token)          // الكلمة الأصلية
            result.add(stem(token))    // الجذر
        }
        return result
    }

    /**
     * هل الكلمتان لهما نفس الجذر؟
     */
    fun haveSameStem(word1: String, word2: String): Boolean {
        return stem(word1) == stem(word2)
    }
}
