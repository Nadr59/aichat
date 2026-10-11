package com.example.aichat.repository

import com.example.aichat.data.model.MemoryItem
import com.example.aichat.data.model.QueryStyle

object MemoryCuratorPrompt {

    /**
     * الدالة القديمة لتنقية الذاكرة.
     */
    fun build(
        userQuery: String,
        candidates: List<MemoryItem>,
        mediatorIdentityText: String = ""
    ): String = buildString {

        appendLine("أنت وسيط ذكي. مهمتك تنقية المعلومات من الذاكرة المشتركة وتقديمها بشكل منظم.")
        appendLine()
        appendLine("سؤال المستخدم:")
        appendLine("\"$userQuery\"")
        appendLine()

        if (candidates.isNotEmpty()) {
            appendLine("الذكريات المحتملة:")
            candidates.forEachIndexed { i, mem ->
                appendLine("${i + 1}. ${mem.content}")
            }
            appendLine()
        }

        if (mediatorIdentityText.isNotBlank()) {
            appendLine("نقاط الهوية/الأسلوب:")
            appendLine(mediatorIdentityText.trim())
            appendLine()
        }

        appendLine("مهمتك:")
        appendLine("1. اختر فقط المعلومات المرتبطة مباشرة بالسؤال")
        appendLine("2. رتبها حسب الأهمية")
        appendLine("3. لخّصها بشكل موجز")
        appendLine("4. إذا لم يكن هناك معلومات مفيدة، قل: NONE")
        appendLine()
        appendLine("أخرج الملخص مباشرة بدون ترويسات:")
    }

    /**
     * Prompt مخصص لاختيار الذكريات بواسطة ID الحقيقي.
     */
    fun buildSelectionPrompt(
        query: String,
        candidates: List<MemoryItem>
    ): String = buildString {

        appendLine("أنت Curator للذاكرة المشتركة.")
        appendLine("مهمتك الوحيدة هي اختيار IDs للذكريات المرتبطة مباشرة بسؤال المستخدم.")
        appendLine()

        appendLine("القواعد الصارمة:")
        appendLine("1. أجب بـ JSON فقط.")
        appendLine("2. لا تكتب أي نص قبل JSON أو بعده.")
        appendLine("3. لا تستخدم Markdown أو ```.")
        appendLine("4. لا تلخص محتوى الذكريات.")
        appendLine("5. لا تخترع أي ID.")
        appendLine("6. استخدم فقط IDs الموجودة فعلياً في قائمة المرشحين.")
        appendLine("7. اختر الذكريات التي تجيب مباشرة عن السؤال فقط.")
        appendLine("8. الحد الأقصى للاختيار هو 5 ذكريات.")
        appendLine("9. إذا لم توجد ذاكرة مرتبطة مباشرة بالسؤال، استخدم selectedIds فارغة.")
        appendLine("10. irrelevantIds اختيارية، ويمكن تركها فارغة.")
        appendLine()

        appendLine("السؤال:")
        appendLine("\"$query\"")
        appendLine()

        appendLine("المرشحون:")
        if (candidates.isEmpty()) {
            appendLine("(لا توجد مرشحات)")
        } else {
            candidates.forEach { memory ->
                appendLine("ID: ${memory.id}")
                appendLine("المحتوى: ${memory.content.take(400)}")
                appendLine()
            }
        }

        appendLine("أخرج JSON بهذا الشكل فقط:")
        appendLine("""{"selectedIds":[27,31],"reasoning":"سبب مختصر","irrelevantIds":[42]}""")
    }

    /**
     * ✅ Prompt محسن للـ Query Enhancer
     * - يعالج مشكلة اللغة: القالب (Labels) يصبح عربي/إنجليزي حسب لغة سؤال المستخدم.
     * - يلزم شكل bullets واضح "- " لتسهيل الـ parsing.
     */
    fun buildEnhancerPrompt(
        userQuery: String,
        mediatorIdentityText: String
    ): String = buildString {

        val trimmedQuery = userQuery.trim()
        val isArabic = containsArabic(trimmedQuery)

        val alertLabel = if (isArabic) "تنبيه" else "Alert"
        val questionLabel = if (isArabic) "سؤال" else "Question"

        fun a(ar: String, en: String) = if (isArabic) ar else en

        appendLine(a("أنت Query Enhancer محترف.", "You are a professional Query Enhancer."))
        appendLine(a("مهمتك الوحيدة: تحسين وإثراء طلب المستخدم.", "Your only task: refine and enrich the user's request."))
        appendLine()

        // Identity handling
        if (mediatorIdentityText.isBlank()) {
            appendLine(a("حالة الهوية: لا توجد هوية مخصصة.", "Identity status: no custom identity is provided."))
            appendLine(a(
                "المطلوب: حسّن السؤال بشكل محايد بقواعد عامة فقط، ولا تخترع هوية من عندك.",
                "Requirement: enhance neutrally using general rules only; do NOT invent an identity."
            ))
        } else {
            appendLine(a(
                "قاعدة الأولوية القصوى (Identity-First):",
                "Highest priority rule (Identity-First):"
            ))
            appendLine(a(
                "يجب التقيد بالهوية التالية كقواعد إلزامية لتحسين الطلب.",
                "You must follow the identity below as mandatory design rules for the enhancement."
            ))
            appendLine(a(
                "اعتبر كل كلمة في الهوية أمراً واجب التنفيذ ما أمكن دون تحريف سؤال المستخدم.",
                "Treat every identity instruction as mandatory whenever it doesn't distort the user's intent."
            ))
            appendLine(a("الهوية المعتمدة:", "Approved identity:"))
            appendLine("---")
            appendLine(mediatorIdentityText.trim())
            appendLine("---")
            appendLine()
            appendLine(a(
                "منطق التقيد:",
                "Compliance logic:"
            ))
            appendLine(a(
                "أ) إذا كان السؤال مرتبطاً بالهوية: طبّق الهوية بصرامة في صياغة السؤال والنقاط.",
                "A) If the query is related to the identity: apply it strictly in the question and bullets."
            ))
            appendLine(a(
                "ب) إذا كان السؤال بعيداً تماماً عن الهوية: حسّن بقواعد عامة محايدة، وابدأ بتنبيه واضح أن الطلب لا ينتمي للهوية.",
                "B) If the query is completely unrelated: enhance with neutral general rules, and start with a clear alert that it doesn't belong to the identity."
            ))
        }
        appendLine()

        appendLine(a("طلب المستخدم الأصلي:", "Original user request:"))
        appendLine("\"$trimmedQuery\"")
        appendLine()

        appendLine(a("قواعد لا يمكن كسرها:", "Unbreakable rules:"))
        appendLine(a("1) ممنوع الإجابة على السؤال. حسّن صياغته فقط.", "1) Do NOT answer the question. Only rewrite/enhance the query."))
        appendLine(a("2) ممنوع تغيير موضوع السؤال أو نية المستخدم.", "2) Do NOT change the topic or the user's intent."))
        appendLine(a("3) لا تكتب أي مقدمة/شرح/ميتا-كلام.", "3) No preface/explanation/meta talk."))
        appendLine(a("4) لا تستخدم Markdown أو ```.", "4) No Markdown and no code fences."))
        appendLine()

        appendLine(a("قواعد التنسيق الإلزامية:", "Mandatory formatting rules:"))
        appendLine(a("- استخدم نفس لغة المستخدم تماماً.", "- Use the exact same language as the user."))
        appendLine(a("- استخدم الوسمين حرفياً حسب اللغة:", "- Use these labels literally depending on the language:"))
        appendLine(a("  - عربي: \"$questionLabel:\" و \"$alertLabel:\"", "  - English: \"$questionLabel:\" and \"$alertLabel:\""))
        appendLine(a("- كل نقطة يجب أن تبدأ حرفياً بـ \"- \" (شرطة ثم مسافة).", "- Every bullet MUST start with \"- \" (hyphen then space)."))
        appendLine()

        appendLine(a("صيغة الإخراج الإلزامية (اختر حالة واحدة فقط):", "Required output format (choose exactly one case):"))
        appendLine()

        appendLine(a("الحالة 1 (مرتبط بالهوية):", "Case 1 (related to identity):"))
        appendLine("$questionLabel: <${a("سؤال/طلب مُحسّن واحد فقط", "one single improved question/request")}>")
        appendLine("- <${a("نقطة توضيحية 1", "bullet 1")}>")
        appendLine("- <${a("نقطة توضيحية 2", "bullet 2")}>")
        appendLine("- <${a("نقطة توضيحية 3", "bullet 3")}>")
        appendLine("- <${a("نقطة توضيحية 4", "bullet 4")}>")
        appendLine("- <${a("نقطة توضيحية 5", "bullet 5")}>")
        appendLine()

        appendLine(a("الحالة 2 (بعيد عن الهوية):", "Case 2 (unrelated to identity):"))
        appendLine("$alertLabel: ${a("هذا الطلب لا ينتمي إلى مجال الهوية المحددة، تم تحسينه بقواعد عامة.", "This request does not belong to the provided identity domain; it was enhanced using general rules.")}")
        appendLine("$questionLabel: <${a("سؤال/طلب مُحسّن واحد فقط", "one single improved question/request")}>")
        appendLine("- <${a("نقطة توضيحية 1", "bullet 1")}>")
        appendLine("- <${a("نقطة توضيحية 2", "bullet 2")}>")
        appendLine("- <${a("نقطة توضيحية 3", "bullet 3")}>")
        appendLine("- <${a("نقطة توضيحية 4", "bullet 4")}>")
        appendLine("- <${a("نقطة توضيحية 5", "bullet 5")}>")
        appendLine()

        appendLine(a(
            "مهم جداً: أخرج 5 نقاط بالضبط ولا تكتب أي نص خارج القالب.",
            "Very important: output exactly 5 bullets and no text outside the template."
        ))
    }

    /**
     * تحسين صياغة السؤال فقط حسب الأسلوب.
     */
    fun buildStyleRefinementPrompt(
        userQuery: String,
        style: QueryStyle
    ): String = style.buildPrompt(userQuery)

    private fun containsArabic(text: String): Boolean {
        // Simple heuristic: any Arabic-range char => treat as Arabic
        return Regex("[\\u0600-\\u06FF]").containsMatchIn(text)
    }
}
