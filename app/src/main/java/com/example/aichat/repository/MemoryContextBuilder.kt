package com.example.aichat.repository

import com.example.aichat.data.model.MemoryItem

class MemoryContextBuilder {

    companion object {
        private const val MAX_CONTEXT_LENGTH = 2000  // حد أقصى 2000 حرف
        private const val MAX_MEMORIES = 3           // حد أقصى 3 ذكريات
        private const val MAX_MEMORY_PREVIEW = 500   // حد أقصى 500 حرف لكل ذاكرة
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
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine("📚 معلومات من الذاكرة المشتركة:")
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine()
            
            limitedMemories.forEachIndexed { index, memory ->
                
                // تقليص المحتوى
                val content = if (memory.content.length > MAX_MEMORY_PREVIEW) {
                    memory.content.take(MAX_MEMORY_PREVIEW) + "..."
                } else {
                    memory.content
                }
                
                appendLine("${index + 1}. [${memory.category}] $content")
                appendLine()
                
                android.util.Log.d("MemoryContextBuilder", "Memory ${index + 1}: ${memory.content.take(50)}...")
            }
            
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine("📌 تعليمات:")
            appendLine()
            appendLine("• المعلومات أعلاه من ذاكرة المستخدم")
            appendLine("• استخدمها إذا كانت تساعد في الإجابة")
            appendLine()
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        }

        // ✅ تقليص إضافي إذا تجاوز الحد
        val finalContext = if (context.length > MAX_CONTEXT_LENGTH) {
            android.util.Log.w("MemoryContextBuilder", "⚠️ Context too long (${context.length}), truncating to $MAX_CONTEXT_LENGTH")
            context.take(MAX_CONTEXT_LENGTH) + "\n..."
        } else {
            context
        }

        android.util.Log.d("MemoryContextBuilder", "✅ Context built: ${finalContext.length} characters")
        android.util.Log.d("MemoryContextBuilder", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

        return finalContext
    }
}
