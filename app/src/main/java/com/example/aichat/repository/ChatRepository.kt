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

    private val _customRequestCount = MutableStateFlow(0)
    val customRequestCount: StateFlow<Int> = _customRequestCount.asStateFlow()

    private val providers: Map<String, AiProvider> = AiProviderFactory.create(
        settings        = settings,
        client          = client,
        onCustomRequest = { _customRequestCount.update { it + 1 } }
    )

    private val modelCatalogRepository = ModelCatalogRepository(settings)

    // ── بناء System Prompt ────────────────────────────────────────────────────

    private fun buildSystemPrompt(memoryContext: String): String =
        SystemPrompt.build(
            memoryContext     = memoryContext,
            customInstruction = settings.customSystemInstruction,
            includeAccuracy   = settings.accuracyPromptEnabled
        )

    // ── جلب النماذج ───────────────────────────────────────────────────────────

    suspend fun getAvailableModels(
        provider:  String,
        forImages: Boolean = false
    ) = withContext(Dispatchers.IO) {
        val apiKey = when (provider.lowercase().trim()) {
            "gemini"      -> settings.geminiKey
            "openrouter"  -> settings.openrouterKey
            "openai"      -> settings.openaiKey
            "mistral"     -> settings.mistralKey
            "groq"        -> settings.groqKey
            "nvidia"      -> settings.nvidiaKey
            "huggingface" -> settings.huggingfaceKey
            else          -> ""
        }
        modelCatalogRepository.getModels(provider = provider, apiKey = apiKey)
    }

    // ── إرسال الرسالة ─────────────────────────────────────────────────────────

    suspend fun sendMessage(
        history:       List<Message>,
        userMessage:   String,
        imageBase64:   String? = null,
        memoryContext: String  = ""
    ): String = withContext(Dispatchers.IO) {

        val providerId = settings.provider.lowercase().trim()

        val provider = providers[providerId]
            ?: throw IOException("مزود غير معروف: $providerId")

        val request = AiRequest(
            history      = history,
            userMessage  = userMessage,
            imageBase64  = imageBase64,
            systemPrompt = buildSystemPrompt(memoryContext)
        )

        provider.send(request)
    }
}
