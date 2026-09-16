package com.example.aichat.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * OpenRouter - بوابة موحدة لأكثر من 100 نموذج AI
 * API متوافق مع OpenAI
 * https://openrouter.ai
 */
class OpenRouterProvider {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    companion object {
        const val BASE_URL = "https://openrouter.ai/api/v1/chat/completions"
        const val MODELS_URL = "https://openrouter.ai/api/v1/models"

        // نماذج مجانية
        val FREE_MODELS = listOf(
            "meta-llama/llama-3.1-8b-instruct:free",
            "google/gemma-2-9b-it:free",
            "mistralai/mistral-7b-instruct:free",
            "microsoft/phi-3-mini-128k-instruct:free",
            "qwen/qwen-2-7b-instruct:free"
        )

        // نماذج مدفوعة
        val PREMIUM_MODELS = listOf(
            "anthropic/claude-3.5-sonnet",
            "openai/gpt-4o",
            "google/gemini-pro-1.5",
            "meta-llama/llama-3.1-70b-instruct",
            "mistralai/mistral-large"
        )
    }

    // ============================================================
    // إرسال رسالة
    // ============================================================

    suspend fun sendMessage(
        apiKey: String,
        model: String,
        messages: List<Map<String, String>>,
        systemPrompt: String = "",
        maxTokens: Int = 2048,
        temperature: Double = 0.7,
        appTitle: String = "AiChat",
        appReferer: String = "https://github.com/aichat"
    ): String = withContext(Dispatchers.IO) {

        if (apiKey.isBlank()) throw Exception("❌ مفتاح OpenRouter فارغ")
        if (model.isBlank()) throw Exception("❌ النموذج غير محدد")

        val messagesArray = JSONArray()

        // System prompt
        if (systemPrompt.isNotBlank()) {
            messagesArray.put(
                JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                }
            )
        }

        // رسائل المحادثة
        messages.forEach { msg ->
            messagesArray.put(
                JSONObject().apply {
                    put("role", msg["role"] ?: "user")
                    put("content", msg["content"] ?: "")
                }
            )
        }

        val requestBody = JSONObject().apply {
            put("model", model)
            put("messages", messagesArray)
            put("max_tokens", maxTokens)
            put("temperature", temperature)
        }

        android.util.Log.d(
            "OpenRouter",
            "📤 Sending to model: $model | messages: ${messagesArray.length()}"
        )

        val request = Request.Builder()
            .url(BASE_URL)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .addHeader("HTTP-Referer", appReferer)
            .addHeader("X-Title", appTitle)
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                val errorMsg = runCatching {
                    JSONObject(body)
                        .optJSONObject("error")
                        ?.optString("message")
                        ?: "HTTP ${response.code}"
                }.getOrDefault("HTTP ${response.code}")

                android.util.Log.e("OpenRouter", "❌ ${response.code}: $errorMsg")

                throw Exception(
                    when (response.code) {
                        400 -> "❌ طلب غير صالح: $errorMsg"
                        401 -> "❌ مفتاح API غير صالح"
                        402 -> "❌ رصيد غير كافٍ في OpenRouter"
                        429 -> "⚠️ تجاوزت الحد - انتظر قليلاً"
                        503 -> "❌ النموذج غير متاح حالياً"
                        else -> "❌ خطأ OpenRouter: $errorMsg"
                    }
                )
            }

            val result = JSONObject(body)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()

            android.util.Log.d("OpenRouter", "✅ Response: ${result.take(100)}...")

            result
        }
    }

    // ============================================================
    // جلب النماذج المتاحة
    // ============================================================

    suspend fun getAvailableModels(
        apiKey: String
    ): List<ModelInfo> = withContext(Dispatchers.IO) {

        if (apiKey.isBlank()) return@withContext emptyList()

        val request = Request.Builder()
            .url(MODELS_URL)
            .addHeader("Authorization", "Bearer $apiKey")
            .get()
            .build()

        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()

                val body = response.body?.string()
                    ?: return@withContext emptyList()

                val models = JSONObject(body).getJSONArray("data")

                (0 until models.length()).mapNotNull { i ->
                    runCatching {
                        val model = models.getJSONObject(i)
                        val id = model.getString("id")

                        ModelInfo(
                            id = id,
                            name = model.optString("name", id),
                            isFree = id.endsWith(":free"),
                            contextLength = model.optInt("context_length", 4096),
                            pricing = model.optJSONObject("pricing")?.let { p ->
                                ModelPricing(
                                    promptPerMillion = p.optDouble("prompt", 0.0),
                                    completionPerMillion = p.optDouble("completion", 0.0)
                                )
                            }
                        )
                    }.getOrNull()
                }.sortedWith(
                    compareBy(
                        { !it.isFree },
                        { it.pricing?.promptPerMillion ?: 0.0 }
                    )
                )
            }
        }.getOrDefault(emptyList())
    }

    // ============================================================
    // Data Classes
    // ============================================================

    data class ModelInfo(
        val id: String,
        val name: String,
        val isFree: Boolean,
        val contextLength: Int,
        val pricing: ModelPricing?
    ) {
        fun displayName(): String = buildString {
            append(name)
            if (isFree) append(" (مجاني)")
            pricing?.let { p ->
                if (!isFree && p.promptPerMillion > 0) {
                    append(" - \$${p.promptPerMillion}/M")
                }
            }
        }
    }

    data class ModelPricing(
        val promptPerMillion: Double,
        val completionPerMillion: Double
    )
}
