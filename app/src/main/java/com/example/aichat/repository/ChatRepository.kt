package com.example.aichat.repository

import android.content.Context
import android.util.Base64
import com.example.aichat.data.local.AiSettings
import com.example.aichat.data.model.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class ChatRepository(context: Context) {

    val settings = AiSettings(context)

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    // ============================================================
    // المحادثة النصية
    // ============================================================

    suspend fun sendMessage(
        history: List<Message>,
        userMessage: String,
        imageBase64: String? = null
    ): String = withContext(Dispatchers.IO) {

        when (settings.provider.lowercase().trim()) {
            "gemini"     -> sendGemini(history, userMessage, imageBase64)
            "openrouter" -> sendOpenAICompatible(
                baseUrl      = "https://openrouter.ai/api/v1/chat/completions",
                apiKey       = settings.openrouterKey,
                model        = settings.openrouterModel,
                history      = history,
                userMessage  = userMessage,
                imageBase64  = imageBase64,
                providerName = "OpenRouter"
            )
            "openai" -> sendOpenAICompatible(
                baseUrl      = "https://api.openai.com/v1/chat/completions",
                apiKey       = settings.openaiKey,
                model        = settings.openaiModel,
                history      = history,
                userMessage  = userMessage,
                imageBase64  = imageBase64,
                providerName = "OpenAI"
            )
            "mistral" -> sendOpenAICompatible(
                baseUrl      = "https://api.mistral.ai/v1/chat/completions",
                apiKey       = settings.mistralKey,
                model        = settings.mistralModel,
                history      = history,
                userMessage  = userMessage,
                imageBase64  = imageBase64,
                providerName = "Mistral"
            )
            "groq" -> sendOpenAICompatible(
                baseUrl      = "https://api.groq.com/openai/v1/chat/completions",
                apiKey       = settings.groqKey,
                model        = settings.groqModel,
                history      = history,
                userMessage  = userMessage,
                imageBase64  = imageBase64,
                providerName = "Groq"
            )
            "custom" -> sendOpenAICompatible(
                baseUrl      = settings.customUrl,
                apiKey       = settings.customKey,
                model        = settings.customModel,
                history      = history,
                userMessage  = userMessage,
                imageBase64  = imageBase64,
                providerName = "Custom"
            )
            else -> throw IOException("مزود غير معروف: ${settings.provider}")
        }
    }

    // ============================================================
    // توليد الصور
    // ============================================================

    suspend fun generateImage(
    prompt: String
): ImageResult = withContext(Dispatchers.IO) {

    when (settings.imageProvider.lowercase().trim()) {
        "openai"     -> generateImageOpenAI(prompt)
        "openrouter" -> generateImageOpenRouter(prompt)
        "custom"     -> generateImageCustom(prompt)
        else         -> throw IOException("مزود غير معروف: ${settings.imageProvider}")
    }
}

private fun generateImageCustom(
    prompt: String
): ImageResult {

    val url    = settings.customImageUrl.trim()
    val apiKey = settings.customImageKey
    val model  = settings.imageModel.trim()

    if (url.isBlank()) {
        throw IOException("Custom Image: الرابط فارغ")
    }

    val requestJson = JSONObject().apply {
        put("prompt", prompt)
        if (model.isNotBlank()) put("model", model)
        put("n", 1)
    }

    val requestBuilder = Request.Builder()
        .url(url)
        .post(
            requestJson.toString()
                .toRequestBody("application/json".toMediaType())
        )
        .addHeader("Content-Type", "application/json")

    if (apiKey.isNotBlank()) {
        requestBuilder.addHeader("Authorization", "Bearer $apiKey")
    }

    client.newCall(requestBuilder.build()).execute().use { response ->

        val body = response.body?.string().orEmpty()

        if (!response.isSuccessful) {
            throw IOException("Custom Image HTTP ${response.code}: $body")
        }

        val root = JSONObject(body)
        val data = root.optJSONArray("data")
        val imageUrl = data?.optJSONObject(0)?.optString("url", "")

        if (imageUrl.isNullOrBlank()) {
            throw IOException("Custom Image: لم يتم إرجاع رابط الصورة")
        }

        return ImageResult(url = imageUrl)
    }
}
    // ============================================================
    // نتيجة توليد الصورة
    // ============================================================

    data class ImageResult(
        val url: String? = null,
        val base64: String? = null
    )

    // ============================================================
    // توليد الصور — OpenAI DALL-E
    // ============================================================

    private fun generateImageOpenAI(
        prompt: String
    ): ImageResult {

        val apiKey = settings.openaiKey
        val model  = settings.imageModel.trim().ifBlank { "dall-e-3" }

        if (apiKey.isBlank()) {
            throw IOException("OpenAI: المفتاح فارغ")
        }

        val requestJson = JSONObject().apply {
            put("model", model)
            put("prompt", prompt)
            put("n", 1)
            put("size", "1024x1024")
            put("response_format", "url")
        }

        val request = Request.Builder()
            .url("https://api.openai.com/v1/images/generations")
            .post(
                requestJson.toString()
                    .toRequestBody("application/json".toMediaType())
            )
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .build()

        client.newCall(request).execute().use { response ->

            val body = response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                throw IOException("OpenAI Image HTTP ${response.code}: $body")
            }

            val root = JSONObject(body)
            val data = root.optJSONArray("data")
            val url  = data?.optJSONObject(0)?.optString("url", "")

            if (url.isNullOrBlank()) {
                throw IOException("OpenAI: لم يتم إرجاع رابط الصورة")
            }

            return ImageResult(url = url)
        }
    }

    // ============================================================
    // توليد الصور — OpenRouter
    // ============================================================

    private fun generateImageOpenRouter(
        prompt: String
    ): ImageResult {

        val apiKey = settings.openrouterKey
        val model  = settings.imageModel.trim().ifBlank { "black-forest-labs/flux-schnell:free" }

        if (apiKey.isBlank()) {
            throw IOException("OpenRouter: المفتاح فارغ")
        }

        val requestJson = JSONObject().apply {
            put("model", model)
            put("prompt", prompt)
        }

        val request = Request.Builder()
            .url("https://openrouter.ai/api/v1/images/generations")
            .post(
                requestJson.toString()
                    .toRequestBody("application/json".toMediaType())
            )
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .addHeader("HTTP-Referer", "https://github.com/")
            .addHeader("X-Title", "AiChat")
            .build()

        client.newCall(request).execute().use { response ->

            val body = response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                throw IOException("OpenRouter Image HTTP ${response.code}: $body")
            }

            val root = JSONObject(body)
            val data = root.optJSONArray("data")
            val url  = data?.optJSONObject(0)?.optString("url", "")

            if (url.isNullOrBlank()) {
                throw IOException("OpenRouter: لم يتم إرجاع رابط الصورة")
            }

            return ImageResult(url = url)
        }
    }

    // ============================================================
    // Gemini
    // ============================================================

    private fun sendGemini(
        history: List<Message>,
        userMessage: String,
        imageBase64: String?
    ): String {

        val apiKey = settings.geminiKey
        val model  = settings.geminiModel.trim().ifBlank { "gemini-3.6-flash" }

        val endpoint =
            "https://generativelanguage.googleapis.com/v1beta/models/" +
            "$model:generateContent?key=$apiKey"

        val contents = JSONArray()

        history.forEach { msg ->
            val role  = if (msg.role == "user") "user" else "model"
            val parts = JSONArray()

            if (msg.imageBase64 != null) {
                parts.put(JSONObject().apply {
                    put("inline_data", JSONObject().apply {
                        put("mime_type", "image/jpeg")
                        put("data", msg.imageBase64)
                    })
                })
            }

            parts.put(JSONObject().apply { put("text", msg.content) })

            contents.put(JSONObject().apply {
                put("role", role)
                put("parts", parts)
            })
        }

        val newParts = JSONArray()

        if (imageBase64 != null) {
            newParts.put(JSONObject().apply {
                put("inline_data", JSONObject().apply {
                    put("mime_type", "image/jpeg")
                    put("data", imageBase64)
                })
            })
        }

        newParts.put(JSONObject().apply { put("text", userMessage) })

        contents.put(JSONObject().apply {
            put("role", "user")
            put("parts", newParts)
        })

        val requestJson = JSONObject().apply {
            put("contents", contents)
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.7)
                put("maxOutputTokens", 8192)
            })
        }

        val request = Request.Builder()
            .url(endpoint)
            .post(
                requestJson.toString()
                    .toRequestBody("application/json".toMediaType())
            )
            .build()

        client.newCall(request).execute().use { response ->

            val body = response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                throw IOException("Gemini HTTP ${response.code}: $body")
            }

            val root       = JSONObject(body)
            val candidates = root.optJSONArray("candidates")
            val content    = candidates?.optJSONObject(0)?.optJSONObject("content")
            val parts      = content?.optJSONArray("parts")
            val text       = parts?.optJSONObject(0)?.optString("text", "")

            return text?.trim()
                ?: throw IOException("Gemini: لم يتم العثور على نص")
        }
    }

    // ============================================================
    // OpenAI Compatible
    // ============================================================

    private fun sendOpenAICompatible(
        baseUrl: String,
        apiKey: String,
        model: String,
        history: List<Message>,
        userMessage: String,
        imageBase64: String?,
        providerName: String
    ): String {

        val cleanModel = model.trim()

        if (apiKey.isBlank())      throw IOException("$providerName: المفتاح فارغ")
        if (cleanModel.isBlank())  throw IOException("$providerName: اسم النموذج فارغ")

        val messages = JSONArray()

        history.forEach { msg ->
            val content = JSONArray()

            if (msg.imageBase64 != null) {
                content.put(JSONObject().apply {
                    put("type", "image_url")
                    put("image_url", JSONObject().apply {
                        put("url", "data:image/jpeg;base64,${msg.imageBase64}")
                    })
                })
            }

            content.put(JSONObject().apply {
                put("type", "text")
                put("text", msg.content)
            })

            messages.put(JSONObject().apply {
                put("role", msg.role)
                put("content", content)
            })
        }

        val newContent = JSONArray()

        if (imageBase64 != null) {
            newContent.put(JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().apply {
                    put("url", "data:image/jpeg;base64,$imageBase64")
                })
            })
        }

        newContent.put(JSONObject().apply {
            put("type", "text")
            put("text", userMessage)
        })

        messages.put(JSONObject().apply {
            put("role", "user")
            put("content", newContent)
        })

        val requestJson = JSONObject().apply {
            put("model", cleanModel)
            put("messages", messages)
            put("temperature", 0.7)
            put("max_tokens", 8192)
        }

        val requestBuilder = Request.Builder()
            .url(baseUrl)
            .post(
                requestJson.toString()
                    .toRequestBody("application/json".toMediaType())
            )
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")

        if (providerName == "OpenRouter") {
            requestBuilder
                .addHeader("HTTP-Referer", "https://github.com/")
                .addHeader("X-Title", "AiChat")
        }

        client.newCall(requestBuilder.build()).execute().use { response ->

            val body = response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                throw IOException("$providerName HTTP ${response.code}: $body")
            }

            val root    = JSONObject(body)
            val choices = root.optJSONArray("choices")
            val message = choices?.optJSONObject(0)?.optJSONObject("message")
            val text    = message?.optString("content", "")

            return text?.trim()
                ?: throw IOException("$providerName: لم يتم العثور على نص")
        }
    }
}
