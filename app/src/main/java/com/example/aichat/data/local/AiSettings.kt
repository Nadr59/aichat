package com.example.aichat.data.local

import android.content.Context

class AiSettings(context: Context) {

    private val prefs = context.getSharedPreferences(
        "ai_settings",
        Context.MODE_PRIVATE
    )

    var provider: String
        get() = prefs.getString("provider", "gemini")
            ?.trim()
            ?.lowercase()
            ?: "gemini"
        set(value) = prefs.edit()
            .putString("provider", value.trim().lowercase())
            .apply()

    // Gemini

    var geminiKey: String
        get() = prefs.getString("gemini_key", "") ?: ""
        set(value) = prefs.edit()
            .putString("gemini_key", value)
            .apply()

    var geminiModel: String
        get() = prefs.getString(
            "gemini_model",
            "gemini-3.6-flash"
        )?.trim() ?: "gemini-3.6-flash"
        set(value) = prefs.edit()
            .putString("gemini_model", value.trim())
            .apply()

    // OpenRouter

    var openrouterKey: String
        get() = prefs.getString("openrouter_key", "") ?: ""
        set(value) = prefs.edit()
            .putString("openrouter_key", value)
            .apply()

    var openrouterModel: String
        get() = prefs.getString(
            "openrouter_model",
            "google/gemini-2.5-flash:free"
        )?.trim() ?: "google/gemini-2.5-flash:free"
        set(value) = prefs.edit()
            .putString("openrouter_model", value.trim())
            .apply()

    // OpenAI

    var openaiKey: String
        get() = prefs.getString("openai_key", "") ?: ""
        set(value) = prefs.edit()
            .putString("openai_key", value.trim())
            .apply()

    var openaiModel: String
        get() = prefs.getString(
            "openai_model",
            "gpt-4o-mini"
        )?.trim() ?: "gpt-4o-mini"
        set(value) = prefs.edit()
            .putString("openai_model", value.trim())
            .apply()

    // Mistral

    var mistralKey: String
        get() = prefs.getString("mistral_key", "") ?: ""
        set(value) = prefs.edit()
            .putString("mistral_key", value.trim())
            .apply()

    var mistralModel: String
        get() = prefs.getString(
            "mistral_model",
            "pixtral-12b-2409"
        )?.trim() ?: "pixtral-12b-2409"
        set(value) = prefs.edit()
            .putString("mistral_model", value.trim())
            .apply()

    // Groq

    var groqKey: String
        get() = prefs.getString("groq_key", "") ?: ""
        set(value) = prefs.edit()
            .putString("groq_key", value.trim())
            .apply()

    var groqModel: String
        get() = prefs.getString(
            "groq_model",
            "llama-3.3-70b-versatile"
        )?.trim() ?: "llama-3.3-70b-versatile"
        set(value) = prefs.edit()
            .putString("groq_model", value.trim())
            .apply()

    // NVIDIA NIM

    var nvidiaKey: String
        get() = prefs.getString("nvidia_key", "") ?: ""
        set(value) = prefs.edit()
            .putString("nvidia_key", value.trim())
            .apply()

    var nvidiaModel: String
        get() {
            val saved = prefs.getString(
                "nvidia_model",
                ""
            )?.trim().orEmpty()

            if (
                saved.isBlank() ||
                saved.equals(
                    "nvidia/nemotron-3-nano-30b-a3b",
                    ignoreCase = true
                )
            ) {
                val replacement =
                    "nvidia/llama-3.3-nemotron-super-49b-v1"

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

    // Hugging Face

    var huggingfaceKey: String
        get() = prefs.getString("hf_key", "") ?: ""
        set(value) = prefs.edit()
            .putString("hf_key", value.trim())
            .apply()

    var huggingfaceModel: String
        get() = prefs.getString(
            "hf_model",
            "mistralai/Mistral-7B-Instruct-v0.3"
        )?.trim() ?: "mistralai/Mistral-7B-Instruct-v0.3"
        set(value) = prefs.edit()
            .putString("hf_model", value.trim())
            .apply()

    // Ollama

    var ollamaModel: String
        get() = prefs.getString(
            "ollama_model",
            "qwen2.5:1.5b"
        )?.trim() ?: "qwen2.5:1.5b"
        set(value) = prefs.edit()
            .putString("ollama_model", value.trim())
            .apply()

    // Custom

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
        get() = prefs.getString("custom_model", "")
            ?.trim()
            ?: ""
        set(value) = prefs.edit()
            .putString("custom_model", value.trim())
            .apply()

    // تفعيل وثيقة الدقة
    var accuracyPromptEnabled: Boolean
        get() = prefs.getBoolean("accuracy_prompt_enabled", true)
        set(value) = prefs.edit().putBoolean("accuracy_prompt_enabled", value).apply()

    // تعليمات مخصصة إضافية
    var customSystemInstruction: String
        get() = prefs.getString("custom_system_instruction", "") ?: ""
        set(value) = prefs.edit().putString("custom_system_instruction", value).apply()

    // ── Memory Curator (وسيط الذاكرة الذكي) ──────────────────────────

    var memoryCuratorEnabled: Boolean
        get() = prefs.getBoolean("memory_curator_enabled", true)
        set(value) = prefs.edit()
            .putBoolean("memory_curator_enabled", value)
            .apply()

    var memoryCuratorModel: String
        get() = prefs.getString(
            "memory_curator_model",
            "gemini-3.6-flash"
        )?.trim() ?: "gemini-3.6-flash"
        set(value) = prefs.edit()
            .putString("memory_curator_model", value.trim())
            .apply()

    /**
     * مزوّد الوسيط الذكي — مستقل تماماً عن [provider] الرئيسي المستخدَم
     * في المحادثة. يسمح للمستخدم بتبديل الوسيط إلى مزوّد آخر (مثل "custom")
     * دون التأثير على مزوّد المحادثة الأساسي، ودون أي تعديل كود مستقبلي —
     * فقط تبديل هذه القيمة من واجهة الإعدادات.
     *
     * القيم المدعومة حالياً: "gemini" (افتراضي، يحافظ على السلوك القديم)
     * و "custom" (يعيد استخدام customUrl/customKey/customModel الموجودة
     * أصلاً، بلا ازدواج إعدادات).
     */
    var memoryCuratorProvider: String
        get() = prefs.getString("memory_curator_provider", "gemini")
            ?.trim()
            ?.lowercase()
            ?: "gemini"
        set(value) = prefs.edit()
            .putString("memory_curator_provider", value.trim().lowercase())
            .apply()

    /**
     * 🆕 نص هوية/أسلوب الوسيط — حقل حر اختياري.
     *
     * - فارغ (الافتراضي): الوسيط يعمل بنفس سلوكه الأصلي تماماً
     *   (تنقية سياق الذاكرة فقط، بلا أي تغيير سلوكي أو كسر توافق).
     * - غير فارغ: يتحول دور الوسيط أيضاً إلى "محسّن طلبات" يقترح نقاط
     *   محتوى ملموسة تخدم هذا الوصف، ضمن نفس استدعاء LLM الواحد
     *   (لا يضيف أي استدعاء شبكة إضافي).
     *
     * حماية مهمة: هذا النص لا يُستخدَم أبداً لإعادة صياغة سؤال المستخدم
     * نفسه — فقط يُوجّه الوسيط لإنتاج توجيه/سياق مرافق. يُنفَّذ هذا الشرط
     * داخل MemoryCuratorPrompt.build()، وليس في هذا الملف.
     */
    var mediatorIdentityText: String
        get() = prefs.getString("mediator_identity_text", "") ?: ""
        set(value) = prefs.edit()
            .putString("mediator_identity_text", value.trim())
            .apply()

    // Web

    var webPlatform: String
        get() = prefs.getString("web_platform", "venice") ?: "venice"
        set(value) = prefs.edit()
            .putString("web_platform", value.trim())
            .apply()

    // Helpers

    fun getActiveKey(): String {
        return when (provider) {
            "gemini" -> geminiKey
            "openrouter" -> openrouterKey
            "openai" -> openaiKey
            "mistral" -> mistralKey
            "groq" -> groqKey
            "nvidia" -> nvidiaKey

            "huggingface",
            "hugging face",
            "hf" -> huggingfaceKey

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

            "huggingface",
            "hugging face",
            "hf" -> huggingfaceModel

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
}
