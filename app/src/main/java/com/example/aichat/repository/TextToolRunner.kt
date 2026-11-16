package com.example.aichat.repository

import android.content.Context
import com.example.aichat.data.local.AiSettings
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * يشغّل تعليمة واحدة (ترجمة أو وكيل) على نص عبر المزوّد المختار حاليًا في إعدادات AiChat.
 * لا يحفظ شيئًا ولا يستعمل الذاكرة ولا الدقة ولا التعليمات المخصصة.
 */
class TextToolRunner(context: Context) {

    private val settings = AiSettings(context.applicationContext)

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val providers: Map<String, AiProvider> = AiProviderFactory.create(
        settings = settings,
        client = client,
        onCustomRequest = {}
    )

    /** "Gemini · gemini-…" أو null إن لم يُضبط مزوّد. */
    fun providerInfo(): String? {
        if (!settings.isConfigured()) return null
        val model = settings.getActiveModel()
        val label = settings.getProviderLabel(settings.provider)
        return if (model.isBlank()) label else "$label · $model"
    }

    suspend fun run(systemPrompt: String, text: String): String {
        if (!settings.isConfigured()) {
            throw IOException("لم يُضبط مزوّد API في إعدادات AiChat.")
        }
        val id = when (val p = settings.provider.lowercase().trim()) {
            "hugging face", "hf" -> "huggingface"
            else -> p
        }
        val provider = providers[id] ?: throw IOException("مزوّد غير معروف: $id")

        val out = provider.send(
            AiRequest(userMessage = text, systemPrompt = systemPrompt)
        ).trim()

        if (out.isBlank()) throw IOException("أعاد المزوّد ردًا فارغًا.")
        return out
    }
}
