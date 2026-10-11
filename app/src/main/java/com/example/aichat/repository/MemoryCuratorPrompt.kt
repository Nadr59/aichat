package com.example.aichat.repository

import com.example.aichat.data.model.MemoryItem
import com.example.aichat.data.model.QueryStyle

object MemoryCuratorPrompt {

    // لضبط حجم النص المرسل للوسيط
    private const val MAX_CANDIDATES_IN_PROMPT = 12
    private const val MAX_SNIPPET_LEN = 360
    private const val MAX_QUERY_KEYWORDS = 10
    private const val MIN_KEYWORD_LEN = 3

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
     *
     * ✅ تعديل مهم:
     * - نعرض "مقتطف snippet" ذكي من كل ذاكرة حول كلمات السؤال بدل أخذ أول 400 حرف فقط.
     * - هذا يقلل فشل اختيار الذكريات الطويلة/المقسمة.
     */
    fun buildSelectionPrompt(
        query: String,
        candidates: List<MemoryItem>
    ): String = buildString {

        val cleanQuery = query.trim()

        appendLine("أنت Curator للذاكرة المشتركة.")
        appendLine("مهمتك الوحيدة: اختيار IDs للذكريات المرتبطة مباشرة بسؤال المستخدم.")
        appendLine()

        appendLine("قواعد صارمة (لا يمكن كسرها):")
        appendLine("1) أجب بـ JSON فقط (كائن واحد).")
        appendLine("2) ممنوع أي نص قبل JSON أو بعده.")
        appendLine("3) ممنوع Markdown أو ```.")
        appendLine("4) لا تلخص محتوى الذكريات ولا تعيد كتابته.")
        appendLine("5) لا تخترع أي ID.")
        appendLine("6) استخدم فقط IDs الموجودة في قائمة المرشحين.")
        appendLine("7) اختر ما يجيب مباشرة عن السؤال فقط.")
        appendLine("8) الحد الأقصى للاختيار: 5 ذكريات.")
        appendLine("9) إذا لا توجد ذاكرة مرتبطة مباشرة: اجعل selectedIds فارغة [].")
        appendLine("10) irrelevantIds اختيارية.")
        appendLine()

        appendLine("السؤال:")
        appendLine("\"$cleanQuery\"")
        appendLine()

        appendLine("المرشحون (قد يكون المحتوى مقتطفاً/snippet وليس النص الكامل):")
        if (candidates.isEmpty()) {
            appendLine("(لا توجد مرشحات)")
        } else {
            candidates
                .take(MAX_CANDIDATES_IN_PROMPT)
                .forEach { memory ->
                    val snippet = excerptForQuery(
                        content = memory.content,
                        query = cleanQuery,
                        maxLen = MAX_SNIPPET_LEN
                    )

                    appendLine("ID: ${memory.id}")
                    appendLine("CATEGORY: ${memory.category}")
                    appendLine("SNIPPET: $snippet")
                    appendLine()
                }
        }

        appendLine("أخرج JSON بهذا الشكل فقط (مثال):")
        appendLine("""{"selectedIds":[27,31],"reasoning":"سبب مختصر","irrelevantIds":[42]}""")
    }

    /**
     * ✅ Query Enhancer (JSON minimal)
     * - لا نقاط، لا قالب "سؤال:".
     * - المخرجات JSON فقط لتفادي فشل الالتقاط.
     */
    fun buildEnhancerPrompt(
        userQuery: String,
        mediatorIdentityText: String
    ): String = buildString {

        val trimmedQuery = userQuery.trim()
        val isArabic = containsArabic(trimmedQuery)
        fun a(ar: String, en: String) = if (isArabic) ar else en

        appendLine(
            a(
                "أنت Query Enhancer. مهمتك الوحيدة: إعادة صياغة طلب المستخدم ليصبح أدق وأكثر قابلية للإجابة (بدون الإجابة عليه).",
                "You are a Query Enhancer. Your only task: rewrite the user's request to be clearer and more answerable (without answering it)."
            )
        )
        appendLine()

        if (mediatorIdentityText.isBlank()) {
            appendLine(
                a(
                    "لا توجد هوية مخصصة. حسّن الطلب بقواعد عامة فقط.",
                    "No custom identity provided. Enhance using general rules only."
                )
            )
        } else {
            appendLine(
                a(
                    "هوية/قواعد التحويل (تعليمات عليا يجب الالتزام بها قدر الإمكان دون تغيير نية المستخدم):",
                    "Identity / transformation rules (high-priority instructions; follow as much as possible without changing intent):"
                )
            )
            appendLine("---")
            appendLine(mediatorIdentityText.trim())
            appendLine("---")
        }
        appendLine()

        appendLine(a("طلب المستخدم الأصلي:", "Original user request:"))
        appendLine("\"$trimmedQuery\"")
        appendLine()

        appendLine(a("قواعد صارمة:", "Strict rules:"))
        appendLine(
            a(
                "1) ممنوع الإجابة على الطلب. فقط أعد صياغته.",
                "1) Do NOT answer. Only rewrite the query."
            )
        )
        appendLine(
            a(
                "2) لا تغيّر موضوع الطلب أو نية المستخدم.",
                "2) Do NOT change the topic or intent."
            )
        )
        appendLine(
            a(
                "3) لا تخترع حقائق/أرقام/مدة زمنية غير مذكورة. إذا احتجت معلومة غير موجودة، اجعلها شرطاً/placeholder داخل نفس العبارة بدل الافتراض.",
                "3) Do NOT invent facts/numbers/time ranges not stated. If missing info is needed, include it as a condition/placeholder in the same sentence instead of assuming."
            )
        )
        appendLine(a("4) لا تستخدم Markdown أو ```.", "4) No Markdown or code fences."))
        appendLine()

        appendLine(
            a(
                "أخرج JSON فقط بدون أي نص آخر. الشكل الإلزامي (مفتاحان فقط):",
                "Output JSON only with no extra text. Required schema (exactly two keys):"
            )
        )
        appendLine("""{"rewritten":"...","outOfDomain":false}""")
        appendLine()

        appendLine(a("شرح الحقول:", "Field rules:"))
        appendLine(
            a(
                "- rewritten: جملة واحدة (أو جملتين كحد أقصى) تمثل الطلب بعد التحسين.",
                "- rewritten: one sentence (max two) representing the improved query."
            )
        )
        appendLine(
            a(
                "- outOfDomain: true إذا كان الطلب خارج نطاق الهوية تماماً ولا يمكن تطبيقها منطقياً، وإلا false.",
                "- outOfDomain: true if the request is completely outside the identity domain, otherwise false."
            )
        )
    }

    /**
     * تحسين صياغة السؤال فقط حسب الأسلوب.
     */
    fun buildStyleRefinementPrompt(
        userQuery: String,
        style: QueryStyle
    ): String = style.buildPrompt(userQuery)

    // ============================================================
    // Helpers
    // ============================================================

    private fun containsArabic(text: String): Boolean {
        return Regex("[\\u0600-\\u06FF]").containsMatchIn(text)
    }

    private fun excerptForQuery(content: String, query: String, maxLen: Int): String {
        val c = content.replace("\u0000", "").trim()
        if (c.isBlank()) return ""
        if (c.length <= maxLen) return normalizeSpaces(c)

        val keywords = extractKeywords(query)
        if (keywords.isEmpty()) {
            return normalizeSpaces(c.take(maxLen)) + "…"
        }

        val lower = c.lowercase()
        val hits = keywords.mapNotNull { k ->
            val idx = lower.indexOf(k)
            if (idx >= 0) idx else null
        }

        val hitIndex = hits.minOrNull()
        if (hitIndex == null) {
            return normalizeSpaces(c.take(maxLen)) + "…"
        }

        // نافذة حول التطابق: ثلث قبل + ثلثين بعد تقريباً
        val before = maxLen / 3
        val start = (hitIndex - before).coerceAtLeast(0)
        val end = (start + maxLen).coerceAtMost(c.length)

        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < c.length) "…" else ""

        return prefix + normalizeSpaces(c.substring(start, end)) + suffix
    }

    private fun extractKeywords(query: String): List<String> {
        return query.lowercase()
            .replace(Regex("[^\\p{L}\\p{Nd}\\s]"), " ")
            .split(Regex("\\s+"))
            .map { it.trim() }
            .filter { it.length >= MIN_KEYWORD_LEN }
            .distinct()
            .take(MAX_QUERY_KEYWORDS)
    }

    private fun normalizeSpaces(s: String): String {
        return s.replace(Regex("\\s+"), " ").trim()
    }
}
