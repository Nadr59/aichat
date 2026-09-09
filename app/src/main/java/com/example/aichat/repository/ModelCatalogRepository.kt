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

class ModelCatalogRepository(private val settings: AiSettings) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // ============================================================
    // المدخل الرئيسي
    // ============================================================

    suspend fun getModels(
    provider: String,
    apiKey: String,
    forImages: Boolean = false
): List<ModelInfo> = withContext(Dispatchers.IO) {

    when (provider.lowercase().trim()) {
        "gemini"      -> getGeminiModels(apiKey)
        "openrouter"  -> getOpenRouterModels(apiKey, forImages)
        "openai"      -> getOpenAIModels(apiKey)
        "mistral"     -> getMistralModels(apiKey)
        "groq"        -> getGroqModels(apiKey)
        "horde"       -> getHordeModels(forImages)
        // ✅ HF لا يحتاج مفتاح للقائمة
        "huggingface" -> getHuggingFaceModels(apiKey)
        else          -> emptyList()
    }
    }

    // ============================================================
    // Gemini
    // ============================================================

    private fun getGeminiModels(apiKey: String): List<ModelInfo> {

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

            if (!response.isSuccessful)
                throw IOException("Gemini ${response.code}: ${body.take(200)}")

            val models = JSONObject(body).optJSONArray("models")
                ?: return emptyList()

            val result = mutableListOf<ModelInfo>()

            for (i in 0 until models.length()) {
                val obj = models.optJSONObject(i) ?: continue

                val id = obj.optString("name").removePrefix("models/")
                val displayName = obj.optString("displayName").ifBlank { id }

                // فقط النماذج التي تدعم generateContent
                val methods = obj.optJSONArray("supportedGenerationMethods")
                val supportsChat = (0 until (methods?.length() ?: 0))
                    .any { methods?.optString(it).equals("generateContent", true) }

                if (!supportsChat) continue

                val lower = id.lowercase()

                result += ModelInfo(
                    id                   = id,
                    name                 = displayName,
                    provider             = "gemini",
                    isFree               = lower.contains("flash") || lower.contains("lite"),
                    supportsVision       = !lower.contains("audio"),
                    supportsImageGeneration = lower.contains("image"),
                    recommended          = lower.contains("2.5") || lower.contains("3."),
                    contextLength        = obj.optLong("inputTokenLimit", 0L)
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
// ✅ HF لا يحتاج مفتاح لعرض القائمة الثابتة
private fun getHuggingFaceModels(apiKey: String): List<ModelInfo> {
    return listOf(
        ModelInfo(
            id            = "mistralai/Mistral-7B-Instruct-v0.3",
            name          = "Mistral 7B Instruct",
            provider      = "huggingface",
            isFree        = true,
            recommended   = true,
            contextLength = 32768
        ),
        ModelInfo(
            id            = "mistralai/Mistral-Nemo-Instruct-2407",
            name          = "Mistral Nemo 12B",
            provider      = "huggingface",
            isFree        = true,
            recommended   = true,
            contextLength = 128000
        ),
        ModelInfo(
            id            = "microsoft/Phi-3.5-mini-instruct",
            name          = "Phi 3.5 Mini",
            provider      = "huggingface",
            isFree        = true,
            recommended   = true,
            contextLength = 128000
        ),
        ModelInfo(
            id            = "Qwen/Qwen2.5-7B-Instruct",
            name          = "Qwen 2.5 7B",
            provider      = "huggingface",
            isFree        = true,
            recommended   = true,
            contextLength = 32768
        ),
        ModelInfo(
            id            = "Qwen/Qwen2.5-72B-Instruct",
            name          = "Qwen 2.5 72B",
            provider      = "huggingface",
            isFree        = true,
            recommended   = true,
            contextLength = 32768
        ),
        ModelInfo(
            id            = "meta-llama/Llama-3.1-8B-Instruct",
            name          = "Llama 3.1 8B",
            provider      = "huggingface",
            isFree        = true,
            recommended   = true,
            contextLength = 128000
        ),
        ModelInfo(
            id            = "meta-llama/Llama-3.2-3B-Instruct",
            name          = "Llama 3.2 3B",
            provider      = "huggingface",
            isFree        = true,
            recommended   = false,
            contextLength = 128000
        ),
        ModelInfo(
            id            = "google/gemma-2-9b-it",
            name          = "Gemma 2 9B",
            provider      = "huggingface",
            isFree        = true,
            recommended   = true,
            contextLength = 8192
        ),
        ModelInfo(
            id            = "HuggingFaceH4/zephyr-7b-beta",
            name          = "Zephyr 7B Beta",
            provider      = "huggingface",
            isFree        = true,
            recommended   = false,
            contextLength = 32768
        ),
        ModelInfo(
            id            = "deepseek-ai/DeepSeek-R1-Distill-Qwen-7B",
            name          = "DeepSeek R1 Distill 7B",
            provider      = "huggingface",
            isFree        = true,
            recommended   = true,
            contextLength = 32768
        )
    )
}

            

    // ============================================================
    // OpenRouter
    // ============================================================

    private fun getOpenRouterModels(
        apiKey: String,
        forImages: Boolean
    ): List<ModelInfo> {

        val url = if (forImages)
            "https://openrouter.ai/api/v1/models?output_modalities=image"
        else
            "https://openrouter.ai/api/v1/models?output_modalities=text"

        val reqBuilder = Request.Builder().url(url).get()
        if (apiKey.isNotBlank())
            reqBuilder.addHeader("Authorization", "Bearer $apiKey")

        client.newCall(reqBuilder.build()).execute().use { response ->
            val body = response.body?.string().orEmpty()

            if (!response.isSuccessful)
                throw IOException("OpenRouter ${response.code}: ${body.take(200)}")

            val data = JSONObject(body).optJSONArray("data")
                ?: return emptyList()

            val result = mutableListOf<ModelInfo>()

            for (i in 0 until data.length()) {
                val obj  = data.optJSONObject(i) ?: continue
                val id   = obj.optString("id").trim()
                if (id.isBlank()) continue

                val name         = obj.optString("name").ifBlank { id }
                val architecture = obj.optJSONObject("architecture")
                val inputMod     = architecture?.optJSONArray("input_modalities")
                val outputMod    = architecture?.optJSONArray("output_modalities")

                val supportsVision    = jsonArrayContains(inputMod, "image")
                val supportsImageGen  = jsonArrayContains(outputMod, "image")

                val pricing        = obj.optJSONObject("pricing")
                val promptPrice    = pricing?.optString("prompt", "1") ?: "1"
                val completionPrice = pricing?.optString("completion", "1") ?: "1"
                val requestPrice   = pricing?.optString("request", "0") ?: "0"
                val imagePrice     = pricing?.optString("image", "0") ?: "0"

                val isFree = isZeroPrice(promptPrice) &&
                             isZeroPrice(completionPrice) &&
                             (isZeroPrice(requestPrice) || requestPrice == "0") &&
                             (!supportsImageGen || isZeroPrice(imagePrice))

                val lower = "$id $name".lowercase()
                val recommended = lower.contains("gemini-2.5") ||
                                  lower.contains("claude-3.5") ||
                                  lower.contains("claude-opus") ||
                                  lower.contains("gpt-4o") ||
                                  lower.contains("qwen3") ||
                                  lower.contains("nemotron") ||
                                  lower.contains("deepseek-r1")

                result += ModelInfo(
                    id                      = id,
                    name                    = name,
                    provider                = "openrouter",
                    isFree                  = isFree,
                    supportsVision          = supportsVision,
                    supportsImageGeneration = supportsImageGen,
                    recommended             = recommended,
                    contextLength           = obj.optLong("context_length", 0L)
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
    // OpenAI
    // ============================================================

    private fun getOpenAIModels(apiKey: String): List<ModelInfo> {

        if (apiKey.isBlank()) throw IOException("OpenAI: أدخل API Key أولاً")

        val request = Request.Builder()
            .url("https://api.openai.com/v1/models")
            .get()
            .addHeader("Authorization", "Bearer $apiKey")
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()

            if (!response.isSuccessful)
                throw IOException("OpenAI ${response.code}: ${body.take(200)}")

            val data = JSONObject(body).optJSONArray("data")
                ?: return emptyList()

            val result = mutableListOf<ModelInfo>()

            for (i in 0 until data.length()) {
                val obj = data.optJSONObject(i) ?: continue
                val id  = obj.optString("id")
                if (id.isBlank()) continue

                val lower = id.lowercase()

                // فقط نماذج المحادثة
                val isChat = lower.startsWith("gpt-") ||
                             lower.startsWith("o1") ||
                             lower.startsWith("o3") ||
                             lower.startsWith("o4")

                if (!isChat) continue

                result += ModelInfo(
                    id             = id,
                    name           = id,
                    provider       = "openai",
                    isFree         = false,
                    supportsVision = lower.contains("gpt-4o") ||
                                     lower.contains("gpt-4.1") ||
                                     lower.contains("gpt-5"),
                    recommended    = lower.contains("gpt-4o") ||
                                     lower.contains("gpt-4.1") ||
                                     lower.contains("gpt-5")
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

    // ============================================================
    // Mistral
    // ============================================================

    private fun getMistralModels(apiKey: String): List<ModelInfo> {

        if (apiKey.isBlank()) throw IOException("Mistral: أدخل API Key أولاً")

        val request = Request.Builder()
            .url("https://api.mistral.ai/v1/models")
            .get()
            .addHeader("Authorization", "Bearer $apiKey")
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()

            if (!response.isSuccessful)
                throw IOException("Mistral ${response.code}: ${body.take(200)}")

            val data = JSONObject(body).optJSONArray("data")
                ?: return emptyList()

            val result = mutableListOf<ModelInfo>()

            for (i in 0 until data.length()) {
                val obj = data.optJSONObject(i) ?: continue
                val id  = obj.optString("id")
                if (id.isBlank()) continue

                val capabilities = obj.optJSONObject("capabilities")
                val isChat       = capabilities?.optBoolean("completion_chat", false) ?: false
                if (!isChat) continue

                val vision = capabilities?.optBoolean("vision", false) ?: false
                val lower  = id.lowercase()

                result += ModelInfo(
                    id             = id,
                    name           = id,
                    provider       = "mistral",
                    isFree         = lower.contains("ministral") || lower.contains("small"),
                    supportsVision = vision,
                    recommended    = lower.contains("large") ||
                                     lower.contains("medium") ||
                                     lower.contains("pixtral"),
                    contextLength  = obj.optLong("max_context_length", 0L)
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
    // Groq
    // ============================================================

    private fun getGroqModels(apiKey: String): List<ModelInfo> {

        if (apiKey.isBlank()) throw IOException("Groq: أدخل API Key أولاً")

        val request = Request.Builder()
            .url("https://api.groq.com/openai/v1/models")
            .get()
            .addHeader("Authorization", "Bearer $apiKey")
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()

            if (!response.isSuccessful)
                throw IOException("Groq ${response.code}: ${body.take(200)}")

            val data = JSONObject(body).optJSONArray("data")
                ?: return emptyList()

            val result = mutableListOf<ModelInfo>()

            for (i in 0 until data.length()) {
                val obj = data.optJSONObject(i) ?: continue
                val id  = obj.optString("id")
                if (id.isBlank()) continue

                val lower = id.lowercase()

                // استبعاد نماذج الصوت والمراقبة
                if (lower.contains("whisper") ||
                    lower.contains("guard") ||
                    lower.contains("tts")) continue

                result += ModelInfo(
                    id             = id,
                    name           = id,
                    provider       = "groq",
                    isFree         = true,
                    supportsVision = lower.contains("vision") ||
                                     lower.contains("scout") ||
                                     lower.contains("maverick"),
                    recommended    = lower.contains("llama-4") ||
                                     lower.contains("deepseek"),
                    contextLength  = obj.optLong("context_window", 0L)
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

    // ============================================================
    // AI Horde
    // ============================================================

    private fun getHordeModels(forImages: Boolean): List<ModelInfo> {

        val request = Request.Builder()
            .url("https://aihorde.net/api/v2/status/models")
            .get()
            .addHeader("Client-Agent", "AiChat:1.0:anonymous")
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()

            if (!response.isSuccessful)
                throw IOException("Horde ${response.code}: ${body.take(200)}")

            val array  = JSONArray(body)
            val result = mutableListOf<ModelInfo>()

            for (i in 0 until array.length()) {
                val obj  = array.optJSONObject(i) ?: continue
                val name = obj.optString("name").trim()
                if (name.isBlank()) continue

                val lower    = name.lowercase()
                val isImage  = lower.contains("stable diffusion") ||
                               lower.contains("sdxl") ||
                               lower.contains("flux") ||
                               lower.contains("dreamshaper") ||
                               lower.contains("realistic") ||
                               lower.contains("deliberate")

                if (forImages != isImage) continue

                result += ModelInfo(
                    id                      = name,
                    name                    = name,
                    provider                = "horde",
                    isFree                  = true,
                    supportsVision          = false,
                    supportsImageGeneration = isImage,
                    recommended             = lower.contains("flux") ||
                                             lower.contains("sdxl") ||
                                             lower.contains("mistral") ||
                                             lower.contains("llama-3")
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

    // ============================================================
    // Helpers
    // ============================================================

    private fun jsonArrayContains(array: JSONArray?, value: String): Boolean {
        if (array == null) return false
        for (i in 0 until array.length()) {
            if (array.optString(i).equals(value, ignoreCase = true)) return true
        }
        return false
    }

    private fun isZeroPrice(value: String): Boolean =
        value.trim().toDoubleOrNull()?.let { it == 0.0 } ?: false
}
