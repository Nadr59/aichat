package com.example.aichat.data.local

import android.content.Context

class AiSettings(context: Context) {

    private val prefs = context.getSharedPreferences(
        "ai_settings",
        Context.MODE_PRIVATE
    )

    // ============================================================
    // المزود الرئيسي
    // ============================================================

    var provider: String
        get() = prefs.getString("provider", "gemini")?.trim() ?: "gemini"
        set(value) = prefs.edit()
            .putString("provider", value.trim())
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
        get() = prefs.getString(
            "gemini_model",
            "gemini-3.6-flash"
        )?.trim() ?: "gemini-3.6-flash"
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
        get() = prefs.getString(
            "openrouter_model",
            "google/gemini-2.5-flash:free"
        )?.trim() ?: "google/gemini-2.5-flash:free"
        set(value) = prefs.edit()
            .putString("openrouter_model", value.trim())
            .apply()

    // ============================================================
    // OpenAI
    // ============================================================

    var openaiKey: String
        get() = prefs.getString("openai_key", "") ?: ""
        set(value) = prefs.edit()
            .putString("openai_key", value)
            .apply()

    var openaiModel: String
        get() = prefs.getString(
            "openai_model",
            "gpt-4o-mini"
        )?.trim() ?: "gpt-4o-mini"
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
        get() = prefs.getString(
            "mistral_model",
            "pixtral-12b-2409"
        )?.trim() ?: "pixtral-12b-2409"
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
        get() = prefs.getString(
            "groq_model",
            "llama-3.3-70b-versatile"
        )?.trim() ?: "llama-3.3-70b-versatile"
        set(value) = prefs.edit()
            .putString("groq_model", value.trim())
            .apply()

    // ============================================================
    // NVIDIA NIM
    // ============================================================

    var nvidiaKey: String
        get() = prefs.getString("nvidia_key", "") ?: ""
        set(value) = prefs.edit()
            .putString("nvidia_key", value)
            .apply()

    var nvidiaModel: String
        get() = prefs.getString(
            "nvidia_model",
            "nvidia/nemotron-3-nano-30b-a3b"
        )?.trim() ?: "nvidia/nemotron-3-nano-30b-a3b"
        set(value) = prefs.edit()
            .putString("nvidia_model", value.trim())
            .apply()

    // ============================================================
    // Hugging Face
    // ============================================================

    var huggingfaceKey: String
        get() = prefs.getString("hf_key", "") ?: ""
        set(value) = prefs.edit()
            .putString("hf_key", value)
            .apply()

    var huggingfaceModel: String
        get() = prefs.getString(
            "hf_model",
            "mistralai/Mistral-7B-Instruct-v0.3"
        )?.trim() ?: "mistralai/Mistral-7B-Instruct-v0.3"
        set(value) = prefs.edit()
            .putString("hf_model", value.trim())
            .apply()

    // ============================================================
    // AI Horde
    // ============================================================

    var hordeKey: String
        get() = prefs.getString(
            "horde_key",
            "0000000000"
        ) ?: "0000000000"
        set(value) = prefs.edit()
            .putString("horde_key", value)
            .apply()

    var hordeTextModel: String
        get() = prefs.getString(
            "horde_text_model",
            "mistralai/Mistral-7B-Instruct-v0.2"
        )?.trim() ?: "mistralai/Mistral-7B-Instruct-v0.2"
        set(value) = prefs.edit()
            .putString("horde_text_model", value.trim())
            .apply()

    var hordeImageModel: String
        get() = prefs.getString(
            "horde_image_model",
            "Stable Diffusion XL"
        )?.trim() ?: "Stable Diffusion XL"
        set(value) = prefs.edit()
            .putString("horde_image_model", value.trim())
            .apply()

    // ============================================================
    // Custom
    // ============================================================

    var customUrl: String
        get() = prefs.getString("custom_url", "") ?: ""
        set(value) = prefs.edit()
            .putString("custom_url", value)
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
    // توليد الصور
    // ============================================================

    var imageProvider: String
        get() = prefs.getString(
            "image_provider",
            "openai"
        )?.trim() ?: "openai"
        set(value) = prefs.edit()
            .putString("image_provider", value.trim())
            .apply()

    var imageModel: String
        get() = prefs.getString(
            "image_model",
            "dall-e-3"
        )?.trim() ?: "dall-e-3"
        set(value) = prefs.edit()
            .putString("image_model", value.trim())
            .apply()

    var customImageUrl: String
        get() = prefs.getString(
            "custom_image_url",
            ""
        )?.trim() ?: ""
        set(value) = prefs.edit()
            .putString("custom_image_url", value.trim())
            .apply()

    var customImageKey: String
        get() = prefs.getString(
            "custom_image_key",
            ""
        ) ?: ""
        set(value) = prefs.edit()
            .putString("custom_image_key", value)
            .apply()

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
            "huggingface" -> huggingfaceKey
            "horde" -> hordeKey
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
            "huggingface" -> huggingfaceModel
            "horde" -> hordeTextModel
            "custom" -> customModel
            else -> ""
        }
    }

    fun isConfigured(): Boolean {
        return when (provider) {
            "horde" -> true
            "custom" -> customUrl.isNotBlank()
            else -> getActiveKey().isNotBlank()
        }
    }
}
