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
