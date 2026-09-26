package com.example.aichat.repository

import com.example.aichat.data.model.MemoryItem

class MemoryContextBuilder {

    companion object {
        private const val MAX_CONTEXT_LENGTH = 2000
        private const val MAX_MEMORIES = 3
        private const val MAX_MEMORY_PREVIEW = 500

        // نفس التحذير المستخدَم في MemoryCuratorService — يجب أن يبقى
        // متطابقاً بين المسارين لضمان سلوك متسق بصرف النظر عن مصدر السياق.
        private const val CONTEXT_DISCLAIMER =
            "\n\n⚠️ ملاحظة: هذا كل ما هو مؤكد ومتاح بخصوص هذا الموضوع تحديداً. " +
            "لا تُضف تفاصيل تقنية أو تاريخية أو مؤسسية إضافية من معرفتك العامة " +
            "غير مذكورة صراحة أعلاه، حتى لو بدت مألوفة أو مرتبطة لديك."
    }

    fun build(memories: List<MemoryItem>): String {

        if (memories.isEmpty()) {
            android.util.Log.d("MemoryContextBuilder", "⚠️ No memories to build context from")
            return ""
        }

        android.util.Log.d("MemoryContextBuilder", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        android.util.Log.d("MemoryContextBuilder", "Building context from ${memories.size} memories")

        val limitedMemories = memories.take(MAX_MEMORIES)

        val context = buildString {
            limitedMemories.forEachIndexed { index, memory ->

                val content = if (memory.content.length > MAX_MEMORY_PREVIEW) {
                    memory.content.take(MAX_MEMORY_PREVIEW) + "..."
                } else {
                    memory.content
                }

                appendLine("${index + 1}. [${memory.category}] $content")

                android.util.Log.d("MemoryContextBuilder", "Memory ${index + 1}: ${memory.content.take(50)}...")
            }
        }.trim() + CONTEXT_DISCLAIMER

        val finalContext = if (context.length > MAX_CONTEXT_LENGTH) {
            android.util.Log.w("MemoryContextBuilder", "⚠️ Context too long (${context.length}), truncating to $MAX_CONTEXT_LENGTH")
            context.take(MAX_CONTEXT_LENGTH) + "..."
        } else {
            context
        }

        android.util.Log.d("MemoryContextBuilder", "✅ Context built: ${finalContext.length} characters")
        android.util.Log.d("MemoryContextBuilder", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

        return finalContext
    }
}
