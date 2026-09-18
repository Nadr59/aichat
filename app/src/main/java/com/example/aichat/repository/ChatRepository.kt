package com.example.aichat.repository

import android.content.Context
import com.example.aichat.data.local.AiSettings
import com.example.aichat.data.local.SystemPrompt
import com.example.aichat.data.model.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.TimeUnit

class ChatRepository(context: Context) {

    val settings = AiSettings(context)

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    // ── مزودو الذكاء الاصطناعي ───────────────────────────────────────────────

    private val geminiProvider = GeminiProvider(
        settings = settings,
        client   = client
    )

    private val mistralProvider = MistralProvider(
        settings = settings,
        client   = client
    )

    private val openAICompatibleProvider = OpenAICompatibleProvider(client)

    private val ollamaProvider = OllamaProvider(
        settings = settings,
        client   = client
    )

    private val huggingFaceProvider = HuggingFaceProvider(
        settings = settings,
        client   = client
    )

    private val modelCatalogRepository = ModelCatalogRepository(settings)

    // ── عداد طلبات Custom ────────────────────────────────────────────────────

    private val _customRequestCount = MutableStateFlow(0)
    val customRequestCount: StateFlow<Int> = _customRequestCount.asStateFlow()

    // ============================================================
    // بناء System Prompt
    // ============================================================

    /**
     * يبني System Prompt من:
     * 1. وثيقة الدقة (إذا كانت مفعّلة)
     * 2. التعليمات المخصصة من الإعدادات
     * 3. سياق الذاكرة من الـ RAG
     */
    private fun buildSystemPrompt(memoryContext: String): String =
        SystemPrompt.build(
            memoryContext     = memoryContext,
            customInstruction = settings.customSystemInstruction,
            includeAccuracy   = settings.accuracyPromptEnabled
        )

    // ============================================================
    // إعدادات المحادثة
    // ============================================================

    private fun getMaxHistory(provider: String, model: String): Int = when {
        provider == "groq"                                          -> 6
        provider == "mistral"                                       -> 6
        provider == "openrouter" && model.contains(":free")        -> 6
        else                                                        -> 10
    }

    private fun getMaxTokens(provider: String, model: String): Int = when {
        provider == "groq"                                          -> 2048
        provider == "mistral"                                       -> 2048
        provider == "openrouter" && model.contains(":free")        -> 2048
        else                                                        -> 4096
    }

    // ============================================================
    // جلب النماذج
    // ============================================================

    suspend fun getAvailableModels(
        provider:  String,
        forImages: Boolean = false
    ) = withContext(Dispatchers.IO) {

        val apiKey = when (provider.lowercase().trim()) {
            "gemini"                       -> settings.geminiKey
            "openrouter"                   -> settings.openrouterKey
            "openai"                       -> settings.openaiKey
            "mistral"                      -> settings.mistralKey
            "groq"                         -> settings.groqKey
            "nvidia"                       -> settings.nvidiaKey
            "huggingface", "hugging face",
            "hf"                           -> settings.huggingfaceKey
            else                           -> ""
        }

        modelCatalogRepository.getModels(provider = provider, apiKey = apiKey)
    }

    // ============================================================
    // إرسال الرسالة — System Prompt يُمرَّر لكل مزود
    // ============================================================

    suspend fun sendMessage(
        history:       List<Message>,
        userMessage:   String,
        imageBase64:   String? = null,
        memoryContext: String  = ""
    ): String = withContext(Dispatchers.IO) {

        // ✅ بناء System Prompt مرة واحدة لكل الطلبات
        val systemPrompt = buildSystemPrompt(memoryContext)

        when (settings.provider.lowercase().trim()) {

            // ── Gemini ────────────────────────────────────────────────────
            "gemini" -> geminiProvider.send(
                history       = history,
                userMessage   = userMessage,
                imageBase64   = imageBase64,
                memoryContext = memoryContext,
                systemPrompt  = systemPrompt
            )

            // ── OpenRouter ────────────────────────────────────────────────
            "openrouter" -> openAICompatibleProvider.send(
                baseUrl      = "https://openrouter.ai/api/v1/chat/completions",
                apiKey       = settings.openrouterKey,
                model        = settings.openrouterModel,
                history      = history,
                userMessage  = userMessage,
                imageBase64  = imageBase64,
                providerName = "OpenRouter",
                maxHistory   = getMaxHistory("openrouter", settings.openrouterModel),
                maxTokens    = getMaxTokens("openrouter", settings.openrouterModel),
                memoryContext = memoryContext,
                systemPrompt  = systemPrompt,
                extraHeaders  = mapOf(
                    "HTTP-Referer" to "https://github.com/",
                    "X-Title"      to "AiChat"
                )
            )

            // ── OpenAI ────────────────────────────────────────────────────
            "openai" -> openAICompatibleProvider.send(
                baseUrl      = "https://api.openai.com/v1/chat/completions",
                apiKey       = settings.openaiKey,
                model        = settings.openaiModel,
                history      = history,
                userMessage  = userMessage,
                imageBase64  = imageBase64,
                providerName = "OpenAI",
                maxHistory   = getMaxHistory("openai", settings.openaiModel),
                maxTokens    = getMaxTokens("openai", settings.openaiModel),
                memoryContext = memoryContext,
                systemPrompt  = systemPrompt
            )

            // ── Mistral ───────────────────────────────────────────────────
            "mistral" -> mistralProvider.send(
                history       = history,
                userMessage   = userMessage,
                imageBase64   = imageBase64,
                memoryContext = memoryContext,
                systemPrompt  = systemPrompt
            )

            // ── Hugging Face ──────────────────────────────────────────────
            "huggingface" -> huggingFaceProvider.send(
                history       = history,
                userMessage   = userMessage,
                memoryContext = memoryContext,
                systemPrompt  = systemPrompt
            )

            // ── Groq ──────────────────────────────────────────────────────
            "groq" -> openAICompatibleProvider.send(
                baseUrl      = "https://api.groq.com/openai/v1/chat/completions",
                apiKey       = settings.groqKey,
                model        = settings.groqModel,
                history      = history,
                userMessage  = userMessage,
                imageBase64  = imageBase64,
                providerName = "Groq",
                maxHistory   = getMaxHistory("groq", settings.groqModel),
                maxTokens    = getMaxTokens("groq", settings.groqModel),
                memoryContext = memoryContext,
                systemPrompt  = systemPrompt
            )

            // ── NVIDIA ────────────────────────────────────────────────────
            "nvidia" -> openAICompatibleProvider.send(
                baseUrl      = "https://integrate.api.nvidia.com/v1/chat/completions",
                apiKey       = settings.nvidiaKey,
                model        = settings.nvidiaModel,
                history      = history,
                userMessage  = userMessage,
                imageBase64  = imageBase64,
                providerName = "NVIDIA",
                maxHistory   = getMaxHistory("nvidia", settings.nvidiaModel),
                maxTokens    = getMaxTokens("nvidia", settings.nvidiaModel),
                memoryContext = memoryContext,
                systemPrompt  = systemPrompt
            )

            // ── Ollama ────────────────────────────────────────────────────
            "ollama" -> ollamaProvider.send(
                userMessage   = userMessage,
                memoryContext = memoryContext,
                systemPrompt  = systemPrompt
            )

            // ── Custom ────────────────────────────────────────────────────
            "custom" -> {
                val url = settings.customUrl.trim()
                if (url.isBlank()) throw IOException("Custom: رابط الخادم فارغ")

                openAICompatibleProvider.send(
                    baseUrl      = url,
                    apiKey       = settings.customKey,
                    model        = settings.customModel,
                    history      = history,
                    userMessage  = userMessage,
                    imageBase64  = imageBase64,
                    providerName = "Custom",
                    maxHistory   = getMaxHistory("custom", settings.customModel),
                    maxTokens    = getMaxTokens("custom", settings.customModel),
                    memoryContext = memoryContext,
                    systemPrompt  = systemPrompt,
                    forceVision   = true,
                    onCustomRequest = { _customRequestCount.update { it + 1 } }
                )
            }

            // ── مزود غير معروف ────────────────────────────────────────────
            else -> throw IOException("مزود غير معروف: ${settings.provider}")
        }
    }
}
