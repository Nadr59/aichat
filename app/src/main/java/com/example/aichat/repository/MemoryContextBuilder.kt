package com.example.aichat.repository

import com.example.aichat.data.model.MemoryItem

class MemoryContextBuilder {

    fun build(memories: List<MemoryItem>): String {

        if (memories.isEmpty()) {
            return ""
        }

        return buildString {
            appendLine("=== USER MEMORY (authoritative facts about the user) ===")
            appendLine()

            memories.forEachIndexed { index, memory ->
                appendLine("${index + 1}. [${memory.category}] ${memory.content}")
            }

            appendLine()
            appendLine("RULES:")
            appendLine("- The facts above are TRUE, provided by the user's shared memory.")
            appendLine("- If the user's question relates to any fact above, you MUST use it in your answer.")
            appendLine("- NEVER say you don't know something that is stated above.")
            appendLine("- هذه حقائق مؤكدة من ذاكرة المستخدم المشتركة، استخدمها إلزامياً عند صلتها بالسؤال، ولا تقل أبداً إنك لا تعرفها.")
            appendLine("=== END USER MEMORY ===")
        }
    }
}
