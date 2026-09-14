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
            appendLine("📌 تعليمات:")
            appendLine()
            appendLine("• المعلومات أعلاه من ذاكرة المستخدم المشتركة")
            appendLine("• استخدمها في إجابتك إذا كانت تساعد في الإجابة على السؤال")
            appendLine("• إذا لم تكن مرتبطة بالسؤال، تجاهلها")
            appendLine()
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        }

        return context
    }
}
