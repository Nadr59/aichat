package com.example.aichat.repository

import com.example.aichat.data.model.MemoryItem
import com.example.aichat.data.model.QueryStyle

object MemoryCuratorPrompt {

    /**
     * الدالة القديمة لتنقية الذاكرة.
     *
     * تبقى للتوافق مع المسار الحالي حتى يتم الانتقال إلى
     * MemoryWindowBuilder في المرحلة 3.
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
            appendLine(mediatorIdentityText)
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
     * 🆕 المرحلة 2:
     * Prompt مخصص لاختيار الذكريات بواسطة ID الحقيقي.
     *
     * مهم جداً:
     * - لا يستخدم رقم ترتيب الذاكرة.
     * - لا يسمح للـ LLM بتلخيص المحتوى.
     * - المخرج المطلوب JSON فقط.
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
        appendLine(
            """{"selectedIds":[27,31],"reasoning":"سبب مختصر","irrelevantIds":[42]}"""
        )
    }

    /**
     * ✅ Enhancement (Identity-First):
     * توسيع/تحسين الطلب بتقمّص الهوية "إلى أقصى حد" كمواصفات للطلب،
     * وليس مجرد تغيير أسلوب الكتابة.
     *
     * مخرجات الدالة: نص الطلب المُحسّن فقط (بدون مقدمات).
     */
    fun buildEnhancerPrompt(
        userQuery: String,
        mediatorIdentityText: String
    ): String = buildString {

        appendLine("أنت Query Enhancer.")
        appendLine("مهمتك الوحيدة: تحسين/توسيع *طلب المستخدم* بتقمّص الهوية التالية إلى أقصى حد.")
        appendLine()

        // ✅ شرط المستخدم: الهوية أعلى أولوية من أي قاعدة عامة
        appendLine("قاعدة أولوية صارمة:")
        appendLine("اعتبر الهوية أعلى أولوية من أي قاعدة عامة،")
        appendLine("إلا قاعدتين لا يمكن كسرهما إطلاقاً:")
        appendLine("1) لا تُجب على السؤال.")
        appendLine("2) لا تُغيّر موضوع السؤال أو نية المستخدم.")
        appendLine()

        appendLine("الهوية (طبّقها حرفياً كقواعد تصميم للطلب):")
        appendLine(mediatorIdentityText.trim())
        appendLine()

        appendLine("طلب المستخدم الأصلي:")
        appendLine(userQuery.trim())
        appendLine()

        appendLine("قواعد عامة (تُطبَّق دائماً ما لم تتعارض مع الهوية):")
        appendLine("- لا تكتب أي مقدمات أو عناوين أو شرح لما فعلته. اكتب الطلب النهائي فقط.")
        appendLine("- حافظ على لغة المستخدم.")
        appendLine("- اجعل الطلب أكثر قابلية للتنفيذ عبر إضافة تفاصيل موضوعية مثل:")
        appendLine("  • مخرجات مطلوبة بوضوح")
        appendLine("  • قيود وحدود (بيئة/لغة/إصدار/جمهور/وقت/ميزانية... حسب الهوية)")
        appendLine("  • أمثلة مدخلات/مخرجات أو حالات استخدام (حسب الهوية)")
        appendLine("  • خطوات/أسئلة فرعية محددة تساعد المجيب على تقديم إجابة أدق")
        appendLine("- لا تستخدم أمثلة جاهزة أو أسئلة عامة غير مرتبطة بمدخل المستخدم.")
        appendLine("- إذا كانت الهوية لا تنطبق منطقياً على هذا الطلب، أعد نص الطلب الأصلي كما هو دون تعديل.")
        appendLine("- حد أقصى تقريبي: 700 حرف. إذا تجاوزت ذلك اختصر دون فقد جوهر التوسعة.")
    }

    /**
     * تحسين صياغة السؤال فقط.
     */
    fun buildStyleRefinementPrompt(
        userQuery: String,
        style: QueryStyle
    ): String = style.buildPrompt(userQuery)
}
