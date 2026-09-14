package com.example.aichat.repository

import com.example.aichat.data.model.MemoryItem

class MemoryContextBuilder {

    fun build(memories: List<MemoryItem>): String {
        
        // ✅✅✅ إذا لا توجد ذكريات، نُخبر النموذج صراحة
        if (memories.isEmpty()) {
            return """
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
⚠️ تشخيص الذاكرة:
- عدد الذكريات المُرسلة: 0
- الحالة: لا توجد ذكريات مرتبطة بهذا السؤال

ملاحظة: ابدأ إجابتك بـ [DEBUG: لا ذكريات]
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            """.trimIndent()
        }

        val context = buildString {
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine("📚 معلومات من الذاكرة المشتركة:")
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine()
            appendLine("⚠️ تشخيص: تم إرسال ${memories.size} ذاكرة")
            appendLine()
            
            memories.forEachIndexed { index, memory ->
                appendLine("${index + 1}. [${memory.category}] ${memory.content}")
                appendLine()
            }
            
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine("⚠️ تعليمات مهمة:")
            appendLine("1. إذا استخدمت أي معلومة من الذاكرة أعلاه، ابدأ إجابتك بـ: ✅ [استخدمت الذاكرة]")
            appendLine("2. إذا لم تستخدم الذاكرة، ابدأ إجابتك بـ: ⚪ [لم أستخدم الذاكرة]")
            appendLine("3. للتشخيص: اذكر عدد الذكريات التي استلمتها في بداية ردك")
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        }

        return context
    }
}
