package com.example.aichat.repository

import com.example.aichat.data.local.AiSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.IOException

// ── Gemini ────────────────────────────────────────────────────────────────────

class GeminiAiProvider(
    private val inner: GeminiProvider
) : AiProvider {
    override val id = "gemini"
    override suspend fun send(request: AiRequest): String =
        withContext(Dispatchers.IO) {
            inner.send(
                history      = request.history,
                userMessage  = request.userMessage,
                imageBase64  = request.imageBase64,
                systemPrompt = request.systemPrompt
            )
        }
}

// ── Mistral ───────────────────────────────────────────────────────────────────

class MistralAiProvider(
    private val inner: MistralProvider
) : AiProvider {
    override val id = "mistral"
    override suspend fun send(request: AiRequest): String =
        withContext(Dispatchers.IO) {
            inner.send(
                history      = request.history,
                userMessage  = request.userMessage,
                imageBase64  = request.imageBase64,
                systemPrompt = request.systemPrompt
            )
        }
}

// ── HuggingFace ───────────────────────────────────────────────────────────────

class HuggingFaceAiProvider(
    private val inner: HuggingFaceProvider
) : AiProvider {
    override val id = "huggingface"
    override suspend fun send(request: AiRequest): String =
        withContext(Dispatchers.IO) {
            inner.send(
                history      = request.history,
                userMessage  = request.userMessage,
                systemPrompt = request.systemPrompt
            )
        }
}

// ── Ollama ────────────────────────────────────────────────────────────────────

class OllamaAiProvider(
    private val inner: OllamaProvider
) : AiProvider {
    override val id = "ollama"
    override suspend fun send(request: AiRequest): String =
        withContext(Dispatchers.IO) {
            inner.send(
                userMessage  = request.userMessage,
                systemPrompt = request.systemPrompt
            )
        }
}

// ── OpenAI-Compatible (يغطي 5 مزودين) ────────────────────────────────────────

class OpenAICompatibleAiProvider(
    override val id:      String,
    private val baseUrl:  () -> String,
    private val apiKey:   () -> String,
    private val model:    () -> String,
    private val inner:    OpenAICompatibleProvider,
    private val maxHistory:   (String) -> Int,
    private val maxTokens:    (String) -> Int,
    private val extraHeaders: Map<String, String> = emptyMap(),
    private val forceVision:  Boolean             = false,
    private val onRequest:    (() -> Unit)?       = null
) : AiProvider {
    override suspend fun send(request: AiRequest): String =
        withContext(Dispatchers.IO) {
            val currentModel = model()
            val currentUrl   = baseUrl()
            if (id == "custom" && currentUrl.isBlank())
                throw IOException("Custom: رابط الخادم فارغ")
            inner.send(
                baseUrl         = currentUrl,
                apiKey          = apiKey(),
                model           = currentModel,
                history         = request.history,
                userMessage     = request.userMessage,
                imageBase64     = request.imageBase64,
                providerName    = id,
                maxHistory      = maxHistory(currentModel),
                maxTokens       = maxTokens(currentModel),
                systemPrompt    = request.systemPrompt,
                extraHeaders    = extraHeaders,
                forceVision     = forceVision,
                onCustomRequest = onRequest
            )
        }
}

// ── المصنع ────────────────────────────────────────────────────────────────────

object AiProviderFactory {

    fun create(
        settings:        AiSettings,
        client:          OkHttpClient,
        onCustomRequest: () -> Unit
    ): Map<String, AiProvider> {

        val gemini  = GeminiProvider(settings, client)
        val mistral = MistralProvider(settings, client)
        val ollama  = OllamaProvider(settings, client)
        val hugging = HuggingFaceProvider(settings, client)
        val openAI  = OpenAICompatibleProvider(client)

        fun maxHistory(provider: String, model: String): Int = when {
            provider == "groq"                                   -> 6
            provider == "mistral"                                -> 6
            provider == "openrouter" && model.contains(":free") -> 6
            else                                                 -> 10
        }

        fun maxTokens(provider: String, model: String): Int = when {
            provider == "groq"                                   -> 2048
            provider == "mistral"                                -> 2048
            provider == "openrouter" && model.contains(":free") -> 2048
            else                                                 -> 4096
        }

        return listOf(

            GeminiAiProvider(gemini),
            MistralAiProvider(mistral),
            HuggingFaceAiProvider(hugging),
            OllamaAiProvider(ollama),

            OpenAICompatibleAiProvider(
                id           = "openrouter",
                baseUrl      = { "https://openrouter.ai/api/v1/chat/completions" },
                apiKey       = { settings.openrouterKey },
                model        = { settings.openrouterModel },
                inner        = openAI,
                maxHistory   = { m -> maxHistory("openrouter", m) },
                maxTokens    = { m -> maxTokens("openrouter", m) },
                extraHeaders = mapOf(
                    "HTTP-Referer" to "https://github.com/",
                    "X-Title"      to "AiChat"
                )
            ),

            OpenAICompatibleAiProvider(
                id         = "openai",
                baseUrl    = { "https://api.openai.com/v1/chat/completions" },
                apiKey     = { settings.openaiKey },
                model      = { settings.openaiModel },
                inner      = openAI,
                maxHistory = { m -> maxHistory("openai", m) },
                maxTokens  = { m -> maxTokens("openai", m) }
            ),

            OpenAICompatibleAiProvider(
                id         = "groq",
                baseUrl    = { "https://api.groq.com/openai/v1/chat/completions" },
                apiKey     = { settings.groqKey },
                model      = { settings.groqModel },
                inner      = openAI,
                maxHistory = { m -> maxHistory("groq", m) },
                maxTokens  = { m -> maxTokens("groq", m) }
            ),

            OpenAICompatibleAiProvider(
                id         = "nvidia",
                baseUrl    = { "https://integrate.api.nvidia.com/v1/chat/completions" },
                apiKey     = { settings.nvidiaKey },
                model      = { settings.nvidiaModel },
                inner      = openAI,
                maxHistory = { m -> maxHistory("nvidia", m) },
                maxTokens  = { m -> maxTokens("nvidia", m) }
            ),

            OpenAICompatibleAiProvider(
                id          = "custom",
                baseUrl     = { settings.customUrl.trim() },
                apiKey      = { settings.customKey },
                model       = { settings.customModel },
                inner       = openAI,
                maxHistory  = { m -> maxHistory("custom", m) },
                maxTokens   = { m -> maxTokens("custom", m) },
                forceVision = true,
                onRequest   = onCustomRequest
            )

        ).associateBy { it.id }
    }
}
