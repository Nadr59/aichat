package com.example.aichat.data.local

import android.content.Context

class AiSettings(context: Context) {

    private val prefs = context.getSharedPreferences(
        "ai_settings",
        Context.MODE_PRIVATE
    )

    // ============================================================
    // Provider
    // ============================================================

    var provider: String
        get() = prefs.getString("provider", "gemini")
            ?.trim()
            ?.lowercase()
            ?: "gemini"
        set(value) = prefs.edit()
            .putString("provider", value.trim().lowercase())
            .apply()

    // ============================================================
    // Gemini
    // ============================================================

    var geminiKey: String
        get() = prefs.getString("gemini_key", "") ?: ""
        set(value) = prefs.edit()
            .putString("gemini_key", value)
            .apply()

    var geminiModel: String
        get() = prefs.getString("gemini_model", "gemini-3.6-flash")?.trim() ?: "gemini-3.6-flash"
        set(value) = prefs.edit()
            .putString("gemini_model", value.trim())
            .apply()

    // ============================================================
    // OpenRouter
    // ============================================================

    var openrouterKey: String
        get() = prefs.getString("openrouter_key", "") ?: ""
        set(value) = prefs.edit()
            .putString("openrouter_key", value)
            .apply()

    var openrouterModel: String
        get() = prefs.getString("openrouter_model", "google/gemini-2.5-flash:free")?.trim()
            ?: "google/gemini-2.5-flash:free"
        set(value) = prefs.edit()
            .putString("openrouter_model", value.trim())
            .apply()

    // ============================================================
    // OpenAI
    // ============================================================

    var openaiKey: String
        get() = prefs.getString("openai_key", "") ?: ""
        set(value) = prefs.edit()
            .putString("openai_key", value.trim())
            .apply()

    var openaiModel: String
        get() = prefs.getString("openai_model", "gpt-4o-mini")?.trim() ?: "gpt-4o-mini"
        set(value) = prefs.edit()
            .putString("openai_model", value.trim())
            .apply()

    // ============================================================
    // Mistral
    // ============================================================

    var mistralKey: String
        get() = prefs.getString("mistral_key", "") ?: ""
        set(value) = prefs.edit()
            .putString("mistral_key", value)
            .apply()

    var mistralModel: String
        get() = prefs.getString("mistral_model", "pixtral-12b-2409")?.trim() ?: "pixtral-12b-2409"
        set(value) = prefs.edit()
            .putString("mistral_model", value.trim())
            .apply()

    // ============================================================
    // Groq
    // ============================================================

    var groqKey: String
        get() = prefs.getString("groq_key", "") ?: ""
        set(value) = prefs.edit()
            .putString("groq_key", value)
            .apply()

    var groqModel: String
        get() = prefs.getString("groq_model", "llama-3.3-70b-versatile")?.trim()
            ?: "llama-3.3-70b-versatile"
        set(value) = prefs.edit()
            .putString("groq_model", value.trim())
            .apply()

    // ============================================================
    // NVIDIA NIM
    // ============================================================

    var nvidiaKey: String
        get() = prefs.getString("nvidia_key", "") ?: ""
        set(value) = prefs.edit()
            .putString("nvidia_key", value.trim())
            .apply()

    var nvidiaModel: String
        get() {
            val saved = prefs.getString("nvidia_model", "")?.trim().orEmpty()

            if (
                saved.isBlank() ||
                saved.equals("nvidia/nemotron-3-nano-30b-a3b", ignoreCase = true)
            ) {
                val replacement = "nvidia/llama-3.3-nemotron-super-49b-v1"
                prefs.edit()
                    .putString("nvidia_model", replacement)
                    .apply()
                return replacement
            }

            return saved
        }
        set(value) = prefs.edit()
            .putString("nvidia_model", value.trim())
            .apply()

    // ============================================================
    // Hugging Face
    // ============================================================

    var huggingfaceKey: String
        get() = prefs.getString("hf_key", "") ?: ""
        set(value) = prefs.edit()
            .putString("hf_key", value.trim())
            .apply()

    var huggingfaceModel: String
        get() = prefs.getString("hf_model", "mistralai/Mistral-7B-Instruct-v0.3")?.trim()
            ?: "mistralai/Mistral-7B-Instruct-v0.3"
        set(value) = prefs.edit()
            .putString("hf_model", value.trim())
            .apply()

    // ============================================================
    // Ollama
    // ============================================================

    var ollamaModel: String
        get() = prefs.getString("ollama_model", "qwen2.5:1.5b")?.trim() ?: "qwen2.5:1.5b"
        set(value) = prefs.edit()
            .putString("ollama_model", value.trim())
            .apply()

    // ============================================================
    // Custom
    // ============================================================

    var customUrl: String
        get() = prefs.getString("custom_url", "") ?: ""
        set(value) = prefs.edit()
            .putString("custom_url", value.trim())
            .apply()

    var customKey: String
        get() = prefs.getString("custom_key", "") ?: ""
        set(value) = prefs.edit()
            .putString("custom_key", value)
            .apply()

    var customModel: String
        get() = prefs.getString("custom_model", "")?.trim() ?: ""
        set(value) = prefs.edit()
            .putString("custom_model", value.trim())
            .apply()

    // ============================================================
    // Accuracy / الدقة
    // ============================================================

    var accuracyPromptEnabled: Boolean
        get() = prefs.getBoolean("accuracy_prompt_enabled", true)
        set(value) = prefs.edit()
            .putBoolean("accuracy_prompt_enabled", value)
            .apply()

    var customSystemInstruction: String
        get() = prefs.getString("custom_system_instruction", "") ?: ""
        set(value) = prefs.edit()
            .putString("custom_system_instruction", value)
            .apply()

    // ============================================================
    // Memory / الذاكرة
    // ============================================================

    var autoSave: Boolean
        get() = prefs.getBoolean("auto_save", false)
        set(value) = prefs.edit()
            .putBoolean("auto_save", value)
            .apply()

    // ============================================================
    // Memory Curator / وسيط الذاكرة الذكي
    // ============================================================

    var memoryCuratorEnabled: Boolean
        get() = prefs.getBoolean("memory_curator_enabled", true)
        set(value) = prefs.edit()
            .putBoolean("memory_curator_enabled", value)
            .apply()

    /**
     * مزوّد الوسيط الذكي.
     *
     * النموذج لا يُحفظ هنا بشكل مستقل.
     * يتم أخذه ديناميكيًا من إعدادات النموذج الخاصة بالمزوّد.
     */
    var memoryCuratorProvider: String
        get() = prefs.getString("memory_curator_provider", "gemini")
            ?.trim()
            ?.lowercase()
            ?: "gemini"
        set(value) = prefs.edit()
            .putString("memory_curator_provider", value.trim().lowercase())
            .apply()

    fun getMemoryCuratorModel(): String {
        return when (memoryCuratorProvider) {
            "gemini" -> geminiModel
            "groq" -> groqModel
            "mistral" -> mistralModel
            "ollama" -> ollamaModel
            "custom" -> customModel
            else -> ""
        }.trim()
    }

    /**
     * نص هوية/أسلوب الوسيط — حقل حر اختياري.
     */
    var mediatorIdentityText: String
        get() = prefs.getString("mediator_identity_text", "") ?: ""
        set(value) = prefs.edit()
            .putString("mediator_identity_text", value.trim())
            .apply()

    // ============================================================
    // Web / الويب
    // ============================================================

    var webPlatform: String
        get() = prefs.getString("web_platform", "venice") ?: "venice"
        set(value) = prefs.edit()
            .putString("web_platform", value.trim())
            .apply()

    /**
     * ✅ Web Agent مستقل عن محادثات الـ API
     * يخزن ID من جدول agents.
     *
     * ملاحظة: القيمة الفارغة "" تعني "بدون وكيل".
     */
    var webAgentId: String
        get() = prefs.getString("web_agent_id", "")?.trim().orEmpty()
        set(value) = prefs.edit()
            .putString("web_agent_id", value.trim())
            .apply()

    /**
     * ✅ Helper: null إذا لا يوجد وكيل
     */
    val webAgentIdOrNull: String?
        get() = webAgentId.takeIf { it.isNotBlank() }

    // ============================================================
    // Helpers
    // ============================================================

    fun getActiveKey(): String {
        return when (provider) {
            "gemini" -> geminiKey
            "openrouter" -> openrouterKey
            "openai" -> openaiKey
            "mistral" -> mistralKey
            "groq" -> groqKey
            "nvidia" -> nvidiaKey
            "huggingface", "hugging face", "hf" -> huggingfaceKey
            "ollama" -> ""
            "custom" -> customKey
            else -> ""
        }
    }

    fun getActiveModel(): String {
        return when (provider) {
            "gemini" -> geminiModel
            "openrouter" -> openrouterModel
            "openai" -> openaiModel
            "mistral" -> mistralModel
            "groq" -> groqModel
            "nvidia" -> nvidiaModel
            "huggingface", "hugging face", "hf" -> huggingfaceModel
            "ollama" -> ollamaModel
            "custom" -> customModel
            else -> ""
        }
    }

    fun isConfigured(): Boolean {
        return when (provider) {
            "ollama" -> ollamaModel.isNotBlank()
            "custom" -> customUrl.isNotBlank()
            else -> getActiveKey().isNotBlank()
        }
    }

    // ============================================================
    // ✅ إضافة جديدة — مُبدّل المزودين السريع (قائمة API في ChatScreen)
    //
    // إضافات فقط — لم يُمَسّ أي شيء أعلاه.
    //
    // الغرض: شريحة واحدة في شريط المنصات تعرض المزود النشط، وفتحها
    // يعرض المزودين "المُهيّأين" فقط (من لديهم مفتاح/تهيئة محفوظة)
    // للتبديل الفوري بينهم دون دخول الإعدادات.
    //
    // قواعد التهيئة لكل مزود:
    // - مزودو المفاتيح (gemini..huggingface): مفتاح غير فارغ + نموذج غير فارغ.
    // - ollama: محلي بلا مفتاح — يكفي نموذج غير فارغ.
    // - custom: بعض الخوادم لا تتطلب اسم نموذج — يكفي customUrl غير فارغ
    //   (استثناء موثَّق ومعتمَد).
    // ============================================================

    /**
     * اسم عرض موحّد لأي مزود (يُستخدم في شريحة الشريط والقائمة والـToast).
     */
    fun getProviderLabel(providerId: String): String {
        return when (providerId.trim().lowercase()) {
            "gemini" -> "Gemini"
            "openrouter" -> "OpenRouter"
            "openai" -> "OpenAI"
            "mistral" -> "Mistral"
            "groq" -> "Groq"
            "nvidia" -> "NVIDIA NIM"
            "huggingface", "hugging face", "hf" -> "Hugging Face"
            "ollama" -> "Ollama"
            "custom" -> "Custom"
            else -> providerId.trim()
        }
    }

    /**
     * قائمة المزودين المُهيّأين فعليًا (لديهم ما يلزم للإرسال)،
     * بالترتيب الثابت نفسه المتبع في الإعدادات.
     */
    fun getConfiguredApiProviders(): List<ApiProviderInfo> {
        val list = mutableListOf<ApiProviderInfo>()

        if (geminiKey.isNotBlank() && geminiModel.isNotBlank()) {
            list.add(ApiProviderInfo("gemini", getProviderLabel("gemini"), geminiModel))
        }
        if (openrouterKey.isNotBlank() && openrouterModel.isNotBlank()) {
            list.add(ApiProviderInfo("openrouter", getProviderLabel("openrouter"), openrouterModel))
        }
        if (openaiKey.isNotBlank() && openaiModel.isNotBlank()) {
            list.add(ApiProviderInfo("openai", getProviderLabel("openai"), openaiModel))
        }
        if (mistralKey.isNotBlank() && mistralModel.isNotBlank()) {
            list.add(ApiProviderInfo("mistral", getProviderLabel("mistral"), mistralModel))
        }
        if (groqKey.isNotBlank() && groqModel.isNotBlank()) {
            list.add(ApiProviderInfo("groq", getProviderLabel("groq"), groqModel))
        }
        if (nvidiaKey.isNotBlank() && nvidiaModel.isNotBlank()) {
            list.add(ApiProviderInfo("nvidia", getProviderLabel("nvidia"), nvidiaModel))
        }
        if (huggingfaceKey.isNotBlank() && huggingfaceModel.isNotBlank()) {
            list.add(
                ApiProviderInfo("huggingface", getProviderLabel("huggingface"), huggingfaceModel)
            )
        }
        if (ollamaModel.isNotBlank()) {
            list.add(ApiProviderInfo("ollama", getProviderLabel("ollama"), ollamaModel))
        }
        if (customUrl.isNotBlank()) {
            list.add(ApiProviderInfo("custom", getProviderLabel("custom"), customModel))
        }

        return list
    }

    /**
     * يجعل المزود الممرَّر هو النشط ويحفظه فورًا في SharedPreferences،
     * ثم يُرجع نموذجه المحفوظ (لعرضه في Toast التأكيد وعنوان الشريط).
     *
     * ملاحظة: لا تُستدعى إلا بمزود موجود في getConfiguredApiProviders()،
     * لكنها لا تفترض ذلك — تقرأ النموذج عبر getActiveModel() المجرّبة.
     */
    fun activateApiProvider(providerId: String): String {
        provider = providerId
        return getActiveModel()
    }
}

/**
 * وصف عنصر واحد في قائمة مُبدّل المزودين السريع.
 * data class خارج الـclass — مستوى top-level في نفس الملف.
 */
data class ApiProviderInfo(
    val provider: String,
    val label: String,
    val model: String
)
