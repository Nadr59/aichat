package com.example.aichat.repository

import com.example.aichat.data.model.MemoryItem

class MemoryContextBuilder {

    companion object {
        private const val MAX_CONTEXT_LENGTH = 2000  // حد أقصى 2000 حرف
        private const val MAX_MEMORIES = 3           // حد أقصى 3 ذكريات
        private const val MAX_MEMORY_PREVIEW = 500   // حد أقصى 500 حرف لكل ذاكرة
    }

    /**
     * يبني نصاً خاماً فقط من الذكريات — بلا عناوين، بلا زخرفة،
     * بلا أي تعليمات استخدام مضمّنة.
     *
     * سبب هذا القرار: تعليمات الاستخدام أصبحت مسؤولية مركزية واحدة
     * في SystemPrompt.build()، بنفس الفلسفة المطبّقة على مخرجات
     * MemoryCuratorPrompt تماماً. توحيد شكل المخرجات بين المسارين
     * (Curator + Fallback) ضروري لمنع ازدواج/تعارض التوجيهات المرسلة
     * للنموذج، ولجعل منطق "متى نستخدم السياق" قابلاً للتعديل من
     * مكان واحد فقط دون الحاجة لتعديل كل مصدر سياق على حدة.
     */
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
        }.trim()

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
