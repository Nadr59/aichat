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
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    data class ImageResult(
        val url: String? = null,
        val base64: String? = null
    )

    // ============================================================
    // حدود النماذج المجانية
    // ============================================================

    private fun getMaxHistory(provider: String, model: String): Int = when {
        provider == "groq"                          -> 6
        provider == "horde"                         -> 4
        provider == "mistral"                       -> 6
        provider == "openrouter" && model.contains(":free") -> 6
        else                                        -> 10
    }

    private fun getMaxTokens(provider: String, model: String): Int = when {
        provider == "groq"                          -> 2048
        provider == "horde"                         -> 512
        provider == "mistral"                       -> 2048
        provider == "openrouter" && model.contains(":free") -> 2048
        else                                        -> 4096
    }

    // ============================================================
    // إرسال رسالة نصية
    // ============================================================

    suspend fun sendMessage(
        history: List<Message>,
        userMessage: String,
        imageBase64: String? = null
    ): String = withContext(Dispatchers.IO) {

        when (settings.provider.lowercase().trim()) {

            "gemini" -> sendGemini(history, userMessage, imageBase64)

            "openrouter" -> sendOpenAICompatible(
                baseUrl      = "https://openrouter.ai/api/v1/chat/completions",
                apiKey       = settings.openrouterKey,
                model        = settings.openrouterModel,
                history      = history,
                userMessage  = userMessage,
                imageBase64  = imageBase64,
                providerName = "OpenRouter",
                extraHeaders = mapOf(
                    "HTTP-Referer" to "https://github.com/",
                    "X-Title"      to "AiChat"
                )
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

            "mistral" -> sendMistral(history, userMessage, imageBase64)

            "groq" -> sendOpenAICompatible(
                baseUrl      = "https://api.groq.com/openai/v1/chat/completions",
                apiKey       = settings.groqKey,
                model        = settings.groqModel,
                history      = history,
                userMessage  = userMessage,
                imageBase64  = imageBase64,
                providerName = "Groq"
            )

            "horde" -> sendHordeText(history, userMessage)

            "custom" -> {
                val url = settings.customUrl.trim()
                if (url.isBlank()) throw IOException("Custom: رابط الخادم فارغ")
                sendOpenAICompatible(
                    baseUrl      = url,
                    apiKey       = settings.customKey,
                    model        = settings.customModel,
                    history      = history,
                    userMessage  = userMessage,
                    imageBase64  = imageBase64,
                    providerName = "Custom"
                )
            }

            else -> throw IOException("مزود غير معروف: ${settings.provider}")
        }
    }

    // ============================================================
    // توليد الصور
    // ============================================================

    suspend fun generateImage(prompt: String): ImageResult = withContext(Dispatchers.IO) {
        when (settings.imageProvider.lowercase().trim()) {
            "openai"     -> generateImageOpenAI(prompt)
            "openrouter" -> generateImageOpenRouter(prompt)
            "horde"      -> generateImageHorde(prompt)
            "custom"     -> generateImageCustom(prompt)
            else -> throw IOException("مزود صور غير معروف: ${settings.imageProvider}")
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

        val apiKey = settings.geminiKey.trim()
        if (apiKey.isBlank()) throw IOException("Gemini: المفتاح فارغ")

        val model    = settings.geminiModel.trim().ifBlank { "gemini-3.6-flash" }
        val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/" +
                       "$model:generateContent?key=$apiKey"

        val contents = JSONArray()

        history.takeLast(10).forEach { msg ->
            val role  = if (msg.role == "user") "user" else "model"
            val parts = JSONArray()
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
                put("topP", 0.95)
            })
            put("safetySettings", JSONArray().apply {
                listOf(
                    "HARM_CATEGORY_HARASSMENT",
                    "HARM_CATEGORY_HATE_SPEECH",
                    "HARM_CATEGORY_SEXUALLY_EXPLICIT",
                    "HARM_CATEGORY_DANGEROUS_CONTENT"
                ).forEach { category ->
                    put(JSONObject().apply {
                        put("category", category)
                        put("threshold", "BLOCK_ONLY_HIGH")
                    })
                }
            })
        }

        val request = Request.Builder()
            .url(endpoint)
            .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
            .addHeader("Content-Type", "application/json")
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                val msg = runCatching {
                    JSONObject(body).optJSONObject("error")?.optString("message") ?: body
                }.getOrDefault(body)
                throw IOException(
                    when (response.code) {
                        429  -> "⚠️ Gemini: تجاوزت حد الطلبات - انتظر دقيقة"
                        401  -> "❌ Gemini: المفتاح غير صحيح"
                        else -> "Gemini ${response.code}: $msg"
                    }
                )
            }

            val candidates = JSONObject(body).optJSONArray("candidates")
            if (candidates == null || candidates.length() == 0) {
                val reason = JSONObject(body).optJSONObject("promptFeedback")
                    ?.optString("blockReason", "غير معروف")
                throw IOException("Gemini: محجوب ($reason)")
            }

            val candidate    = candidates.optJSONObject(0)
            val finishReason = candidate?.optString("finishReason", "")
            val text         = candidate
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
                ?.optJSONObject(0)
                ?.optString("text", "")
                ?.trim()

            if (!text.isNullOrBlank()) return text

            throw IOException(
                when (finishReason) {
                    "SAFETY"     -> "Gemini: محجوب لأسباب أمان"
                    "MAX_TOKENS" -> "Gemini: الرد طويل جداً"
                    else         -> "Gemini: رد فارغ ($finishReason)"
                }
            )
        }
    }

    // ============================================================
    // Mistral - دالة مستقلة بحدود مناسبة
    // ============================================================

    private fun sendMistral(
        history: List<Message>,
        userMessage: String,
        imageBase64: String?
    ): String {

        val apiKey = settings.mistralKey.trim()
        val model  = settings.mistralModel.trim()

        if (apiKey.isBlank()) throw IOException("Mistral: المفتاح فارغ")
        if (model.isBlank())  throw IOException("Mistral: النموذج فارغ")

        val messages   = JSONArray()
        val maxHistory = getMaxHistory("mistral", model)
        val maxTokens  = getMaxTokens("mistral", model)

        // آخر N رسائل نصية فقط
        history.takeLast(maxHistory).forEach { msg ->
            messages.put(JSONObject().apply {
                put("role", msg.role)
                put("content", msg.content)
            })
        }

        // الرسالة الحالية
        val isVision = model.contains("pixtral") || model.contains("vision")
        if (imageBase64 != null && isVision) {
            messages.put(JSONObject().apply {
                put("role", "user")
                put("content", JSONArray().apply {
                    put(JSONObject().apply {
                        put("type", "text")
                        put("text", userMessage)
                    })
                    put(JSONObject().apply {
                        put("type", "image_url")
                        put("image_url", JSONObject().apply {
                            put("url", "data:image/jpeg;base64,$imageBase64")
                        })
                    })
                })
            })
        } else {
            messages.put(JSONObject().apply {
                put("role", "user")
                put("content", userMessage)
            })
        }

        val requestJson = JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("temperature", 0.7)
            put("max_tokens", maxTokens)
        }

        val request = Request.Builder()
            .url("https://api.mistral.ai/v1/chat/completions")
            .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                val msg = runCatching {
                    val j = JSONObject(body)
                    j.optJSONObject("error")?.optString("message")
                        ?: j.optString("message", body)
                }.getOrDefault(body)
                throw IOException(
                    when (response.code) {
                        429  -> "⚠️ Mistral: تجاوزت حد الطلبات\n" +
                                "النموذج الحالي: $model\n" +
                                "جرّب: pixtral-12b-2409"
                        401  -> "❌ Mistral: المفتاح غير صحيح"
                        422  -> "❌ Mistral: صيغة خاطئة: $msg"
                        else -> "Mistral ${response.code}: $msg"
                    }
                )
            }

            val text = JSONObject(body)
                .optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content", "")
                ?.trim()

            return text?.takeIf { it.isNotBlank() }
                ?: throw IOException("Mistral: الرد فارغ")
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
        providerName: String,
        extraHeaders: Map<String, String> = emptyMap()
    ): String {

        val cleanModel = model.trim()
        if (apiKey.isBlank())     throw IOException("$providerName: المفتاح فارغ")
        if (cleanModel.isBlank()) throw IOException("$providerName: النموذج فارغ")
        if (baseUrl.isBlank())    throw IOException("$providerName: الرابط فارغ")

        val provider   = providerName.lowercase()
        val maxHistory = getMaxHistory(provider, cleanModel)
        val maxTokens  = getMaxTokens(provider, cleanModel)

        val messages = JSONArray()

        // System prompt
        messages.put(JSONObject().apply {
            put("role", "system")
            put("content", "You are a helpful AI assistant.")
        })

        // History - نص فقط
        history.takeLast(maxHistory).forEach { msg ->
            messages.put(JSONObject().apply {
                put("role", msg.role)
                put("content", msg.content)
            })
        }

        // الرسالة الحالية
        val hasImage = imageBase64 != null && supportsVision(cleanModel)
        if (hasImage) {
            messages.put(JSONObject().apply {
                put("role", "user")
                put("content", JSONArray().apply {
                    put(JSONObject().apply {
                        put("type", "image_url")
                        put("image_url", JSONObject().apply {
                            put("url", "data:image/jpeg;base64,$imageBase64")
                            put("detail", "low")
                        })
                    })
                    put(JSONObject().apply {
                        put("type", "text")
                        put("text", userMessage)
                    })
                })
            })
        } else {
            messages.put(JSONObject().apply {
                put("role", "user")
                put("content", userMessage)
            })
        }

        val requestJson = JSONObject().apply {
            put("model", cleanModel)
            put("messages", messages)
            put("temperature", 0.7)
            put("max_tokens", maxTokens)
        }

        val requestBuilder = Request.Builder()
            .url(baseUrl)
            .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")

        extraHeaders.forEach { (k, v) -> requestBuilder.addHeader(k, v) }

        client.newCall(requestBuilder.build()).execute().use { response ->
            val body = response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                val msg = runCatching {
                    val j = JSONObject(body)
                    j.optJSONObject("error")?.optString("message")
                        ?: j.optString("message", body)
                }.getOrDefault(body)
                throw IOException(
                    when (response.code) {
                        429  -> "⚠️ $providerName: تجاوزت حد الطلبات - انتظر دقيقة"
                        401  -> "❌ $providerName: المفتاح غير صحيح"
                        403  -> "❌ $providerName: لا توجد صلاحية"
                        422  -> "❌ $providerName: صيغة خاطئة: $msg"
                        500  -> "❌ $providerName: خطأ في الخادم"
                        else -> "$providerName ${response.code}: $msg"
                    }
                )
            }

            val text = JSONObject(body)
                .optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content", "")
                ?.trim()

            return text?.takeIf { it.isNotBlank() }
                ?: throw IOException("$providerName: الرد فارغ")
        }
    }

    private fun supportsVision(model: String): Boolean {
        val m = model.lowercase()
        return listOf(
            "gpt-4o", "gpt-4-turbo", "gpt-4-vision",
            "gemini", "claude-3", "pixtral",
            "llava", "vision", "qwen-vl", "qwen2-vl"
        ).any { m.contains(it) }
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

        val sb = StringBuilder()
        sb.append("### System\nYou are a helpful AI assistant.\n\n")
        history.takeLast(4).forEach { msg ->
            when (msg.role) {
                "user"      -> sb.append("### Human\n${msg.content}\n\n")
                "assistant" -> sb.append("### Assistant\n${msg.content}\n\n")
            }
        }
        sb.append("### Human\n$userMessage\n\n### Assistant\n")

        val requestJson = JSONObject().apply {
            put("prompt", sb.toString())
            put("models", JSONArray().apply { put(model) })
            put("params", JSONObject().apply {
                put("max_length", 512)
                put("max_context_length", 2048)
                put("temperature", 0.7)
                put("top_p", 0.9)
                put("rep_pen", 1.1)
            })
            put("trusted_workers", false)
            put("slow_workers", true)
        }

        val request = Request.Builder()
            .url("https://aihorde.net/api/v2/generate/text/async")
            .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
            .addHeader("apikey", apiKey)
            .addHeader("Content-Type", "application/json")
            .addHeader("Client-Agent", "AiChat:1.0:anonymous")
            .build()

        val jobId = client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful)
                throw IOException("Horde ${response.code}: $body")
            JSONObject(body).optString("id", "")
                .takeIf { it.isNotBlank() }
                ?: throw IOException("Horde: لم يُرجع job_id")
        }

        return pollHordeText(jobId, apiKey)
    }

    private suspend fun pollHordeText(jobId: String, apiKey: String): String {
        repeat(60) {
            delay(3000L)
            val req = Request.Builder()
                .url("https://aihorde.net/api/v2/generate/text/status/$jobId")
                .get()
                .addHeader("apikey", apiKey)
                .addHeader("Client-Agent", "AiChat:1.0:anonymous")
                .build()

            val obj = runCatching {
                client.newCall(req).execute().use { r ->
                    JSONObject(r.body?.string().orEmpty())
                }
            }.getOrNull() ?: return@repeat

            if (obj.optBoolean("faulted", false))
                throw IOException("Horde: فشلت المهمة")

            if (obj.optBoolean("done", false)) {
                val text = obj.optJSONArray("generations")
                    ?.optJSONObject(0)
                    ?.optString("text", "")
                    ?.trim()
                return text?.takeIf { it.isNotBlank() }
                    ?: throw IOException("Horde: النتيجة فارغة")
            }
        }
        throw IOException("Horde: انتهى الوقت بدون نتيجة")
    }

    // ============================================================
    // AI Horde — الصور
    // ============================================================

    private suspend fun generateImageHorde(prompt: String): ImageResult {

        val apiKey = settings.hordeKey.ifBlank { "0000000000" }
        val model  = settings.hordeImageModel.trim()

        val requestJson = JSONObject().apply {
            put("prompt", prompt)
            put("models", JSONArray().apply { put(model) })
            put("params", JSONObject().apply {
                put("width", 512)
                put("height", 512)
                put("steps", 20)
                put("cfg_scale", 7.5)
                put("sampler_name", "k_euler_a")
                put("n", 1)
                put("karras", true)
            })
            put("r2", true)
            put("shared", false)
            put("trusted_workers", false)
            put("slow_workers", true)
        }

        val request = Request.Builder()
            .url("https://aihorde.net/api/v2/generate/async")
            .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
            .addHeader("apikey", apiKey)
            .addHeader("Content-Type", "application/json")
            .addHeader("Client-Agent", "AiChat:1.0:anonymous")
            .build()

        val jobId = client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful)
                throw IOException("Horde Image ${response.code}: $body")
            JSONObject(body).optString("id", "")
                .takeIf { it.isNotBlank() }
                ?: throw IOException("Horde: لم يُرجع job_id")
        }

        return pollHordeImage(jobId, apiKey)
    }

    private suspend fun pollHordeImage(jobId: String, apiKey: String): ImageResult {
        repeat(120) {
            delay(5000L)

            val checkReq = Request.Builder()
                .url("https://aihorde.net/api/v2/generate/check/$jobId")
                .get()
                .addHeader("apikey", apiKey)
                .addHeader("Client-Agent", "AiChat:1.0:anonymous")
                .build()

            val check = runCatching {
                client.newCall(checkReq).execute().use { r ->
                    JSONObject(r.body?.string().orEmpty())
                }
            }.getOrNull() ?: return@repeat

            if (check.optBoolean("faulted", false))
                throw IOException("Horde: فشل توليد الصورة")

            if (!check.optBoolean("done", false)) return@repeat

            val statusReq = Request.Builder()
                .url("https://aihorde.net/api/v2/generate/status/$jobId")
                .get()
                .addHeader("apikey", apiKey)
                .addHeader("Client-Agent", "AiChat:1.0:anonymous")
                .build()

            val status = client.newCall(statusReq).execute().use { r ->
                val body = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw IOException("Horde Status ${r.code}: $body")
                JSONObject(body)
            }

            val img = status.optJSONArray("generations")
                ?.optJSONObject(0)
                ?.optString("img", "")
                ?: throw IOException("Horde: لا توجد صورة")

            return when {
                img.startsWith("http") -> ImageResult(url = img)
                img.isNotBlank()       -> ImageResult(base64 = img)
                else -> throw IOException("Horde: الصورة فارغة")
            }
        }
        throw IOException("Horde: انتهى الوقت بدون صورة")
    }

    // ============================================================
    // توليد الصور — OpenAI
    // ============================================================

    private fun generateImageOpenAI(prompt: String): ImageResult {

        val apiKey = settings.openaiKey.trim()
        val model  = settings.imageModel.trim().ifBlank { "dall-e-3" }
        if (apiKey.isBlank()) throw IOException("OpenAI: المفتاح فارغ")

        val requestJson = JSONObject().apply {
            put("model", model)
            put("prompt", prompt)
            put("n", 1)
            put("size", if (model == "dall-e-3") "1024x1024" else "512x512")
            put("response_format", "url")
            if (model == "dall-e-3") put("quality", "standard")
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
                val msg = runCatching {
                    JSONObject(body).optJSONObject("error")?.optString("message") ?: body
                }.getOrDefault(body)
                throw IOException("OpenAI Image ${response.code}: $msg")
            }
            val url = JSONObject(body).optJSONArray("data")
                ?.optJSONObject(0)?.optString("url", "")
                ?.takeIf { it.isNotBlank() }
                ?: throw IOException("OpenAI: لم يُرجع رابط الصورة")
            return ImageResult(url = url)
        }
    }

    // ============================================================
    // توليد الصور — OpenRouter
    // ============================================================

    private fun generateImageOpenRouter(prompt: String): ImageResult {

        val apiKey = settings.openrouterKey.trim()
        val model  = settings.imageModel.trim()
            .ifBlank { "black-forest-labs/flux-schnell:free" }
        if (apiKey.isBlank()) throw IOException("OpenRouter: المفتاح فارغ")

        val requestJson = JSONObject().apply {
            put("model", model)
            put("prompt", prompt)
            put("n", 1)
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
                val msg = runCatching {
                    val j = JSONObject(body)
                    j.optJSONObject("error")?.optString("message")
                        ?: j.optString("message", body)
                }.getOrDefault(body)
                throw IOException("OpenRouter Image ${response.code}: $msg")
            }
            val root = JSONObject(body)
            root.optJSONArray("data")?.optJSONObject(0)?.let { d ->
                d.optString("url", "").takeIf { it.isNotBlank() }
                    ?.let { return ImageResult(url = it) }
                d.optString("b64_json", "").takeIf { it.isNotBlank() }
                    ?.let { return ImageResult(base64 = it) }
            }
            throw IOException("OpenRouter: لم يُرجع صورة\n${body.take(200)}")
        }
    }

    // ============================================================
    // توليد الصور — Custom
    // ============================================================

    private fun generateImageCustom(prompt: String): ImageResult {

        val url    = settings.customImageUrl.trim()
        val apiKey = settings.customImageKey.trim()
        val model  = settings.imageModel.trim()
        if (url.isBlank()) throw IOException("Custom Image: الرابط فارغ")

        val requestJson = JSONObject().apply {
            put("prompt", prompt)
            if (model.isNotBlank()) put("model", model)
            put("n", 1)
        }

        val requestBuilder = Request.Builder()
            .url(url)
            .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
            .addHeader("Content-Type", "application/json")

        if (apiKey.isNotBlank())
            requestBuilder.addHeader("Authorization", "Bearer $apiKey")

        client.newCall(requestBuilder.build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful)
                throw IOException("Custom Image ${response.code}: ${body.take(300)}")
            val root = JSONObject(body)
            root.optJSONArray("data")?.optJSONObject(0)?.let { d ->
                d.optString("url", "").takeIf { it.isNotBlank() }
                    ?.let { return ImageResult(url = it) }
                d.optString("b64_json", "").takeIf { it.isNotBlank() }
                    ?.let { return ImageResult(base64 = it) }
            }
            throw IOException("Custom Image: لم يُرجع صورة\n${body.take(200)}")
        }
    }
}
