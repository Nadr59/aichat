package com.example.aichat.repository

import com.example.aichat.data.model.MemoryItem

/**
 * يحول الذكريات المختارة إلى نص منظم يمكن تمريره
 * لاحقًا إلى طبقة المحادثة كسياق للذاكرة.
 *
 * هذا الكلاس لا يقوم بـ:
 * - البحث في قاعدة البيانات
 * - تعديل الذكريات
 * - حذف الذكريات
 * - الاتصال بأي مزود AI
 *
 * مهمته فقط تنسيق الذكريات الموجودة مسبقًا.
 */
class MemoryContextBuilder {

    fun build(memories: List<MemoryItem>): String {

        if (memories.isEmpty()) {
            return ""
        }

        val validMemories = memories
            .filter { it.content.isNotBlank() }
            .take(MAX_MEMORIES)

        if (validMemories.isEmpty()) {
            return ""
        }

        val memoryLines = validMemories.mapIndexed { index, memory ->

            val category = getCategoryName(memory.category)

            "${index + 1}. [$category] ${memory.content.trim()}"
        }

        return buildString {

            appendLine("معلومات من الذاكرة المشتركة:")

            memoryLines.forEach { line ->
                appendLine(line)
            }

            appendLine()
            append(
                "استخدم هذه المعلومات فقط عندما تكون مرتبطة بالسؤال الحالي."
            )
        }
    }

    private fun getCategoryName(category: String): String {

        return when (category.trim().uppercase()) {

            "KNOWLEDGE" ->
                "معرفة"

            "PROJECT" ->
                "مشروع"

            "PREFERENCE" ->
                "تفضيل"

            "OTHER" ->
                "أخرى"

            else ->
                category.trim().ifBlank { "أخرى" }
        }
    }

    companion object {

        /**
         * الحد الأقصى للذكريات التي يتم وضعها في السياق.
         *
         * هذا الحد للتنسيق فقط، وليس له علاقة بحدود
         * النماذج أو المزودين.
         */
        private const val MAX_MEMORIES = 8
    }
}
