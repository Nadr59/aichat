package com.example.aichat.repository

import com.example.aichat.data.model.MemoryItem

class MemoryContextBuilder {

    fun build(memories: List<MemoryItem>): String {
        if (memories.isEmpty()) {
            return ""
        }

        val context = buildString {
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine("📚 معلومات من الذاكرة المشتركة:")
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine()
            
            memories.forEachIndexed { index, memory ->
                appendLine("${index + 1}. [${memory.category}] ${memory.content}")
                appendLine()
            }
            
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine("⚠️ تعليمات مهمة:")
            appendLine("1. إذا استخدمت أي معلومة من الذاكرة أعلاه، ابدأ إجابتك بـ: ✅ [استخدمت الذاكرة]")
            appendLine("2. إذا لم تستخدم الذاكرة، ابدأ إجابتك بـ: ⚪ [لم أستخدم الذاكرة]")
            appendLine("3. استخدم الذاكرة فقط إذا كانت مرتبطة فعلاً بالسؤال.")
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        }

        return context
    }
}
