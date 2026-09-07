package com.example.aichat.repository

import android.content.Context
import com.example.aichat.data.local.AiSettings
import com.example.aichat.data.model.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    // نتيجة توليد الصورة
    // ============================================================

    data class ImageResult(
        val url: String? = null,
        val base64: String? = null
    )

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
            "horde" -> sendHordeText(
                history     = history,
                userMessage = userMessage
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
            "horde"      -> generateImageHorde(prompt)
            "custom"     -> generateImageCustom(prompt)
            else         -> throw IOException("مزود غير معروف: ${settings.imageProvider}")
        }
    }

    // ============================================================
    // AI Horde — النصوص
    // ============================================================

    private suspend fun sendHordeText(
        history: List<Message>,
        userMessage: String
    ): String {

        val apiKey = settings.hordeKey.ifBlank { "0000000000" }
        val model  = settings.hordeTextModel.trim()

        // بناء المحادثة كـprompt نصي
        val promptBuilder = StringBuilder()

        history.takeLast(10).forEach { msg ->
            when (msg.role) {
                "user"      -> promptBuilder.append("User: ${msg.content}\n")
                "assistant" -> promptBuilder.append("Assistant: ${msg.content}\n")
            }
        }

        promptBuilder.append("User: $userMessage\nAssistant:")

        val requestJson = JSONObject().apply {
            put("prompt", promptBuilder.toString())
            put("models", JSONArray().apply { put(model) })
            put("params", JSONObject().apply {
                put("max_length", 512)
                put("max_context_length", 2048)
                put("temperature", 0.7)
                put("top_p", 0.9)
            })
        }

        val request = Request.Builder()
            .url("https://aihorde.net/api/v2/generate/text/async")
            .post(
                requestJson.toString()
                    .toRequestBody("application/json".toMediaType())
            )
            .addHeader("apikey", apiKey)
            .addHeader("Content-Type", "application/json")
            .addHeader("Client-Agent", "AiChat:1.0:github")
            .build()

        // إرسال الطلب
        val jobId = client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("Horde Text HTTP ${response.code}: $body")
            }
            JSONObject(body).optString("id", "")
                .takeIf { it.isNotBlank() }
                ?: throw IOException("Horde: لم يتم إرجاع job_id")
        }

        // Polling حتى اكتمال المهمة
        return pollHordeTextResult(jobId, apiKey)
    }

    private suspend fun pollHordeTextResult(
        jobId: String,
        apiKey: String
    ): String {

        val maxAttempts = 60
        val delayMs     = 3000L

        repeat(maxAttempts) { attempt ->

            delay(delayMs)

            val checkRequest = Request.Builder()
                .url("https://aihorde.net/api/v2/generate/text/status/$jobId")
                .get()
                .addHeader("apikey", apiKey)
                .addHeader("Client-Agent", "AiChat:1.0:github")
                .build()

            val result = client.newCall(checkRequest).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) return@use null
                JSONObject(body)
            } ?: return@repeat

            val isDone = result.optBoolean("done", false)

            if (isDone) {
                val generations = result.optJSONArray("generations")
                val text = generations
                    ?.optJSONObject(0)
                    ?.optString("text", "")
                    ?.trim()

                if (!text.isNullOrBlank()) {
                    return text
                }

                throw IOException("Horde: النتيجة فارغة")
            }

            // انتظر أكثر إذا كانت المهمة لا تزال قيد التنفيذ
        }

        throw IOException("Horde: انتهى الوقت المحدد بدون نتيجة (${maxAttempts * delayMs / 1000} ثانية)")
    }

    // ============================================================
    // AI Horde — الصور
    // ============================================================

    private suspend fun generateImageHorde(
        prompt: String
    ): ImageResult {

        val apiKey = settings.hordeKey.ifBlank { "0000000000" }
        val model  = settings.hordeImageModel.trim()

        val requestJson = JSONObject().apply {
            put("prompt", prompt)
            put("models", JSONArray().apply { put(model) })
            put("params", JSONObject().apply {
                put("width", 512)
                put("height", 512)
                put("steps", 20)
                put("cfg_scale", 7.0)
                put("sampler_name", "k_euler_a")
                put("n", 1)
            })
            put("r2", true) // استخدام R2 storage للحصول على رابط مباشر
        }

        val request = Request.Builder()
            .url("https://aihorde.net/api/v2/generate/async")
            .post(
                requestJson.toString()
                    .toRequestBody("application/json".toMediaType())
            )
            .addHeader("apikey", apiKey)
            .addHeader("Content-Type", "application/json")
            .addHeader("Client-Agent", "AiChat:1.0:github")
            .build()

        val jobId = client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("Horde Image HTTP ${response.code}: $body")
            }
            JSONObject(body).optString("id", "")
                .takeIf { it.isNotBlank() }
                ?: throw IOException("Horde: لم يتم إرجاع job_id")
        }

        return pollHordeImageResult(jobId, apiKey)
    }

    private suspend fun pollHordeImageResult(
        jobId: String,
        apiKey: String
    ): ImageResult {

        val maxAttempts = 120
        val delayMs     = 5000L

        repeat(maxAttempts) {

            delay(delayMs)

            val checkRequest = Request.Builder()
                .url("https://aihorde.net/api/v2/generate/check/$jobId")
                .get()
                .addHeader("apikey", apiKey)
                .addHeader("Client-Agent", "AiChat:1.0:github")
                .build()

            val checkResult = client.newCall(checkRequest).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) return@use null
                JSONObject(body)
            } ?: return@repeat

            val isDone = checkResult.optBoolean("done", false)
            val isFaulted = checkResult.optBoolean("faulted", false)

            if (isFaulted) {
                throw IOException("Horde: فشل توليد الصورة")
            }

            if (isDone) {

                // جلب النتيجة
                val statusRequest = Request.Builder()
                    .url("https://aihorde.net/api/v2/generate/status/$jobId")
                    .get()
                    .addHeader("apikey", apiKey)
                    .addHeader("Client-Agent", "AiChat:1.0:github")
                    .build()

                val statusResult = client.newCall(statusRequest).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        throw IOException("Horde Status HTTP ${response.code}: $body")
                    }
                    JSONObject(body)
                }

                val generations = statusResult.optJSONArray("generations")
                val generation  = generations?.optJSONObject(0)

                // رابط مباشر إذا كان R2 مفعلاً
                val imageUrl = generation?.optString("img", "")
                    ?.takeIf { it.isNotBlank() && it.startsWith("http") }

                if (imageUrl != null) {
                    return ImageResult(url = imageUrl)
                }

                // Base64 إذا لم يكن رابطاً
                val base64 = generation?.optString("img", "")
                    ?.takeIf { it.isNotBlank() }

                if (base64 != null) {
                    return ImageResult(base64 = base64)
                }

                throw IOException("Horde: لم يتم إرجاع صورة")
            }

            // معلومات الانتظار
            val waitTime    = checkResult.optInt("wait_time", 0)
            val queuePos    = checkResult.optInt("queue_position", 0)

            if (waitTime > 300) {
                throw IOException(
                    "Horde: وقت الانتظار طويل جداً ($waitTime ثانية)\n" +
                    "الموضع في الطابور: $queuePos\n" +
                    "حاول لاحقاً أو استخدم مزوداً آخر."
                )
            }
        }

        throw IOException(
            "Horde: انتهى الوقت المحدد بدون نتيجة\n" +
            "الشبكة مشغولة — حاول لاحقاً."
        )
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

        if (apiKey.isBlank())     throw IOException("$providerName: المفتاح فارغ")
        if (cleanModel.isBlank()) throw IOException("$providerName: اسم النموذج فارغ")

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

    // ============================================================
    // توليد الصور — OpenAI
    // ============================================================

    private fun generateImageOpenAI(prompt: String): ImageResult {

        val apiKey = settings.openaiKey
        val model  = settings.imageModel.trim().ifBlank { "dall-e-3" }

        if (apiKey.isBlank()) throw IOException("OpenAI: المفتاح فارغ")

        val requestJson = JSONObject().apply {
            put("model", model)
            put("prompt", prompt)
            put("n", 1)
            put("size", "1024x1024")
            put("response_format", "url")
        }

        val request = Request.Builder()
            .url("https://api.openai.com/v1/images/generations")
            .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("OpenAI Image HTTP ${response.code}: $body")
            }
            val url = JSONObject(body).optJSONArray("data")
                ?.optJSONObject(0)?.optString("url", "")
            if (url.isNullOrBlank()) throw IOException("OpenAI: لم يتم إرجاع رابط الصورة")
            return ImageResult(url = url)
        }
    }

    // ============================================================
    // توليد الصور — OpenRouter
    // ============================================================

    private fun generateImageOpenRouter(prompt: String): ImageResult {

        val apiKey = settings.openrouterKey
        val model  = settings.imageModel.trim()
            .ifBlank { "black-forest-labs/flux-schnell:free" }

        if (apiKey.isBlank()) throw IOException("OpenRouter: المفتاح فارغ")

        val requestJson = JSONObject().apply {
            put("model", model)
            put("prompt", prompt)
        }

        val request = Request.Builder()
            .url("https://openrouter.ai/api/v1/images/generations")
            .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
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
            val url = JSONObject(body).optJSONArray("data")
                ?.optJSONObject(0)?.optString("url", "")
            if (url.isNullOrBlank()) throw IOException("OpenRouter: لم يتم إرجاع رابط الصورة")
            return ImageResult(url = url)
        }
    }

    // ============================================================
    // توليد الصور — Custom
    // ============================================================

    private fun generateImageCustom(prompt: String): ImageResult {

        val url    = settings.customImageUrl.trim()
        val apiKey = settings.customImageKey
        val model  = settings.imageModel.trim()

        if (url.isBlank()) throw IOException("Custom Image: الرابط فارغ")

        val isImageEndpoint = url.contains("images/generations") ||
                              url.contains("image/generate")

        val requestJson = if (isImageEndpoint) {
            JSONObject().apply {
                put("prompt", prompt)
                if (model.isNotBlank()) put("model", model)
                put("n", 1)
            }
        } else {
            JSONObject().apply {
                put("model", model)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                    })
                })
                put("max_tokens", 1024)
            }
        }

        val requestBuilder = Request.Builder()
            .url(url)
            .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
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

            val fromData = root.optJSONArray("data")
                ?.optJSONObject(0)?.optString("url", "")
                ?.takeIf { it.isNotBlank() }
            if (fromData != null) return ImageResult(url = fromData)

            val fromChat = root.optJSONArray("choices")
                ?.optJSONObject(0)?.optJSONObject("message")
                ?.optString("content", "")?.takeIf { it.isNotBlank() }
            if (fromChat != null) {
                val urlRegex = Regex(
                    "(https?://[^\\s\"'<>]+\\.(?:png|jpg|jpeg|webp|gif)(?:[^\\s\"'<>]*)?)",
                    RegexOption.IGNORE_CASE
                )
                val extracted = urlRegex.find(fromChat)?.value
                if (extracted != null) return ImageResult(url = extracted)
            }

            throw IOException(
                "Custom Image: لم يتم إرجاع صورة\n${body.take(200)}"
            )
        }
    }
}
