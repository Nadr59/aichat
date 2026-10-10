package com.example.aichat.repository

import com.example.aichat.data.model.MemoryItem
import com.example.aichat.data.model.QueryStyle

object MemoryCuratorPrompt {

    /**
     * الدالة القديمة لتنقية الذاكرة.
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
     * المرحلة 2:
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
     * ✅ النسخة المحسنة النهائية (Identity-First + Fallback العام)
     * الشرط الأساسي: التقيد بما في مربع الهوية إلى أبعد الحدود.
     * إذا كان السؤال بعيداً عن الهوية -> حسّنه بقواعد عامة مع تنبيه صريح.
     */
    fun buildEnhancerPrompt(
        userQuery: String,
        mediatorIdentityText: String
    ): String = buildString {

        appendLine("أنت Query Enhancer محترف. مهمتك الوحيدة: تحسين وإثراء طلب المستخدم.")
        appendLine()

        // --- 1. معالجة الهوية (الشرط الأهم) ---
        if (mediatorIdentityText.isBlank()) {
            appendLine("حالة الهوية: لا توجد هوية مخصصة.")
            appendLine("المطلوب: قم بتحسين محايد للسؤال بقواعد عامة فقط، ولا تخترع هوية من عندك.")
        } else {
            appendLine("قاعدة الأولوية القصوى (Identity-First):")
            appendLine("يجب أن تتقيد بالهوية التالية إلى أبعد الحدود وتطبقها كقواعد تصميم إلزامية لتحسين الطلب.")
            appendLine("اعتبر كل كلمة في الهوية أمراً واجب التنفيذ.")
            appendLine("الهوية المعتمدة:")
            appendLine("--- بداية الهوية ---")
            appendLine(mediatorIdentityText.trim())
            appendLine("--- نهاية الهوية ---")
            appendLine()
            appendLine("منطق التقيد:")
            appendLine("أ- إذا كان سؤال المستخدم مرتبطاً بالهوية (ولو جزئياً): طبّق الهوية بصرامة 100% في صياغة السؤال والنقاط الخمس.")
            appendLine("ب- إذا كان سؤال المستخدم بعيداً تماماً عن مجال الهوية ولا يمكن تطبيقها منطقياً: لا تفتعل تطبيقها، بل حسّن السؤال بقواعد عامة محايدة، لكن يجب أن تبدأ إخراجك بتنبيه واضح يفيد أن السؤال لا ينتمي للهوية.")
        }
        appendLine()

        appendLine("طلب المستخدم الأصلي:")
        appendLine("\"${userQuery.trim()}\"")
        appendLine()

        appendLine("قواعد ذهبية لا يمكن كسرها إطلاقاً حتى لو طلبت الهوية عكسها:")
        appendLine("1) ممنوع الإجابة على السؤال. مهمتك تحسين السؤال فقط.")
        appendLine("2) ممنوع تغيير موضوع السؤال أو نية المستخدم الأصلية أو تحريفه.")
        appendLine()

        appendLine("قواعد عامة إلزامية:")
        appendLine("- حافظ على نفس لغة طلب المستخدم تماماً (عربي -> عربي، إنجليزي -> إنجليزي).")
        appendLine("- ممنوع كتابة أي ميتا-كلام مثل: (كمساعد ذكاء اصطناعي / إليك الطلب المحسن / سأقوم بتحسين / هذا يتطلب توضيحاً...).")
        appendLine("- لا تكتب أي مقدمات أو عناوين أو شرح لما فعلته خارج القالب المحدد.")
        appendLine("- لا تخترع تفاصيل شخصية عن المستخدم غير مذكورة في سؤاله.")
        appendLine("- النقاط الخمس يجب أن تكون تفاصيل موضوعية تساعد على إجابة أدق (شروط، حدود، أمثلة، معايير، سياق) مستوحاة من الهوية عند الإمكان.")
        appendLine()

        appendLine("صيغة الإخراج الإلزامية:")
        appendLine("الحالة 1 - إذا كان السؤال مرتبطاً بالهوية:")
        appendLine("سؤال: <سؤال/طلب مُحسّن واحد فقط بصيغة استفهام أو أمر مطبق عليه الهوية>")
        appendLine("- <نقطة توضيحية 1>")
        appendLine("- <نقطة توضيحية 2>")
        appendLine("- <نقطة توضيحية 3>")
        appendLine("- <نقطة توضيحية 4>")
        appendLine("- <نقطة توضيحية 5>")
        appendLine()
        appendLine("الحالة 2 - إذا كان السؤال بعيداً عن الهوية:")
        appendLine("تنبيه: هذا الطلب لا ينتمي إلى مجال الهوية المحددة، تم تحسينه بقواعد عامة.")
        appendLine("سؤال: <سؤال/طلب مُحسّن واحد فقط بقواعد عامة>")
        appendLine("- <نقطة توضيحية 1>")
        appendLine("- <نقطة توضيحية 2>")
        appendLine("- <نقطة توضيحية 3>")
        appendLine("- <نقطة توضيحية 4>")
        appendLine("- <نقطة توضيحية 5>")
        appendLine()
        appendLine("مهم جداً: أخرج 5 نقاط بالضبط لا أكثر ولا أقل، ولا تخرج أي نص خارج إحدى الحالتين.")
    }

    /**
     * تحسين صياغة السؤال فقط حسب الأسلوب.
     */
    fun buildStyleRefinementPrompt(
        userQuery: String,
        style: QueryStyle
    ): String = style.buildPrompt(userQuery)
}
