package com.example.aichat.repository

import com.example.aichat.data.model.MemoryItem

object MemoryCuratorPrompt {

    fun build(userQuery: String, candidates: List<MemoryItem>): String = buildString {
        appendLine("أنت وسيط ذاكرة متخصص. مهمتك استخلاص السياق الأكثر صلة فقط، بإيجاز.")
        appendLine()
        appendLine("سؤال المستخدم الحالي:")
        appendLine("\"$userQuery\"")
        appendLine()
        appendLine("ذكريات مخزّنة (قد يتكرر بعضها أو لا صلة له):")
        candidates.forEachIndexed { i, m ->
            appendLine("${i + 1}. [${m.category}] ${m.content.take(300)}")
        }
        appendLine()
        appendLine("التعليمات:")
        appendLine("- استخلص فقط ما له صلة فعلية ومباشرة بالسؤال")
        appendLine("- ادمج الذكريات المتشابهة في نقطة واحدة موجزة")
        appendLine("- تجاهل تماماً ما لا صلة له")
        appendLine("- أخرج النتيجة كنقاط مختصرة مباشرة، بلا مقدمات أو شرح")
        appendLine("- لا تُضف عناوين أو زخرفة، فقط النقاط نفسها")
        appendLine("- إذا لم يوجد أي شيء ذو صلة فعلية، أخرج فقط الكلمة: NONE")
    }
}
