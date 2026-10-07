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
     * برومبت تحسين الطلبات - توسيع السؤال بناءً على الهوية.
     */
    fun buildEnhancerPrompt(
        userQuery: String,
        mediatorIdentityText: String
    ): String = buildString {

        appendLine("مهمتك: إعادة صياغة سؤال المستخدم التالي ليصبح أوضح وأكثر توجيهاً نحو الأسلوب المطلوب،")
        appendLine("مع الحفاظ الكامل على نيته الأصلية (لا تُغيّر جوهر ما يسأل عنه، فقط اجعل الطلب أغنى وأكثر تحديداً).")
        appendLine()
        appendLine("السؤال الأصلي:")
        appendLine("\"$userQuery\"")
        appendLine()
        appendLine("الأسلوب/الهوية المطلوبة:")
        appendLine("\"$mediatorIdentityText\"")
        appendLine()

        appendLine(
            """
            أمثلة على الإعادة الصياغة الصحيحة (تعلّم منها بالضبط):
            
            ─────────────────────────────────────────────────────────────────
            مثال 1:
            
            أصلي: "اشرح النسبية"
            هوية: "مبرمج يفضل الأمثلة العملية والكود المباشر"
            
            مُحسّن:
            "اشرح النسبية بأسلوب برمجي عملي، مع:
             • مثال كود Python يُحاكي تباطؤ الزمن (time dilation) عند سرعات مختلفة
             • ربط مفهوم الإطارات المرجعية بفكرة الـ coordinate systems في الرسوميات
             • إن ذكرت معادلة E=mc²، اكتب دالة بسيطة تحسبها بمدخلات واضحة"
            
            ─────────────────────────────────────────────────────────────────
            مثال 2:
            
            أصلي: "ما هي الجاذبية؟"
            هوية: "معلم ابتدائي، لغة بسيطة للأطفال"
            
            مُحسّن:
            "اشرح الجاذبية بأسلوب مبسط جداً لطفل عمره 8 سنوات:
             • استخدم مثالاً واحداً ملموساً (مثل: كرة تسقط من يد الطفل)
             • تجنب تماماً أي معادلات رياضية أو رموز
             • اختتم بتشبيه واحد سهل الفهم (مثل: الجاذبية كـ'مغناطيس' غير مرئي)"
            
            ─────────────────────────────────────────────────────────────────
            مثال 3:
            
            أصلي: "شرح العقد الذكي"
            هوية: "محامٍ، التركيز على الجانب القانوني"
            
            مُحسّن:
            "اشرح العقد الذكي من منظور قانوني بحت:
             • الوضع التشريعي الحالي في الأنظمة القانونية المختلفة (مع أمثلة محددة)
             • الفراغات القانونية الموجودة والمخاطر المترتبة
             • التحذير الصريح بضرورة مراجعة مستشار قانوني قبل التنفيذ الفعلي"
            
            ─────────────────────────────────────────────────────────────────
            
            قواعد صارمة يجب اتباعها:
            
            1. لا تُجب على السؤال بنفسك أبداً — مهمتك فقط إعادة صياغته.
            
            2. احتفظ بنية المستخدم الأصلية كاملة:
               - إن سأل عن "النسبية"، لا تحوّله لسؤال عن "الفيزياء الحديثة" عموماً.
               - إن طلب "شرحاً"، لا تحوّله لطلب "تحليل مقارن" إلا إن كانت الهوية تتطلب ذلك صراحة.
            
            3. أضف نقاطاً ملموسة محددة وقابلة للتنفيذ (وليست صفات أسلوب عامة):
               ❌ ضعيف: "أجب بأسلوب تقني" أو "كن رسمياً"
               ✅ قوي: "أضف مثال كود"، "اذكر رقم المادة القانونية"، "استخدم تشبيهاً واحداً بسيطاً"
            
            4. إن كان السؤال لا يحتمل تطبيق هذه الهوية منطقياً (مثل: هوية "مبرمج" + سؤال "أمثال شعبية")،
               أعد السؤال الأصلي كما هو حرفياً دون أي تحسين — لا تحاول التكلّف.
            
            5. أخرج السؤال المُحسّن فقط، بلا:
               - مقدمات ("إليك السؤال المُحسّن:")
               - شرح إضافي عمّا فعلته
               - اعتذار أو تردد
               فقط النص المُحسّن مباشرة.
            """.trimIndent()
        )
    }

    /**
     * تحسين صياغة السؤال فقط.
     */
    fun buildStyleRefinementPrompt(
        userQuery: String,
        style: QueryStyle
    ): String = style.buildPrompt(userQuery)
}
