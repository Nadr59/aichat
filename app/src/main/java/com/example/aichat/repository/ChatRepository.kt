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
import com.example.aichat.data.model.ModelInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

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
    // عداد طلبات Custom
    // ============================================================

    private val _customRequestCount = MutableStateFlow(0)
    val customRequestCount: StateFlow<Int> = _customRequestCount.asStateFlow()

    // ============================================================
    // حدود النماذج
    // ============================================================

    private fun getMaxHistory(provider: String, model: String): Int = when {
        provider == "groq" -> 6
        provider == "horde" -> 4
        provider == "mistral" -> 6
        provider == "openrouter" && model.contains(":free") -> 6
        else -> 10
    }

    private fun getMaxTokens(provider: String, model: String): Int = when {
        provider == "groq" -> 2048
        provider == "horde" -> 512
        provider == "mistral" -> 2048
        provider == "openrouter" && model.contains(":free") -> 2048
        else -> 4096
    }

    // ============================================================
    // جلب النماذج ديناميكيًا
    // ============================================================

    suspend fun getAvailableModels(
        provider: String,
        forImages: Boolean = false
    ): List<ModelInfo> = withContext(Dispatchers.IO) {

        when (provider.lowercase().trim()) {
            "gemini" -> getGeminiModels()
            "openrouter" -> getOpenRouterModels(forImages)
            "openai" -> getOpenAIModels()
            "mistral" -> getMistralModels()
            "groq" -> getGroqModels()
            "nvidia" -> getNvidiaModels()
            "huggingface", "hugging face", "hf" -> getHuggingFaceModels()
            "horde" -> getHordeModels(forImages)

            // Ollama محلي ولا يحتاج جلب قائمة نماذج من الإنترنت
            "ollama" -> emptyList()

            else -> emptyList()
        }
    }

    // ============================================================
    // Gemini Models
    // ============================================================

    private fun getGeminiModels(): List<ModelInfo> {

        val apiKey = settings.geminiKey.trim()
        if (apiKey.isBlank()) throw IOException("Gemini: أدخل API Key أولاً")

        val request = Request.Builder()
            .url(
                "https://generativelanguage.googleapis.com/v1beta/models" +
                        "?key=$apiKey&pageSize=100"
            )
            .get()
            .build()

        client.newCall(request).execute().use { response ->

            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("Gemini ${response.code}: ${body.take(300)}")
            }

            val root = JSONObject(body)
            val models = root.optJSONArray("models") ?: return emptyList()
            val result = mutableListOf<ModelInfo>()

            for (i in 0 until models.length()) {

                val obj = models.optJSONObject(i) ?: continue
                val name = obj.optString("name").removePrefix("models/")
                val displayName = obj.optString("displayName").ifBlank { name }
                val methods = obj.optJSONArray("supportedGenerationMethods")

                var supportsGenerateContent = false

                if (methods != null) {
                    for (j in 0 until methods.length()) {
                        if (methods.optString(j).equals("generateContent", true)) {
                            supportsGenerateContent = true
                            break
                        }
                    }
                }

                if (!supportsGenerateContent) continue

                val lower = name.lowercase()

                result += ModelInfo(
                    id = name,
                    name = displayName,
                    provider = "gemini",
                    isFree = lower.contains("flash") || lower.contains("lite"),
                    supportsVision =
                        lower.contains("flash") ||
                        lower.contains("pro") ||
                        lower.contains("image"),
                    supportsImageGeneration = lower.contains("image"),
                    recommended =
                        lower.contains("3.8") ||
                        lower.contains("3.7") ||
                        lower.contains("3.6"),
                    contextLength = obj.optLong("inputTokenLimit", 0L)
                )
            }

            return result
                .distinctBy { it.id }
                .sortedWith(
                    compareByDescending<ModelInfo> { it.recommended }
                        .thenByDescending { it.isFree }
                        .thenBy { it.name }
                )
        }
    }

    // ============================================================
    // OpenRouter Models
    // ============================================================

    private fun getOpenRouterModels(forImages: Boolean): List<ModelInfo> {

        val url = if (forImages) {
            "https://openrouter.ai/api/v1/models?output_modalities=image"
        } else {
            "https://openrouter.ai/api/v1/models?output_modalities=text"
        }

        val requestBuilder = Request.Builder().url(url).get()

        val apiKey = settings.openrouterKey.trim()
        if (apiKey.isNotBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer $apiKey")
        }

        client.newCall(requestBuilder.build()).execute().use { response ->

            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("OpenRouter ${response.code}: ${body.take(300)}")
            }

            val data = JSONObject(body).optJSONArray("data") ?: return emptyList()
            val result = mutableListOf<ModelInfo>()

            for (i in 0 until data.length()) {

                val obj = data.optJSONObject(i) ?: continue
                val id = obj.optString("id").trim()
                if (id.isBlank()) continue

                val name = obj.optString("name").ifBlank { id }
                val architecture = obj.optJSONObject("architecture")

                val inputModalities =
                    architecture?.optJSONArray("input_modalities")

                val outputModalities =
                    architecture?.optJSONArray("output_modalities")

                val supportsVision =
                    jsonArrayContains(inputModalities, "image")

                val supportsImageGeneration =
                    jsonArrayContains(outputModalities, "image")

                val pricing = obj.optJSONObject("pricing")

                val promptPrice =
                    pricing?.optString("prompt", "1") ?: "1"

                val completionPrice =
                    pricing?.optString("completion", "1") ?: "1"

                val requestPrice =
                    pricing?.optString("request", "1") ?: "1"

                val imagePrice =
                    pricing?.optString("image", "0") ?: "0"

                val isFree =
                    isZeroPrice(promptPrice) &&
                    isZeroPrice(completionPrice) &&
                    isZeroPrice(requestPrice) &&
                    (!supportsImageGeneration || isZeroPrice(imagePrice))

                val lower = "$id $name".lowercase()

                val recommended =
                    lower.contains("gemini-3") ||
                    lower.contains("gpt-5") ||
                    lower.contains("claude") ||
                    lower.contains("qwen3") ||
                    lower.contains("nemotron") ||
                    lower.contains("deepseek")

                result += ModelInfo(
                    id = id,
                    name = name,
                    provider = "openrouter",
                    isFree = isFree,
                    supportsVision = supportsVision,
                    supportsImageGeneration = supportsImageGeneration,
                    recommended = recommended,
                    contextLength = obj.optLong("context_length", 0L)
                )
            }

            return result
                .distinctBy { it.id }
                .sortedWith(
                    compareByDescending<ModelInfo> { it.recommended }
                        .thenByDescending { it.isFree }
                        .thenBy { it.name }
                )
        }
    }

    // ============================================================
    // Mistral Models
    // ============================================================

    private fun getMistralModels(): List<ModelInfo> {

        val apiKey = settings.mistralKey.trim()
        if (apiKey.isBlank()) throw IOException("Mistral: أدخل API Key أولاً")

        val request = Request.Builder()
            .url("https://api.mistral.ai/v1/models")
            .get()
            .addHeader("Authorization", "Bearer $apiKey")
            .build()

        client.newCall(request).execute().use { response ->

            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("Mistral ${response.code}: ${body.take(300)}")
            }

            val data = JSONObject(body).optJSONArray("data") ?: return emptyList()
            val result = mutableListOf<ModelInfo>()

            for (i in 0 until data.length()) {

                val obj = data.optJSONObject(i) ?: continue
                val id = obj.optString("id")
                if (id.isBlank()) continue

                val capabilities = obj.optJSONObject("capabilities")
                val chat =
                    capabilities?.optBoolean("completion_chat", false) ?: false

                if (!chat) continue

                val vision =
                    capabilities?.optBoolean("vision", false) ?: false

                val lower = id.lowercase()

                result += ModelInfo(
                    id = id,
                    name = id,
                    provider = "mistral",
                    isFree =
                        lower.contains("small") ||
                        lower.contains("ministral"),
                    supportsVision = vision,
                    recommended =
                        lower.contains("medium") ||
                        lower.contains("small") ||
                        lower.contains("ministral"),
                    contextLength =
                        obj.optLong("max_context_length", 0L)
                )
            }

            return result
                .distinctBy { it.id }
                .sortedWith(
                    compareByDescending<ModelInfo> { it.recommended }
                        .thenByDescending { it.isFree }
                        .thenBy { it.name }
                )
        }
    }

    // ============================================================
    // Hugging Face Models
    // ============================================================

    private fun getHuggingFaceModels(): List<ModelInfo> {

        val apiKey = settings.huggingfaceKey.trim()
        if (apiKey.isBlank()) {
            throw IOException("Hugging Face: أدخل API Token أولاً")
        }

        val request = Request.Builder()
            .url("https://router.huggingface.co/v1/models")
            .get()
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Accept", "application/json")
            .build()

        client.newCall(request).execute().use { response ->

            val body = response.body?.string().orEmpty()

            if (!response.isSuccessful) {

                val message = runCatching {
                    val json = JSONObject(body)

                    json.optJSONObject("error")
                        ?.optString("message")
                        ?.takeIf { it.isNotBlank() }
                        ?: json.optString("message")
                            .takeIf { it.isNotBlank() }
                        ?: body

                }.getOrDefault(body)

                throw IOException(
                    when (response.code) {
                        401 ->
                            "❌ Hugging Face: التوكن غير صحيح"

                        403 ->
                            "❌ Hugging Face: لا توجد صلاحية لاستخدام Inference Providers"

                        429 ->
                            "⚠️ Hugging Face: تجاوزت الحد المسموح"

                        else ->
                            "Hugging Face ${response.code}: $message"
                    }
                )
            }

            val data =
                JSONObject(body).optJSONArray("data")
                    ?: return emptyList()

            val models = mutableListOf<ModelInfo>()

            for (i in 0 until data.length()) {

                val model = data.optJSONObject(i) ?: continue
                val id = model.optString("id").trim()

                if (id.isBlank()) continue

                val name =
                    model.optString("name")
                        .trim()
                        .ifBlank { id }

                val architecture =
                    model.optJSONObject("architecture")

                val supportsVision =
                    jsonArrayContains(
                        architecture?.optJSONArray("input_modalities"),
                        "image"
                    )

                val supportsImageGeneration =
                    jsonArrayContains(
                        architecture?.optJSONArray("output_modalities"),
                        "image"
                    )

                val providers =
                    model.optJSONArray("providers")

                var hasLiveProvider = false
                var isFree = false
                var contextLength =
                    model.optLong("context_length", 0L)

                if (providers != null) {

                    for (j in 0 until providers.length()) {

                        val provider =
                            providers.optJSONObject(j)
                                ?: continue

                        if (
                            provider.optString("status")
                                .equals("live", ignoreCase = true)
                        ) {

                            hasLiveProvider = true

                            if (
                                provider.optBoolean(
                                    "is_free",
                                    false
                                )
                            ) {
                                isFree = true
                            }

                            if (contextLength <= 0L) {
                                contextLength =
                                    provider.optLong(
                                        "context_length",
                                        0L
                                    )
                            }
                        }
                    }
                }

                if (!hasLiveProvider) continue

                val lowerName =
                    "$id $name".lowercase()

                val recommended =
                    lowerName.contains("qwen3") ||
                    lowerName.contains("qwen2.5") ||
                    lowerName.contains("deepseek") ||
                    lowerName.contains("llama") ||
                    lowerName.contains("gemma") ||
                    lowerName.contains("mistral") ||
                    lowerName.contains("kimi") ||
                    lowerName.contains("glm")

                models += ModelInfo(
                    id = id,
                    name = name,
                    provider = "huggingface",
                    isFree = isFree,
                    supportsVision = supportsVision,
                    supportsImageGeneration =
                        supportsImageGeneration,
                    recommended = recommended,
                    contextLength = contextLength
                )
            }

            return models
                .distinctBy { it.id }
                .sortedWith(
                    compareByDescending<ModelInfo> { it.recommended }
                        .thenByDescending { it.isFree }
                        .thenBy { it.name }
                )
        }
    }

    // ============================================================
    // NVIDIA Models
    // ============================================================

    private fun getNvidiaModels(
        forImages: Boolean = false
    ): List<ModelInfo> {

        val apiKey = settings.nvidiaKey.trim()

        if (apiKey.isBlank()) {
            throw IOException("NVIDIA: أدخل API Key أولاً")
        }

        val request = Request.Builder()
            .url("https://integrate.api.nvidia.com/v1/models")
            .get()
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Accept", "application/json")
            .build()

        client.newCall(request).execute().use { response ->

            val body = response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                throw IOException(
                    "NVIDIA ${response.code}: ${body.take(300)}"
                )
            }

            val data =
                JSONObject(body).optJSONArray("data")
                    ?: return emptyList()

            val result = mutableListOf<ModelInfo>()

            for (i in 0 until data.length()) {

                val obj = data.optJSONObject(i) ?: continue
                val id = obj.optString("id").trim()

                if (id.isBlank()) continue

                val name =
                    obj.optString("name")
                        .trim()
                        .ifBlank { id }

                val lower =
                    "$id $name".lowercase()

                if (
                    lower.contains("guard") ||
                    lower.contains("safety") ||
                    lower.contains("jailbreak") ||
                    lower.contains("content-safety") ||
                    lower.contains("moderation") ||
                    lower.contains("pii")
                ) continue

                val supportsVision =
                    lower.contains("vision") ||
                    lower.contains("-vl") ||
                    lower.contains("vl-") ||
                    lower.contains("visual") ||
                    lower.contains("multimodal") ||
                    lower.contains("image-text") ||
                    lower.contains("image_text") ||
                    lower.contains("nemotron-nano-12b-v2-vl") ||
                    lower.contains("gemma-3") ||
                    lower.contains("gemma-3n") ||
                    lower.contains("gemma-4") ||
                    lower.contains("qwen3-vl") ||
                    lower.contains("qwen2-vl") ||
                    lower.contains("llama-3.2-11b-vision") ||
                    lower.contains("llama-3.2-90b-vision")

                val recommended =
                    lower.contains("nemotron") ||
                    lower.contains("qwen3") ||
                    lower.contains("qwen2.5") ||
                    lower.contains("llama-3.3") ||
                    lower.contains("llama-3.1") ||
                    lower.contains("gemma-4") ||
                    lower.contains("gemma-3") ||
                    lower.contains("mistral") ||
                    lower.contains("deepseek")

                if (forImages && !supportsVision) continue

                result += ModelInfo(
                    id = id,
                    name = name,
                    provider = "nvidia",
                    isFree = false,
                    supportsVision = supportsVision,
                    supportsImageGeneration = false,
                    recommended = recommended,
                    contextLength =
                        obj.optLong(
                            "context_length",
                            obj.optLong(
                                "context_window",
                                0L
                            )
                        )
                )
            }

            return result
                .distinctBy { it.id }
                .sortedWith(
                    compareByDescending<ModelInfo> {
                        it.recommended
                    }
                        .thenByDescending {
                            it.supportsVision
                        }
                        .thenBy { it.name }
                )
        }
    }

    // ============================================================
    // Groq Models
    // ============================================================

    private fun getGroqModels(): List<ModelInfo> {

        val apiKey = settings.groqKey.trim()

        if (apiKey.isBlank()) {
            throw IOException("Groq: أدخل API Key أولاً")
        }

        val request = Request.Builder()
            .url("https://api.groq.com/openai/v1/models")
            .get()
            .addHeader("Authorization", "Bearer $apiKey")
            .build()

        client.newCall(request).execute().use { response ->

            val body = response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                throw IOException(
                    "Groq ${response.code}: ${body.take(300)}"
                )
            }

            val data =
                JSONObject(body).optJSONArray("data")
                    ?: return emptyList()

            val result = mutableListOf<ModelInfo>()

            for (i in 0 until data.length()) {

                val obj = data.optJSONObject(i) ?: continue
                val id = obj.optString("id")

                if (id.isBlank()) continue

                val lower = id.lowercase()

                if (
                    lower.contains("whisper") ||
                    lower.contains("guard")
                ) continue

                result += ModelInfo(
                    id = id,
                    name = id,
                    provider = "groq",
                    isFree = false,
                    supportsVision =
                        lower.contains("vision") ||
                        lower.contains("qwen3"),
                    recommended =
                        lower.contains("gpt-oss") ||
                        lower.contains("qwen3"),
                    contextLength =
                        obj.optLong(
                            "context_window",
                            0L
                        )
                )
            }

            return result
                .distinctBy { it.id }
                .sortedWith(
                    compareByDescending<ModelInfo> {
                        it.recommended
                    }
                        .thenBy { it.name }
                )
        }
    }

    // ============================================================
    // Horde Models
    // ============================================================

    private fun getHordeModels(
        forImages: Boolean
    ): List<ModelInfo> {

        val request = Request.Builder()
            .url("https://aihorde.net/api/v2/status/models")
            .get()
            .addHeader(
                "Client-Agent",
                "AiChat:1.0:anonymous"
            )
            .build()

        client.newCall(request).execute().use { response ->

            val body = response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                throw IOException(
                    "Horde ${response.code}: ${body.take(300)}"
                )
            }

            val array = JSONArray(body)
            val result = mutableListOf<ModelInfo>()

            for (i in 0 until array.length()) {

                val obj = array.optJSONObject(i) ?: continue
                val name = obj.optString("name").trim()

                if (name.isBlank()) continue

                val lower = name.lowercase()

                val imageModel =
                    lower.contains("stable diffusion") ||
                    lower.contains("sdxl") ||
                    lower.contains("flux") ||
                    lower.contains("dreamshaper") ||
                    lower.contains("realistic")

                if (forImages != imageModel) continue

                result += ModelInfo(
                    id = name,
                    name = name,
                    provider = "horde",
                    isFree = true,
                    supportsVision = false,
                    supportsImageGeneration = imageModel,
                    recommended =
                        lower.contains("flux") ||
                        lower.contains("sdxl") ||
                        lower.contains("stable diffusion")
                )
            }

            return result
                .distinctBy { it.id }
                .sortedWith(
                    compareByDescending<ModelInfo> {
                        it.recommended
                    }
                        .thenBy { it.name }
                )
        }
    }

    // ============================================================
    // OpenAI Models
    // ============================================================

    private fun getOpenAIModels(): List<ModelInfo> {

        val apiKey = settings.openaiKey.trim()

        if (apiKey.isBlank()) {
            throw IOException("OpenAI: أدخل API Key أولاً")
        }

        val request = Request.Builder()
            .url("https://api.openai.com/v1/models")
            .get()
            .addHeader(
                "Authorization",
                "Bearer $apiKey"
            )
            .build()

        client.newCall(request).execute().use { response ->

            val body = response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                throw IOException(
                    "OpenAI ${response.code}: ${body.take(300)}"
                )
            }

            val data =
                JSONObject(body).optJSONArray("data")
                    ?: return emptyList()

            val result = mutableListOf<ModelInfo>()

            for (i in 0 until data.length()) {

                val obj = data.optJSONObject(i) ?: continue
                val id = obj.optString("id")

                if (id.isBlank()) continue

                val lower = id.lowercase()

                val usable =
                    lower.startsWith("gpt-") ||
                    lower.startsWith("o")

                if (!usable) continue

                result += ModelInfo(
                    id = id,
                    name = id,
                    provider = "openai",
                    isFree = false,
                    supportsVision =
                        lower.contains("gpt-5") ||
                        lower.contains("gpt-4o") ||
                        lower.contains("gpt-4.1"),
                    recommended =
                        lower.contains("gpt-5") ||
                        lower.contains("gpt-4.1")
                )
            }

            return result
                .distinctBy { it.id }
                .sortedWith(
                    compareByDescending<ModelInfo> {
                        it.recommended
                    }
                        .thenBy { it.name }
                )
        }
    }

    // ============================================================
    // Helper Functions
    // ============================================================

    private fun jsonArrayContains(
        array: JSONArray?,
        value: String
    ): Boolean {

        if (array == null) return false

        for (i in 0 until array.length()) {
            if (
                array.optString(i)
                    .equals(value, ignoreCase = true)
            ) {
                return true
            }
        }

        return false
    }

    private fun isZeroPrice(value: String): Boolean {
        return value.trim()
            .toDoubleOrNull()
            ?.let { it == 0.0 }
            ?: false
    }

    // ============================================================
    // Hugging Face - Send Message
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

        messages.put(JSONObject().apply {
            put("role", "system")
            put(
                "content",
                "You are a helpful AI assistant."
            )
        })

        history.takeLast(6).forEach { msg ->
            messages.put(JSONObject().apply {
                put("role", msg.role)
                put("content", msg.content)
            })
        }

        messages.put(JSONObject().apply {
            put("role", "user")
            put("content", userMessage)
        })

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

            return text?.takeIf { it.isNotBlank() }
                ?: throw IOException(
                    "HuggingFace: الرد فارغ\n${body.take(200)}"
                )
        }
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

            "gemini" ->
                sendGemini(
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
                        "HTTP-Referer" to
                                "https://github.com/",
                        "X-Title" to
                                "AiChat"
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
                sendMistral(
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

            "horde" ->
                sendHordeText(
                    history,
                    userMessage
                )

            // ====================================================
            // Ollama - محلي على الهاتف عبر Termux
            // ====================================================

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
                    // Custom يرسل الصورة دائماً إذا كانت موجودة
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
    // Ollama - اتصال محلي نصي
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
    // توليد الصور
    // ============================================================

    suspend fun generateImage(
        prompt: String
    ): ImageResult = withContext(Dispatchers.IO) {

        when (settings.imageProvider.lowercase().trim()) {

            "openai" ->
                generateImageOpenAI(prompt)

            "openrouter" ->
                generateImageOpenRouter(prompt)

            "horde" ->
                generateImageHorde(prompt)

            "custom" ->
                generateImageCustom(prompt)

            else ->
                throw IOException(
                    "مزود صور غير معروف: " +
                            settings.imageProvider
                )
        }
    }

    // ============================================================
    // Gemini - Send
    // ============================================================

    private fun sendGemini(
        history: List<Message>,
        userMessage: String,
        imageBase64: String?
    ): String {

        val apiKey = settings.geminiKey.trim()

        if (apiKey.isBlank()) {
            throw IOException(
                "Gemini: المفتاح فارغ"
            )
        }

        val model =
            settings.geminiModel
                .trim()
                .ifBlank { "gemini-3.6-flash" }

        val endpoint =
            "https://generativelanguage.googleapis.com/v1beta/models/" +
                    "$model:generateContent?key=$apiKey"

        val contents = JSONArray()

        history.takeLast(10).forEach { msg ->

            val role =
                if (msg.role == "user") {
                    "user"
                } else {
                    "model"
                }

            val parts = JSONArray()

            parts.put(
                JSONObject().apply {
                    put("text", msg.content)
                }
            )

            contents.put(
                JSONObject().apply {
                    put("role", role)
                    put("parts", parts)
                }
            )
        }

        val newParts = JSONArray()

        if (imageBase64 != null) {

            newParts.put(
                JSONObject().apply {

                    put(
                        "inline_data",
                        JSONObject().apply {
                            put(
                                "mime_type",
                                "image/jpeg"
                            )
                            put(
                                "data",
                                imageBase64
                            )
                        }
                    )
                }
            )
        }

        newParts.put(
            JSONObject().apply {
                put("text", userMessage)
            }
        )

        contents.put(
            JSONObject().apply {
                put("role", "user")
                put("parts", newParts)
            }
        )

        val requestJson =
            JSONObject().apply {

                put(
                    "contents",
                    contents
                )

                put(
                    "generationConfig",
                    JSONObject().apply {
                        put(
                            "temperature",
                            0.7
                        )
                        put(
                            "maxOutputTokens",
                            8192
                        )
                        put(
                            "topP",
                            0.95
                        )
                    }
                )

                put(
                    "safetySettings",
                    JSONArray().apply {

                        listOf(
                            "HARM_CATEGORY_HARASSMENT",
                            "HARM_CATEGORY_HATE_SPEECH",
                            "HARM_CATEGORY_SEXUALLY_EXPLICIT",
                            "HARM_CATEGORY_DANGEROUS_CONTENT"
                        ).forEach { category ->

                            put(
                                JSONObject().apply {
                                    put(
                                        "category",
                                        category
                                    )
                                    put(
                                        "threshold",
                                        "BLOCK_ONLY_HIGH"
                                    )
                                }
                            )
                        }
                    }
                )
            }

        val request = Request.Builder()
            .url(endpoint)
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

                val msg = runCatching {
                    JSONObject(body)
                        .optJSONObject("error")
                        ?.optString("message")
                        ?: body
                }.getOrDefault(body)

                throw IOException(
                    when (response.code) {

                        429 ->
                            "⚠️ Gemini: تجاوزت حد الطلبات - انتظر دقيقة"

                        401 ->
                            "❌ Gemini: المفتاح غير صحيح"

                        else ->
                            "Gemini ${response.code}: $msg"
                    }
                )
            }

            val candidates =
                JSONObject(body)
                    .optJSONArray("candidates")

            if (
                candidates == null ||
                candidates.length() == 0
            ) {

                val reason =
                    JSONObject(body)
                        .optJSONObject("promptFeedback")
                        ?.optString(
                            "blockReason",
                            "غير معروف"
                        )

                throw IOException(
                    "Gemini: محجوب ($reason)"
                )
            }

            val candidate =
                candidates.optJSONObject(0)

            val finishReason =
                candidate?.optString(
                    "finishReason",
                    ""
                )

            val text =
                candidate
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts")
                    ?.optJSONObject(0)
                    ?.optString("text", "")
                    ?.trim()

            if (!text.isNullOrBlank()) {
                return text
            }

            throw IOException(
                when (finishReason) {

                    "SAFETY" ->
                        "Gemini: محجوب لأسباب أمان"

                    "MAX_TOKENS" ->
                        "Gemini: الرد طويل جداً"

                    else ->
                        "Gemini: رد فارغ ($finishReason)"
                }
            )
        }
    }

    // ============================================================
    // Mistral - Send
    // ============================================================

    private fun sendMistral(
        history: List<Message>,
        userMessage: String,
        imageBase64: String?
    ): String {

        val apiKey = settings.mistralKey.trim()
        val model = settings.mistralModel.trim()

        if (apiKey.isBlank()) {
            throw IOException(
                "Mistral: المفتاح فارغ"
            )
        }

        if (model.isBlank()) {
            throw IOException(
                "Mistral: النموذج فارغ"
            )
        }

        val messages = JSONArray()

        val maxHistory =
            getMaxHistory(
                "mistral",
                model
            )

        val maxTokens =
            getMaxTokens(
                "mistral",
                model
            )

        history.takeLast(maxHistory).forEach { msg ->

            messages.put(
                JSONObject().apply {
                    put("role", msg.role)
                    put("content", msg.content)
                }
            )
        }

        val isVision =
            model.contains("pixtral") ||
                    model.contains("vision")

        if (
            imageBase64 != null &&
            isVision
        ) {

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
                                        "text"
                                    )
                                    put(
                                        "text",
                                        userMessage
                                    )
                                }
                            )

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
                                        }
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
                    put(
                        "content",
                        userMessage
                    )
                }
            )
        }

        val requestJson =
            JSONObject().apply {

                put("model", model)
                put("messages", messages)
                put("temperature", 0.7)
                put("max_tokens", maxTokens)
            }

        val request = Request.Builder()
            .url(
                "https://api.mistral.ai/v1/chat/completions"
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
                            "⚠️ Mistral: تجاوزت حد الطلبات\n" +
                                    "النموذج: $model"

                        401 ->
                            "❌ Mistral: المفتاح غير صحيح"

                        422 ->
                            "❌ Mistral: صيغة خاطئة: $msg"

                        else ->
                            "Mistral ${response.code}: $msg"
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
                "Mistral: الرد فارغ"
            )
        }
    }

    // ============================================================
    // OpenAI Compatible - Send
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
                    put(
                        "content",
                        userMessage
                    )
                }
            )
        }

        val requestJson =
            JSONObject().apply {

                put("model", cleanModel)
                put("messages", messages)
                put("temperature", 0.7)
                put(
                    "max_tokens",
                    maxTokens
                )
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

        extraHeaders.forEach { (k, v) ->
            requestBuilder.addHeader(k, v)
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
    // supportsVision
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

    // ============================================================
    // AI Horde — النصوص
    // ============================================================

    private suspend fun sendHordeText(
        history: List<Message>,
        userMessage: String
    ): String {

        val apiKey =
            settings.hordeKey.ifBlank {
                "0000000000"
            }

        val model =
            settings.hordeTextModel.trim()

        val sb = StringBuilder()

        sb.append(
            "### System\n" +
                    "You are a helpful AI assistant.\n\n"
        )

        history.takeLast(4).forEach { msg ->

            when (msg.role) {

                "user" ->
                    sb.append(
                        "### Human\n" +
                                "${msg.content}\n\n"
                    )

                "assistant" ->
                    sb.append(
                        "### Assistant\n" +
                                "${msg.content}\n\n"
                    )
            }
        }

        sb.append(
            "### Human\n" +
                    "$userMessage\n\n" +
                    "### Assistant\n"
        )

        val requestJson =
            JSONObject().apply {

                put(
                    "prompt",
                    sb.toString()
                )

                put(
                    "models",
                    JSONArray().apply {
                        put(model)
                    }
                )

                put(
                    "params",
                    JSONObject().apply {
                        put(
                            "max_length",
                            512
                        )
                        put(
                            "max_context_length",
                            2048
                        )
                        put(
                            "temperature",
                            0.7
                        )
                        put(
                            "top_p",
                            0.9
                        )
                        put(
                            "rep_pen",
                            1.1
                        )
                    }
                )

                put(
                    "trusted_workers",
                    false
                )

                put(
                    "slow_workers",
                    true
                )
            }

        val request = Request.Builder()
            .url(
                "https://aihorde.net/api/v2/generate/text/async"
            )
            .post(
                requestJson.toString()
                    .toRequestBody(
                        "application/json".toMediaType()
                    )
            )
            .addHeader(
                "apikey",
                apiKey
            )
            .addHeader(
                "Content-Type",
                "application/json"
            )
            .addHeader(
                "Client-Agent",
                "AiChat:1.0:anonymous"
            )
            .build()

        val jobId =
            client.newCall(request)
                .execute()
                .use { response ->

                    val body =
                        response.body?.string().orEmpty()

                    if (!response.isSuccessful) {
                        throw IOException(
                            "Horde ${response.code}: $body"
                        )
                    }

                    JSONObject(body)
                        .optString("id", "")
                        .takeIf {
                            it.isNotBlank()
                        }
                        ?: throw IOException(
                            "Horde: لم يُرجع job_id"
                        )
                }

        return pollHordeText(
            jobId,
            apiKey
        )
    }

    private suspend fun pollHordeText(
        jobId: String,
        apiKey: String
    ): String {

        repeat(60) {

            delay(3000L)

            val req = Request.Builder()
                .url(
                    "https://aihorde.net/api/v2/generate/text/status/$jobId"
                )
                .get()
                .addHeader(
                    "apikey",
                    apiKey
                )
                .addHeader(
                    "Client-Agent",
                    "AiChat:1.0:anonymous"
                )
                .build()

            val obj =
                runCatching {

                    client.newCall(req)
                        .execute()
                        .use { r ->
                            JSONObject(
                                r.body
                                    ?.string()
                                    .orEmpty()
                            )
                        }

                }.getOrNull()
                    ?: return@repeat

            if (
                obj.optBoolean(
                    "faulted",
                    false
                )
            ) {
                throw IOException(
                    "Horde: فشلت المهمة"
                )
            }

            if (
                obj.optBoolean(
                    "done",
                    false
                )
            ) {

                val text =
                    obj.optJSONArray(
                        "generations"
                    )
                        ?.optJSONObject(0)
                        ?.optString(
                            "text",
                            ""
                        )
                        ?.trim()

                return text?.takeIf {
                    it.isNotBlank()
                } ?: throw IOException(
                    "Horde: النتيجة فارغة"
                )
            }
        }

        throw IOException(
            "Horde: انتهى الوقت بدون نتيجة"
        )
    }

    // ============================================================
    // AI Horde — الصور
    // ============================================================

    private suspend fun generateImageHorde(
        prompt: String
    ): ImageResult {

        val apiKey =
            settings.hordeKey.ifBlank {
                "0000000000"
            }

        val model =
            settings.hordeImageModel.trim()

        val requestJson =
            JSONObject().apply {

                put("prompt", prompt)

                put(
                    "models",
                    JSONArray().apply {
                        put(model)
                    }
                )

                put(
                    "params",
                    JSONObject().apply {
                        put("width", 512)
                        put("height", 512)
                        put("steps", 20)
                        put("cfg_scale", 7.5)
                        put(
                            "sampler_name",
                            "k_euler_a"
                        )
                        put("n", 1)
                        put("karras", true)
                    }
                )

                put("r2", true)
                put("shared", false)
                put("trusted_workers", false)
                put("slow_workers", true)
            }

        val request = Request.Builder()
            .url(
                "https://aihorde.net/api/v2/generate/async"
            )
            .post(
                requestJson.toString()
                    .toRequestBody(
                        "application/json".toMediaType()
                    )
            )
            .addHeader(
                "apikey",
                apiKey
            )
            .addHeader(
                "Content-Type",
                "application/json"
            )
            .addHeader(
                "Client-Agent",
                "AiChat:1.0:anonymous"
            )
            .build()

        val jobId =
            client.newCall(request)
                .execute()
                .use { response ->

                    val body =
                        response.body?.string().orEmpty()

                    if (!response.isSuccessful) {
                        throw IOException(
                            "Horde Image ${response.code}: $body"
                        )
                    }

                    JSONObject(body)
                        .optString("id", "")
                        .takeIf {
                            it.isNotBlank()
                        }
                        ?: throw IOException(
                            "Horde: لم يُرجع job_id"
                        )
                }

        return pollHordeImage(
            jobId,
            apiKey
        )
    }

    private suspend fun pollHordeImage(
        jobId: String,
        apiKey: String
    ): ImageResult {

        repeat(120) {

            delay(5000L)

            val checkReq =
                Request.Builder()
                    .url(
                        "https://aihorde.net/api/v2/generate/check/$jobId"
                    )
                    .get()
                    .addHeader(
                        "apikey",
                        apiKey
                    )
                    .addHeader(
                        "Client-Agent",
                        "AiChat:1.0:anonymous"
                    )
                    .build()

            val check =
                runCatching {

                    client.newCall(checkReq)
                        .execute()
                        .use { r ->
                            JSONObject(
                                r.body
                                    ?.string()
                                    .orEmpty()
                            )
                        }

                }.getOrNull()
                    ?: return@repeat

            if (
                check.optBoolean(
                    "faulted",
                    false
                )
            ) {
                throw IOException(
                    "Horde: فشل توليد الصورة"
                )
            }

            if (
                !check.optBoolean(
                    "done",
                    false
                )
            ) {
                return@repeat
            }

            val statusReq =
                Request.Builder()
                    .url(
                        "https://aihorde.net/api/v2/generate/status/$jobId"
                    )
                    .get()
                    .addHeader(
                        "apikey",
                        apiKey
                    )
                    .addHeader(
                        "Client-Agent",
                        "AiChat:1.0:anonymous"
                    )
                    .build()

            val status =
                client.newCall(statusReq)
                    .execute()
                    .use { r ->

                        val body =
                            r.body
                                ?.string()
                                .orEmpty()

                        if (!r.isSuccessful) {
                            throw IOException(
                                "Horde Status ${r.code}: $body"
                            )
                        }

                        JSONObject(body)
                    }

            val img =
                status.optJSONArray(
                    "generations"
                )
                    ?.optJSONObject(0)
                    ?.optString(
                        "img",
                        ""
                    )
                    ?: throw IOException(
                        "Horde: لا توجد صورة"
                    )

            return when {

                img.startsWith("http") ->
                    ImageResult(url = img)

                img.isNotBlank() ->
                    ImageResult(base64 = img)

                else ->
                    throw IOException(
                        "Horde: الصورة فارغة"
                    )
            }
        }

        throw IOException(
            "Horde: انتهى الوقت بدون صورة"
        )
    }

    // ============================================================
    // توليد الصور — OpenAI
    // ============================================================

    private fun generateImageOpenAI(
        prompt: String
    ): ImageResult {

        val apiKey = settings.openaiKey.trim()

        val model =
            settings.imageModel
                .trim()
                .ifBlank { "dall-e-3" }

        if (apiKey.isBlank()) {
            throw IOException(
                "OpenAI: المفتاح فارغ"
            )
        }

        val requestJson =
            JSONObject().apply {

                put("model", model)
                put("prompt", prompt)
                put("n", 1)

                put(
                    "size",
                    if (model == "dall-e-3") {
                        "1024x1024"
                    } else {
                        "512x512"
                    }
                )

                put(
                    "response_format",
                    "url"
                )

                if (model == "dall-e-3") {
                    put(
                        "quality",
                        "standard"
                    )
                }
            }

        val request = Request.Builder()
            .url(
                "https://api.openai.com/v1/images/generations"
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

            val body =
                response.body?.string().orEmpty()

            if (!response.isSuccessful) {

                val msg = runCatching {

                    JSONObject(body)
                        .optJSONObject("error")
                        ?.optString("message")
                        ?: body

                }.getOrDefault(body)

                throw IOException(
                    "OpenAI Image ${response.code}: $msg"
                )
            }

            val url =
                JSONObject(body)
                    .optJSONArray("data")
                    ?.optJSONObject(0)
                    ?.optString("url", "")
                    ?.takeIf {
                        it.isNotBlank()
                    }
                    ?: throw IOException(
                        "OpenAI: لم يُرجع رابط الصورة"
                    )

            return ImageResult(url = url)
        }
    }

    // ============================================================
    // توليد الصور — OpenRouter
    // ============================================================

    private fun generateImageOpenRouter(
        prompt: String
    ): ImageResult {

        val apiKey =
            settings.openrouterKey.trim()

        val model =
            settings.imageModel
                .trim()
                .ifBlank {
                    "black-forest-labs/flux-schnell:free"
                }

        if (apiKey.isBlank()) {
            throw IOException(
                "OpenRouter: المفتاح فارغ"
            )
        }

        val requestJson =
            JSONObject().apply {
                put("model", model)
                put("prompt", prompt)
                put("n", 1)
            }

        val request = Request.Builder()
            .url(
                "https://openrouter.ai/api/v1/images/generations"
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
            .addHeader(
                "HTTP-Referer",
                "https://github.com/"
            )
            .addHeader(
                "X-Title",
                "AiChat"
            )
            .build()

        client.newCall(request).execute().use { response ->

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
                    "OpenRouter Image ${response.code}: $msg"
                )
            }

            val root = JSONObject(body)

            root.optJSONArray("data")
                ?.optJSONObject(0)
                ?.let { d ->

                    d.optString(
                        "url",
                        ""
                    )
                        .takeIf {
                            it.isNotBlank()
                        }
                        ?.let {
                            return ImageResult(
                                url = it
                            )
                        }

                    d.optString(
                        "b64_json",
                        ""
                    )
                        .takeIf {
                            it.isNotBlank()
                        }
                        ?.let {
                            return ImageResult(
                                base64 = it
                            )
                        }
                }

            throw IOException(
                "OpenRouter: لم يُرجع صورة\n" +
                        body.take(200)
            )
        }
    }

    // ============================================================
    // توليد الصور — Custom
    // ============================================================

    private fun generateImageCustom(
        prompt: String
    ): ImageResult {

        val url =
            settings.customImageUrl.trim()

        val apiKey =
            settings.customImageKey.trim()

        val model =
            settings.imageModel.trim()

        if (url.isBlank()) {
            throw IOException(
                "Custom Image: الرابط فارغ"
            )
        }

        val requestJson =
            JSONObject().apply {

                put("prompt", prompt)

                if (model.isNotBlank()) {
                    put("model", model)
                }

                put("n", 1)
            }

        val requestBuilder =
            Request.Builder()
                .url(url)
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

        if (apiKey.isNotBlank()) {
            requestBuilder.addHeader(
                "Authorization",
                "Bearer $apiKey"
            )
        }

        client.newCall(
            requestBuilder.build()
        ).execute().use { response ->

            val body =
                response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                throw IOException(
                    "Custom Image ${response.code}: " +
                            body.take(300)
                )
            }

            val root = JSONObject(body)

            root.optJSONArray("data")
                ?.optJSONObject(0)
                ?.let { d ->

                    d.optString(
                        "url",
                        ""
                    )
                        .takeIf {
                            it.isNotBlank()
                        }
                        ?.let {
                            return ImageResult(
                                url = it
                            )
                        }

                    d.optString(
                        "b64_json",
                        ""
                    )
                        .takeIf {
                            it.isNotBlank()
                        }
                        ?.let {
                            return ImageResult(
                                base64 = it
                            )
                        }
                }

            throw IOException(
                "Custom Image: لم يُرجع صورة\n" +
                        body.take(200)
            )
        }
    }
}
