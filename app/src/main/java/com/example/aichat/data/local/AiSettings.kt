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
        set(value) = prefs.edit().putString("provider", value.trim()).apply()

    // ============================================================
    // مزود توليد الصور
    // ============================================================

    var imageProvider: String
        get() = prefs.getString("image_provider", "openrouter")?.trim() ?: "openrouter"
        set(value) = prefs.edit().putString("image_provider", value.trim()).apply()

    var imageModel: String
        get() = prefs.getString("image_model", "black-forest-labs/flux-schnell:free")?.trim()
            ?: "black-forest-labs/flux-schnell:free"
        set(value) = prefs.edit().putString("image_model", value.trim()).apply()

    // مفتاح وURL مخصصان لتوليد الصور
    var customImageUrl: String
        get() = prefs.getString("custom_image_url", "")?.trim() ?: ""
        set(value) = prefs.edit().putString("custom_image_url", value.trim()).apply()

    var customImageKey: String
        get() = prefs.getString("custom_image_key", "") ?: ""
        set(value) = prefs.edit().putString("custom_image_key", value).apply()

    // ============================================================
    // Gemini
    // ============================================================

    var geminiKey: String
        get() = prefs.getString("gemini_key", "") ?: ""
        set(value) = prefs.edit().putString("gemini_key", value).apply()

    var geminiModel: String
        get() = prefs.getString("gemini_model", "gemini-3.6-flash")?.trim() ?: "gemini-3.6-flash"
        set(value) = prefs.edit().putString("gemini_model", value.trim()).apply()

    // ============================================================
    // OpenRouter
    // ============================================================

    var openrouterKey: String
        get() = prefs.getString("openrouter_key", "") ?: ""
        set(value) = prefs.edit().putString("openrouter_key", value).apply()

    var openrouterModel: String
        get() = prefs.getString("openrouter_model", "google/gemini-2.5-flash")?.trim()
            ?: "google/gemini-2.5-flash"
        set(value) = prefs.edit().putString("openrouter_model", value.trim()).apply()

    // ============================================================
    // OpenAI
    // ============================================================

    var openaiKey: String
        get() = prefs.getString("openai_key", "") ?: ""
        set(value) = prefs.edit().putString("openai_key", value).apply()

    var openaiModel: String
        get() = prefs.getString("openai_model", "gpt-4o-mini")?.trim() ?: "gpt-4o-mini"
        set(value) = prefs.edit().putString("openai_model", value.trim()).apply()

    // ============================================================
    // Mistral
    // ============================================================

    var mistralKey: String
        get() = prefs.getString("mistral_key", "") ?: ""
        set(value) = prefs.edit().putString("mistral_key", value).apply()

    var mistralModel: String
        get() = prefs.getString("mistral_model", "pixtral-large-2411")?.trim()
            ?: "pixtral-large-2411"
        set(value) = prefs.edit().putString("mistral_model", value.trim()).apply()

    // ============================================================
    // Groq
    // ============================================================

    var groqKey: String
        get() = prefs.getString("groq_key", "") ?: ""
        set(value) = prefs.edit().putString("groq_key", value).apply()

    var groqModel: String
        get() = prefs.getString("groq_model", "")?.trim() ?: ""
        set(value) = prefs.edit().putString("groq_model", value.trim()).apply()

    // ============================================================
    // Custom — محادثة
    // ============================================================

    var customUrl: String
        get() = prefs.getString("custom_url", "") ?: ""
        set(value) = prefs.edit().putString("custom_url", value).apply()

    var customKey: String
        get() = prefs.getString("custom_key", "") ?: ""
        set(value) = prefs.edit().putString("custom_key", value).apply()

    var customModel: String
        get() = prefs.getString("custom_model", "")?.trim() ?: ""
        set(value) = prefs.edit().putString("custom_model", value.trim()).apply()

    // ============================================================
    // Helpers
    // ============================================================

    fun getActiveKey(): String {
        return when (provider) {
            "gemini"     -> geminiKey
            "openrouter" -> openrouterKey
            "openai"     -> openaiKey
            "mistral"    -> mistralKey
            "groq"       -> groqKey
            "custom"     -> customKey
            else         -> ""
        }
    }

    fun getActiveModel(): String {
        return when (provider) {
            "gemini"     -> geminiModel
            "openrouter" -> openrouterModel
            "openai"     -> openaiModel
            "mistral"    -> mistralModel
            "groq"       -> groqModel
            "custom"     -> customModel
            else         -> ""
        }
    }

    fun isConfigured(): Boolean = getActiveKey().isNotBlank()
}
