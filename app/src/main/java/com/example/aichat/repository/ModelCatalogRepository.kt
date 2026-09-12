package com.example.aichat.repository

import com.example.aichat.data.local.AiSettings
import com.example.aichat.data.model.ModelInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class ModelCatalogRepository(
    private val settings: AiSettings
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    suspend fun getModels(
        provider: String,
        apiKey: String
    ): List<ModelInfo> = withContext(Dispatchers.IO) {
        when (provider.lowercase().trim()) {
            "gemini" -> getGeminiModels(apiKey)
            "openrouter" -> getOpenRouterModels(apiKey)
            "openai" -> getOpenAIModels(apiKey)
            "mistral" -> getMistralModels(apiKey)
            "groq" -> getGroqModels(apiKey)
            "huggingface", "hugging face", "hf" ->
                getHuggingFaceModels(apiKey)
            "nvidia" -> getNvidiaModels()
            else -> emptyList()
        }
    }

    private fun getGeminiModels(apiKey: String): List<ModelInfo> {
        if (apiKey.isBlank()) {
            throw IOException("Gemini: أدخل API Key أولاً")
        }

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
                throw IOException("Gemini ${response.code}: ${body.take(200)}")
            }

            val models = JSONObject(body).optJSONArray("models")
                ?: return emptyList()

            val result = mutableListOf<ModelInfo>()

            for (i in 0 until models.length()) {
                val obj = models.optJSONObject(i) ?: continue
                val id = obj.optString("name").removePrefix("models/")

                if (id.isBlank()) continue

                val methods = obj.optJSONArray("supportedGenerationMethods")
                val supportsChat = (0 until (methods?.length() ?: 0)).any {
                    methods?.optString(it)
                        .equals("generateContent", true)
                }

                if (!supportsChat) continue

                val lower = id.lowercase()

                result += ModelInfo(
                    id = id,
                    name = obj.optString("displayName").ifBlank { id },
                    provider = "gemini",
                    isFree = lower.contains("flash") || lower.contains("lite"),
                    supportsVision = !lower.contains("audio"),
                    recommended = lower.contains("2.5") || lower.contains("3."),
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

    private fun getHuggingFaceModels(apiKey: String): List<ModelInfo> {
        val builder = Request.Builder()
            .url("https://router.huggingface.co/v1/models")
            .get()
            .addHeader("User-Agent", "AiChat/1.0 Android")

        if (apiKey.isNotBlank()) {
            builder.addHeader("Authorization", "Bearer $apiKey")
        }

        client.newCall(builder.build()).execute().use { response ->
            val body = response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                throw IOException(
                    "Hugging Face ${response.code}: ${body.take(500)}"
                )
            }

            val data = JSONObject(body).optJSONArray("data")
                ?: return emptyList()

            val result = mutableListOf<ModelInfo>()

            for (i in 0 until data.length()) {
                val obj = data.optJSONObject(i) ?: continue
                val id = obj.optString("id").trim()

                if (id.isBlank()) continue

                val architecture = obj.optJSONObject("architecture")
                val supportsVision = jsonArrayContains(
                    architecture?.optJSONArray("input_modalities"),
                    "image"
                )

                var isFree = false
                var hasLiveProvider = false
                var bestContextLength = obj.optLong("context_length", 0L)

                obj.optJSONArray("providers")?.let { providers ->
                    for (j in 0 until providers.length()) {
                        val provider = providers.optJSONObject(j) ?: continue
                        val status = provider.optString("status")
                            .trim()
                            .lowercase()

                        if (status == "live" || status.isBlank()) {
                            hasLiveProvider = true
                        }

                        if (provider.optBoolean("is_free", false)) {
                            isFree = true
                        }

                        val providerContext =
                            provider.optLong("context_length", 0L)

                        if (providerContext > bestContextLength) {
                            bestContextLength = providerContext
                        }
                    }

                    if (providers.length() > 0 && !hasLiveProvider) {
                        continue
                    }
                }

                val lower = id.lowercase()
                val recommended =
                    lower.contains("qwen") ||
                        lower.contains("deepseek") ||
                        lower.contains("llama") ||
                        lower.contains("gemma") ||
                        lower.contains("mistral") ||
                        lower.contains("phi") ||
                        lower.contains("kimi") ||
                        lower.contains("glm") ||
                        lower.contains("nemotron") ||
                        lower.contains("minimax") ||
                        lower.contains("gpt-oss") ||
                        lower.contains("command") ||
                        lower.contains("granite")

                result += ModelInfo(
                    id = id,
                    name = createHuggingFaceDisplayName(id),
                    provider = "huggingface",
                    isFree = isFree,
                    supportsVision = supportsVision,
                    recommended = recommended,
                    contextLength = bestContextLength
                )
            }

            return result
                .distinctBy { it.id }
                .sortedWith(
                    compareByDescending<ModelInfo> { it.recommended }
                        .thenByDescending { it.isFree }
                        .thenByDescending { it.supportsVision }
                        .thenBy { it.name }
                )
        }
    }

    private fun createHuggingFaceDisplayName(modelId: String): String {
        if (modelId.isBlank()) return modelId
        return modelId.split("/").last()
    }

    private fun getOpenRouterModels(apiKey: String): List<ModelInfo> {
        val builder = Request.Builder()
            .url("https://openrouter.ai/api/v1/models?output_modalities=text")
            .get()

        if (apiKey.isNotBlank()) {
            builder.addHeader("Authorization", "Bearer $apiKey")
        }

        client.newCall(builder.build()).execute().use { response ->
            val body = response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                throw IOException(
                    "OpenRouter ${response.code}: ${body.take(200)}"
                )
            }

            val data = JSONObject(body).optJSONArray("data")
                ?: return emptyList()

            val result = mutableListOf<ModelInfo>()

            for (i in 0 until data.length()) {
                val obj = data.optJSONObject(i) ?: continue
                val id = obj.optString("id").trim()

                if (id.isBlank()) continue

                val name = obj.optString("name").ifBlank { id }
                val architecture = obj.optJSONObject("architecture")
                val supportsVision = jsonArrayContains(
                    architecture?.optJSONArray("input_modalities"),
                    "image"
                )

                val pricing = obj.optJSONObject("pricing")
                val promptPrice = pricing?.optString("prompt", "1") ?: "1"
                val completionPrice =
                    pricing?.optString("completion", "1") ?: "1"
                val requestPrice = pricing?.optString("request", "0") ?: "0"

                val isFree =
                    isZeroPrice(promptPrice) &&
                        isZeroPrice(completionPrice) &&
                        isZeroPrice(requestPrice)

                val lower = "$id $name".lowercase()
                val recommended =
                    lower.contains("gemini-2.5") ||
                        lower.contains("claude-3.5") ||
                        lower.contains("claude-opus") ||
                        lower.contains("gpt-4o") ||
                        lower.contains("qwen3") ||
                        lower.contains("nemotron") ||
                        lower.contains("deepseek-r1")

                result += ModelInfo(
                    id = id,
                    name = name,
                    provider = "openrouter",
                    isFree = isFree,
                    supportsVision = supportsVision,
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

    private fun getOpenAIModels(apiKey: String): List<ModelInfo> {
        if (apiKey.isBlank()) {
            throw IOException("OpenAI: أدخل API Key أولاً")
        }

        val request = Request.Builder()
            .url("https://api.openai.com/v1/models")
            .get()
            .addHeader("Authorization", "Bearer $apiKey")
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                throw IOException(
                    "OpenAI ${response.code}: ${body.take(200)}"
                )
            }

            val data = JSONObject(body).optJSONArray("data")
                ?: return emptyList()

            val result = mutableListOf<ModelInfo>()

            for (i in 0 until data.length()) {
                val obj = data.optJSONObject(i) ?: continue
                val id = obj.optString("id")

                if (id.isBlank()) continue

                val lower = id.lowercase()
                val isChat =
                    lower.startsWith("gpt-") ||
                        lower.startsWith("o1") ||
                        lower.startsWith("o3") ||
                        lower.startsWith("o4")

                if (!isChat) continue

                val vision =
                    lower.contains("gpt-4o") ||
                        lower.contains("gpt-4.1") ||
                        lower.contains("gpt-5")

                result += ModelInfo(
                    id = id,
                    name = id,
                    provider = "openai",
                    supportsVision = vision,
                    recommended = vision
                )
            }

            return result
                .distinctBy { it.id }
                .sortedWith(
                    compareByDescending<ModelInfo> { it.recommended }
                        .thenBy { it.name }
                )
        }
    }

    private fun getMistralModels(apiKey: String): List<ModelInfo> {
        if (apiKey.isBlank()) {
            throw IOException("Mistral: أدخل API Key أولاً")
        }

        val request = Request.Builder()
            .url("https://api.mistral.ai/v1/models")
            .get()
            .addHeader("Authorization", "Bearer $apiKey")
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                throw IOException(
                    "Mistral ${response.code}: ${body.take(200)}"
                )
            }

            val data = JSONObject(body).optJSONArray("data")
                ?: return emptyList()

            val result = mutableListOf<ModelInfo>()

            for (i in 0 until data.length()) {
                val obj = data.optJSONObject(i) ?: continue
                val id = obj.optString("id")

                if (id.isBlank()) continue

                val capabilities = obj.optJSONObject("capabilities")
                val isChat = capabilities?.optBoolean(
                    "completion_chat",
                    false
                ) ?: false

                if (!isChat) continue

                val vision = capabilities?.optBoolean("vision", false) ?: false
                val lower = id.lowercase()

                result += ModelInfo(
                    id = id,
                    name = id,
                    provider = "mistral",
                    isFree =
                        lower.contains("ministral") ||
                            lower.contains("small"),
                    supportsVision = vision,
                    recommended =
                        lower.contains("large") ||
                            lower.contains("medium") ||
                            lower.contains("pixtral"),
                    contextLength = obj.optLong("max_context_length", 0L)
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

    private fun getGroqModels(apiKey: String): List<ModelInfo> {
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
                    "Groq ${response.code}: ${body.take(200)}"
                )
            }

            val data = JSONObject(body).optJSONArray("data")
                ?: return emptyList()

            val result = mutableListOf<ModelInfo>()

            for (i in 0 until data.length()) {
                val obj = data.optJSONObject(i) ?: continue
                val id = obj.optString("id")

                if (id.isBlank()) continue

                val lower = id.lowercase()

                if (
                    lower.contains("whisper") ||
                    lower.contains("guard") ||
                    lower.contains("tts")
                ) {
                    continue
                }

                result += ModelInfo(
                    id = id,
                    name = id,
                    provider = "groq",
                    isFree = true,
                    supportsVision =
                        lower.contains("vision") ||
                            lower.contains("scout") ||
                            lower.contains("maverick"),
                    recommended =
                        lower.contains("llama-4") ||
                            lower.contains("deepseek"),
                    contextLength = obj.optLong("context_window", 0L)
                )
            }

            return result
                .distinctBy { it.id }
                .sortedWith(
                    compareByDescending<ModelInfo> { it.recommended }
                        .thenBy { it.name }
                )
        }
    }

    private fun getNvidiaModels(): List<ModelInfo> =
        listOf(
            ModelInfo(
                id = "qwen/qwen3.5-122b-a10b",
                name = "Qwen 3.5 122B",
                provider = "nvidia",
                supportsVision = true,
                recommended = true,
                contextLength = 131072L
            ),
            ModelInfo(
                id = "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning",
                name = "Nemotron 3 Nano Omni 30B",
                provider = "nvidia",
                supportsVision = true,
                recommended = true,
                contextLength = 131072L
            ),
            ModelInfo(
                id = "nvidia/nemotron-nano-12b-v2-vl",
                name = "Nemotron Nano 12B V2 VL",
                provider = "nvidia",
                supportsVision = true,
                recommended = true,
                contextLength = 32768L
            ),
            ModelInfo(
                id = "google/gemma-4-31b-it",
                name = "Gemma 4 31B IT",
                provider = "nvidia",
                supportsVision = true,
                recommended = true,
                contextLength = 131072L
            ),
            ModelInfo(
                id = "google/gemma-3-27b-it",
                name = "Gemma 3 27B IT",
                provider = "nvidia",
                supportsVision = true,
                recommended = true,
                contextLength = 131072L
            ),
            ModelInfo(
                id = "microsoft/phi-4-multimodal-instruct",
                name = "Phi 4 Multimodal",
                provider = "nvidia",
                supportsVision = true,
                recommended = true,
                contextLength = 16384L
            ),
            ModelInfo(
                id = "mistralai/mistral-large-3-675b-instruct-2512",
                name = "Mistral Large 3 675B",
                provider = "nvidia",
                recommended = true,
                contextLength = 131072L
            ),
            ModelInfo(
                id = "mistralai/ministral-14b-instruct-2512",
                name = "Ministral 14B",
                provider = "nvidia",
                contextLength = 131072L
            ),
            ModelInfo(
                id = "moonshotai/kimi-k2.6",
                name = "Kimi K2.6",
                provider = "nvidia",
                supportsVision = true,
                recommended = true,
                contextLength = 131072L
            )
        ).sortedWith(
            compareByDescending<ModelInfo> { it.recommended }
                .thenByDescending { it.supportsVision }
                .thenBy { it.name }
        )

    private fun jsonArrayContains(
        array: JSONArray?,
        value: String
    ): Boolean {
        if (array == null) return false

        for (i in 0 until array.length()) {
            if (array.optString(i).equals(value, true)) {
                return true
            }
        }

        return false
    }

    private fun isZeroPrice(value: String): Boolean =
        value.trim().toDoubleOrNull()?.let { it == 0.0 } ?: false
}
