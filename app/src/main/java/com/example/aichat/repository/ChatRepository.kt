package com.example.aichat.repository

import android.content.Context
import com.example.aichat.data.local.AiSettings
import com.example.aichat.data.model.Message
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
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
        private val geminiProvider =
    GeminiProvider(
        settings = settings,
        client = client
    )
    private val mistralProvider =
    MistralProvider(
        settings = settings,
        client = client
    )

    private val _customRequestCount = MutableStateFlow(0)
    val customRequestCount: StateFlow<Int> = _customRequestCount.asStateFlow()

    /**
     * Repository المسؤول عن جلب قوائم النماذج.
     *
     * تم فصل هذه المسؤولية عن ChatRepository
     * حتى يبقى هذا الكلاس مسؤولاً عن المحادثة وإرسال الرسائل.
     */
    private val modelCatalogRepository =
        ModelCatalogRepository(settings)

    // ============================================================
    // إعدادات تاريخ المحادثة وعدد التوكنات
    // ============================================================

    private fun getMaxHistory(
        provider: String,
        model: String
    ): Int = when {
        provider == "groq" -> 6

        provider == "mistral" -> 6

        provider == "openrouter" &&
            model.contains(":free") -> 6

        else -> 10
    }

    private fun getMaxTokens(
        provider: String,
        model: String
    ): Int = when {
        provider == "groq" -> 2048

        provider == "mistral" -> 2048

        provider == "openrouter" &&
            model.contains(":free") -> 2048

        else -> 4096
    }

    // ============================================================
    // جلب النماذج
    // ============================================================

    /**
     * الواجهة القديمة ما زالت موجودة حتى لا نكسر
     * أي كود يستدعي ChatRepository.getAvailableModels().
     *
     * التنفيذ الفعلي أصبح الآن داخل ModelCatalogRepository.
     */
    suspend fun getAvailableModels(
        provider: String,
        forImages: Boolean = false
    ) = withContext(Dispatchers.IO) {

        val apiKey = when (provider.lowercase().trim()) {

            "gemini" ->
                settings.geminiKey

            "openrouter" ->
                settings.openrouterKey

            "openai" ->
                settings.openaiKey

            "mistral" ->
                settings.mistralKey

            "groq" ->
                settings.groqKey

            "nvidia" ->
                settings.nvidiaKey

            "huggingface",
            "hugging face",
            "hf" ->
                settings.huggingfaceKey

            else ->
                ""
        }

        modelCatalogRepository.getModels(
            provider = provider,
            apiKey = apiKey
        )
    }

    // ============================================================
    // Hugging Face - إرسال الرسائل
    // ============================================================

    private fun sendHuggingFace(
        history: List<Message>,
        userMessage: String
    ): String {

        val apiKey = settings.huggingfaceKey.trim()
        val model = settings.huggingfaceModel.trim()

        if (apiKey.isBlank()) {
            throw IOException(
                "Hugging Face: أدخل API Token\n" +
                    "من: huggingface.co/settings/tokens"
            )
        }

        if (model.isBlank()) {
            throw IOException(
                "Hugging Face: اختر نموذجاً"
            )
        }

        val messages = JSONArray()

        messages.put(
            JSONObject().apply {
                put("role", "system")
                put(
                    "content",
                    "You are a helpful AI assistant."
                )
            }
        )

        history.takeLast(6).forEach { msg ->
            messages.put(
                JSONObject().apply {
                    put("role", msg.role)
                    put("content", msg.content)
                }
            )
        }

        messages.put(
            JSONObject().apply {
                put("role", "user")
                put("content", userMessage)
            }
        )

        val requestJson = JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("max_tokens", 1024)
            put("temperature", 0.7)
            put("stream", false)
        }

        val request = Request.Builder()
            .url(
                "https://router.huggingface.co/v1/chat/completions"
            )
            .post(
                requestJson.toString()
                    .toRequestBody(
                        "application/json".toMediaType()
                    )
            )
            .addHeader(
                "Authorization",
                "Bearer $apiKey"
            )
            .addHeader(
                "Content-Type",
                "application/json"
            )
            .build()

        client.newCall(request).execute().use { response ->

            val body = response.body?.string().orEmpty()

            if (!response.isSuccessful) {

                val msg = runCatching {
                    val j = JSONObject(body)

                    j.optJSONObject("error")
                        ?.optString("message")
                        ?: j.optString("error", body)
                        ?: j.optString("message", body)

                }.getOrDefault(body)

                throw IOException(
                    when (response.code) {

                        401 ->
                            "❌ HuggingFace: التوكن غير صحيح"

                        403 ->
                            "❌ HuggingFace: لا توجد صلاحية\n" +
                                "تأكد من قبول شروط النموذج على " +
                                "huggingface.co/$model"

                        404 ->
                            "❌ HuggingFace: النموذج غير موجود\n$model"

                        422 ->
                            "❌ HuggingFace: النموذج لا يدعم المحادثة\n" +
                                "جرب نموذجاً آخر"

                        429 ->
                            "⚠️ HuggingFace: تجاوزت الحد المجاني"

                        503 ->
                            "⏳ HuggingFace: الخادم مشغول - حاول لاحقاً"

                        else ->
                            "HuggingFace ${response.code}: $msg"
                    }
                )
            }

            val text =
                JSONObject(body)
                    .optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content", "")
                    ?.trim()

            return text?.takeIf {
                it.isNotBlank()
            } ?: throw IOException(
                "HuggingFace: الرد فارغ\n${body.take(200)}"
            )
        }
    }

    // ============================================================
    // إرسال الرسالة الرئيسية
    // ============================================================

    suspend fun sendMessage(
        history: List<Message>,
        userMessage: String,
        imageBase64: String? = null
    ): String = withContext(Dispatchers.IO) {

        when (settings.provider.lowercase().trim()) {

            "gemini" ->
              geminiProvider.send(
                history,
                userMessage,
               imageBase64
    )

            "openrouter" ->
                sendOpenAICompatible(
                    baseUrl =
                        "https://openrouter.ai/api/v1/chat/completions",
                    apiKey = settings.openrouterKey,
                    model = settings.openrouterModel,
                    history = history,
                    userMessage = userMessage,
                    imageBase64 = imageBase64,
                    providerName = "OpenRouter",
                    extraHeaders = mapOf(
                        "HTTP-Referer" to "https://github.com/",
                        "X-Title" to "AiChat"
                    )
                )

            "openai" ->
                sendOpenAICompatible(
                    baseUrl =
                        "https://api.openai.com/v1/chat/completions",
                    apiKey = settings.openaiKey,
                    model = settings.openaiModel,
                    history = history,
                    userMessage = userMessage,
                    imageBase64 = imageBase64,
                    providerName = "OpenAI"
                )

            "mistral" ->
    mistralProvider.send(
        history,
        userMessage,
        imageBase64
    )

            "huggingface" ->
                sendHuggingFace(
                    history,
                    userMessage
                )

            "groq" ->
                sendOpenAICompatible(
                    baseUrl =
                        "https://api.groq.com/openai/v1/chat/completions",
                    apiKey = settings.groqKey,
                    model = settings.groqModel,
                    history = history,
                    userMessage = userMessage,
                    imageBase64 = imageBase64,
                    providerName = "Groq"
                )

            "nvidia" ->
                sendOpenAICompatible(
                    baseUrl =
                        "https://integrate.api.nvidia.com/v1/chat/completions",
                    apiKey = settings.nvidiaKey,
                    model = settings.nvidiaModel,
                    history = history,
                    userMessage = userMessage,
                    imageBase64 = imageBase64,
                    providerName = "NVIDIA"
                )

            "ollama" ->
                sendOllama(userMessage)

            "custom" -> {

                val url = settings.customUrl.trim()

                if (url.isBlank()) {
                    throw IOException(
                        "Custom: رابط الخادم فارغ"
                    )
                }

                sendOpenAICompatible(
                    baseUrl = url,
                    apiKey = settings.customKey,
                    model = settings.customModel,
                    history = history,
                    userMessage = userMessage,
                    imageBase64 = imageBase64,
                    providerName = "Custom",
                    forceVision = true
                )
            }

            else ->
                throw IOException(
                    "مزود غير معروف: ${settings.provider}"
                )
        }
    }

    // ============================================================
    // Ollama
    // ============================================================

    private fun sendOllama(
        userMessage: String
    ): String {

        val model =
            settings.ollamaModel
                .trim()
                .ifBlank { "qwen2.5:1.5b" }

        if (userMessage.isBlank()) {
            throw IOException(
                "Ollama: الرسالة فارغة"
            )
        }

        val requestJson = JSONObject().apply {
            put("model", model)
            put("prompt", userMessage)
            put("stream", false)
        }

        val request = Request.Builder()
            .url(
                "http://127.0.0.1:11434/api/generate"
            )
            .post(
                requestJson.toString()
                    .toRequestBody(
                        "application/json".toMediaType()
                    )
            )
            .addHeader(
                "Content-Type",
                "application/json"
            )
            .build()

        client.newCall(request).execute().use { response ->

            val body =
                response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                throw IOException(
                    "Ollama ${response.code}: " +
                        body.take(300)
                )
            }

            val text =
                runCatching {
                    JSONObject(body)
                        .optString(
                            "response",
                            ""
                        )
                        .trim()
                }.getOrElse {
                    throw IOException(
                        "Ollama: استجابة غير صالحة\n" +
                            body.take(300)
                    )
                }

            return text.takeIf {
                it.isNotBlank()
            } ?: throw IOException(
                "Ollama: الرد فارغ"
            )
        }
    }

    // ============================================================
    // OpenAI Compatible - إرسال
    // ============================================================

    private fun sendOpenAICompatible(
        baseUrl: String,
        apiKey: String,
        model: String,
        history: List<Message>,
        userMessage: String,
        imageBase64: String?,
        providerName: String,
        extraHeaders: Map<String, String> = emptyMap(),
        forceVision: Boolean = false
    ): String {

        val cleanModel = model.trim()

        if (apiKey.isBlank()) {
            throw IOException(
                "$providerName: المفتاح فارغ"
            )
        }

        if (cleanModel.isBlank()) {
            throw IOException(
                "$providerName: النموذج فارغ"
            )
        }

        if (baseUrl.isBlank()) {
            throw IOException(
                "$providerName: الرابط فارغ"
            )
        }

        val provider =
            providerName.lowercase()

        val maxHistory =
            getMaxHistory(
                provider,
                cleanModel
            )

        val maxTokens =
            getMaxTokens(
                provider,
                cleanModel
            )

        val messages = JSONArray()

        messages.put(
            JSONObject().apply {
                put("role", "system")
                put(
                    "content",
                    "You are a helpful AI assistant."
                )
            }
        )

        history.takeLast(maxHistory).forEach { msg ->

            messages.put(
                JSONObject().apply {
                    put("role", msg.role)
                    put("content", msg.content)
                }
            )
        }

        val hasImage =
            imageBase64 != null &&
                (
                    forceVision ||
                        supportsVision(cleanModel)
                )

        if (hasImage) {

            messages.put(
                JSONObject().apply {

                    put("role", "user")

                    put(
                        "content",
                        JSONArray().apply {

                            put(
                                JSONObject().apply {

                                    put(
                                        "type",
                                        "image_url"
                                    )

                                    put(
                                        "image_url",
                                        JSONObject().apply {
                                            put(
                                                "url",
                                                "data:image/jpeg;base64,$imageBase64"
                                            )
                                            put(
                                                "detail",
                                                "low"
                                            )
                                        }
                                    )
                                }
                            )

                            put(
                                JSONObject().apply {
                                    put(
                                        "type",
                                        "text"
                                    )
                                    put(
                                        "text",
                                        userMessage
                                    )
                                }
                            )
                        }
                    )
                }
            )

        } else {

            messages.put(
                JSONObject().apply {
                    put("role", "user")
                    put("content", userMessage)
                }
            )
        }

        val requestJson = JSONObject().apply {
            put("model", cleanModel)
            put("messages", messages)
            put("temperature", 0.7)
            put("max_tokens", maxTokens)
        }

        val requestBuilder =
            Request.Builder()
                .url(baseUrl)
                .post(
                    requestJson.toString()
                        .toRequestBody(
                            "application/json".toMediaType()
                        )
                )
                .addHeader(
                    "Authorization",
                    "Bearer $apiKey"
                )
                .addHeader(
                    "Content-Type",
                    "application/json"
                )

        extraHeaders.forEach { (key, value) ->
            requestBuilder.addHeader(key, value)
        }

        client.newCall(
            requestBuilder.build()
        ).execute().use { response ->

            val body =
                response.body?.string().orEmpty()

            if (!response.isSuccessful) {

                val msg = runCatching {

                    val j = JSONObject(body)

                    j.optJSONObject("error")
                        ?.optString("message")
                        ?: j.optString(
                            "message",
                            body
                        )

                }.getOrDefault(body)

                throw IOException(
                    when (response.code) {

                        429 ->
                            "⚠️ $providerName: تجاوزت حد الطلبات"

                        401 ->
                            "❌ $providerName: المفتاح غير صحيح"

                        403 ->
                            "❌ $providerName: لا توجد صلاحية"

                        422 ->
                            "❌ $providerName: صيغة خاطئة: $msg"

                        500 ->
                            "❌ $providerName: خطأ في الخادم"

                        else ->
                            "$providerName ${response.code}: $msg"
                    }
                )
            }

            val text =
                JSONObject(body)
                    .optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content", "")
                    ?.trim()

            val result =
                text?.takeIf {
                    it.isNotBlank()
                } ?: throw IOException(
                    "$providerName: الرد فارغ"
                )

            if (
                providerName.equals(
                    "Custom",
                    ignoreCase = true
                )
            ) {
                _customRequestCount.update {
                    it + 1
                }
            }

            return result
        }
    }

    // ============================================================
    // Vision
    // ============================================================

    private fun supportsVision(
        model: String
    ): Boolean {

        val m = model.lowercase()

        return listOf(
            "gpt-4o",
            "gpt-4-turbo",
            "gpt-4-vision",
            "gpt-4.1",
            "gpt-5",
            "gemini",
            "claude-3",
            "claude-3.5",
            "claude-3.7",
            "pixtral",
            "llava",
            "vision",
            "qwen-vl",
            "qwen2-vl",
            "qwen2.5-vl",
            "minicpm-v",
            "internvl",
            "phi-3-vision",
            "phi-4-vision",
            "moondream",
            "bakllava",
            "cogvlm",
            "yi-vl"
        ).any {
            m.contains(it)
        }
    }
}
