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
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
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
        "huggingface" -> getHuggingFaceModels()
        "nvidia"      -> getNvidiaModels(apiKey)  // ✅ يمرر المفتاح
        "horde"       -> getHordeModels(forImages)
        else          -> emptyList()
    }
    }
     
            

    // ============================================================
    // Gemini
    // ============================================================

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
                throw IOException(
                    "Gemini ${response.code}: ${body.take(200)}"
                )
            }

            val models = JSONObject(body)
                .optJSONArray("models")
                ?: return emptyList()

            val result = mutableListOf<ModelInfo>()

            for (i in 0 until models.length()) {

                val obj = models.optJSONObject(i)
                    ?: continue

                val id = obj
                    .optString("name")
                    .removePrefix("models/")

                val displayName = obj
                    .optString("displayName")
                    .ifBlank { id }

                val methods = obj.optJSONArray(
                    "supportedGenerationMethods"
                )

                val supportsChat =
                    (0 until (methods?.length() ?: 0))
                        .any {
                            methods
                                ?.optString(it)
                                .equals(
                                    "generateContent",
                                    true
                                )
                        }

                if (!supportsChat) continue

                val lower = id.lowercase()

                result += ModelInfo(
                    id = id,
                    name = displayName,
                    provider = "gemini",

                    isFree =
                        lower.contains("flash") ||
                        lower.contains("lite"),

                    supportsVision =
                        !lower.contains("audio"),

                    supportsImageGeneration =
                        lower.contains("image"),

                    recommended =
                        lower.contains("2.5") ||
                        lower.contains("3."),

                    contextLength =
                        obj.optLong(
                            "inputTokenLimit",
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
                        .thenByDescending {
                            it.isFree
                        }
                        .thenBy {
                            it.name
                        }
                )
        }
    }


// // ============================================================
// Hugging Face - Dynamic Models
// ============================================================

private fun getHuggingFaceModels(
    apiKey: String,
    forImages: Boolean
): List<ModelInfo> {

    val result = mutableListOf<HuggingFaceModel>()

    if (forImages) {

        val imageUrl =
            "https://huggingface.co/api/models" +
                "?inference_provider=all" +
                "&pipeline_tag=text-to-image" +
                "&sort=downloads" +
                "&direction=-1" +
                "&limit=100"

        fetchHuggingFaceModels(
            url = imageUrl,
            apiKey = apiKey,
            target = result
        )

    } else {

        // النماذج النصية
        val textUrl =
            "https://huggingface.co/api/models" +
                "?inference_provider=all" +
                "&pipeline_tag=text-generation" +
                "&sort=downloads" +
                "&direction=-1" +
                "&limit=100"

        fetchHuggingFaceModels(
            url = textUrl,
            apiKey = apiKey,
            target = result
        )

        // نماذج Vision
        val visionUrl =
            "https://huggingface.co/api/models" +
                "?inference_provider=all" +
                "&pipeline_tag=image-text-to-text" +
                "&sort=downloads" +
                "&direction=-1" +
                "&limit=100"

        fetchHuggingFaceModels(
            url = visionUrl,
            apiKey = apiKey,
            target = result
        )
    }

    return result
        .distinctBy { it.id }
        .map { model ->

            val lower =
                (
                    "${model.id} " +
                    "${model.pipelineTag} " +
                    model.tags.joinToString(" ")
                ).lowercase()

            val isVision =
                model.pipelineTag.equals(
                    "image-text-to-text",
                    ignoreCase = true
                ) ||
                lower.contains("vision") ||
                lower.contains("visual") ||
                lower.contains("multimodal") ||
                lower.contains("vl")

            val isImageGeneration =
                model.pipelineTag.equals(
                    "text-to-image",
                    ignoreCase = true
                )

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
                lower.contains("pixtral") ||
                lower.contains("ministral") ||
                lower.contains("flux")

            ModelInfo(
                id = model.id,

                name = createHuggingFaceDisplayName(
                    model.id
                ),

                provider = "huggingface",

                /*
                 * لا نعتبر النموذج مجانيًا تلقائيًا.
                 * Hugging Face قد يتطلب رصيدًا أو مزود Inference.
                 */
                isFree = false,

                supportsVision = isVision,

                supportsImageGeneration =
                    isImageGeneration,

                recommended = recommended,

                contextLength =
                    model.contextLength
            )
        }
        .sortedWith(
            compareByDescending<ModelInfo> {
                it.recommended
            }
                .thenByDescending {
                    it.supportsVision
                }
                .thenBy {
                    it.name
                }
        )
}


// ============================================================
// Fetch Hugging Face Models
// ============================================================

private fun fetchHuggingFaceModels(
    url: String,
    apiKey: String,
    target: MutableList<HuggingFaceModel>
) {

    val requestBuilder =
        Request.Builder()
            .url(url)
            .get()
            .addHeader(
                "User-Agent",
                "AiChat/1.0 Android"
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
                "Hugging Face ${response.code}: " +
                    body.take(500)
            )
        }

        val array =
            try {
                JSONArray(body)
            } catch (e: Exception) {

                throw IOException(
                    "Hugging Face: استجابة غير صالحة"
                )
            }

        for (i in 0 until array.length()) {

            val obj =
                array.optJSONObject(i)
                    ?: continue

            val id =
                obj.optString("id")
                    .trim()

            if (id.isBlank()) {
                continue
            }

            if (
                obj.optBoolean(
                    "disabled",
                    false
                )
            ) {
                continue
            }

            val pipelineTag =
                obj.optString(
                    "pipeline_tag"
                ).trim()

            val downloads =
                obj.optLong(
                    "downloads",
                    0L
                )

            val likes =
                obj.optLong(
                    "likes",
                    0L
                )

            val tags =
                mutableListOf<String>()

            val tagsArray =
                obj.optJSONArray("tags")

            if (tagsArray != null) {

                for (j in 0 until tagsArray.length()) {

                    val tag =
                        tagsArray
                            .optString(j)
                            .trim()

                    if (tag.isNotBlank()) {
                        tags += tag
                    }
                }
            }

            val contextLength =
                extractContextLength(obj)

            target += HuggingFaceModel(
                id = id,
                pipelineTag = pipelineTag,
                downloads = downloads,
                likes = likes,
                tags = tags,
                contextLength = contextLength
            )
        }
    }
}


// ============================================================
// Extract Context Length
// ============================================================

private fun extractContextLength(
    obj: JSONObject
): Long {

    val config =
        obj.optJSONObject("config")
            ?: return 0L

    val possibleKeys =
        listOf(
            "max_position_embeddings",
            "max_sequence_length",
            "max_seq_len",
            "seq_length",
            "model_max_length",
            "max_context_length"
        )

    for (key in possibleKeys) {

        val value =
            config.optLong(
                key,
                0L
            )

        if (value > 0L) {
            return value
        }
    }

    return 0L
}


// ============================================================
// Display Name
// ============================================================

private fun createHuggingFaceDisplayName(
    modelId: String
): String {

    if (modelId.isBlank()) {
        return modelId
    }

    val parts =
        modelId.split("/")

    return if (parts.size >= 2) {
        parts.last()
    } else {
        modelId
    }
}


// ============================================================
// Internal Hugging Face Model
// ============================================================

private data class HuggingFaceModel(
    val id: String,
    val pipelineTag: String,
    val downloads: Long,
    val likes: Long,
    val tags: List<String>,
    val contextLength: Long
)


            


                    
            
                  
     
     
    // ============================================================
    // OpenRouter
    // ============================================================

    private fun getOpenRouterModels(
        apiKey: String,
        forImages: Boolean
    ): List<ModelInfo> {

        val url =
            if (forImages) {
                "https://openrouter.ai/api/v1/models?output_modalities=image"
            } else {
                "https://openrouter.ai/api/v1/models?output_modalities=text"
            }

        val reqBuilder =
            Request.Builder()
                .url(url)
                .get()

        if (apiKey.isNotBlank()) {
            reqBuilder.addHeader(
                "Authorization",
                "Bearer $apiKey"
            )
        }

        client.newCall(
            reqBuilder.build()
        ).execute().use { response ->

            val body =
                response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                throw IOException(
                    "OpenRouter ${response.code}: " +
                        body.take(200)
                )
            }

            val data =
                JSONObject(body)
                    .optJSONArray("data")
                    ?: return emptyList()

            val result =
                mutableListOf<ModelInfo>()

            for (i in 0 until data.length()) {

                val obj =
                    data.optJSONObject(i)
                        ?: continue

                val id =
                    obj.optString("id").trim()

                if (id.isBlank()) continue

                val name =
                    obj.optString("name")
                        .ifBlank { id }

                val architecture =
                    obj.optJSONObject(
                        "architecture"
                    )

                val inputMod =
                    architecture?.optJSONArray(
                        "input_modalities"
                    )

                val outputMod =
                    architecture?.optJSONArray(
                        "output_modalities"
                    )

                val supportsVision =
                    jsonArrayContains(
                        inputMod,
                        "image"
                    )

                val supportsImageGen =
                    jsonArrayContains(
                        outputMod,
                        "image"
                    )

                val pricing =
                    obj.optJSONObject("pricing")

                val promptPrice =
                    pricing?.optString(
                        "prompt",
                        "1"
                    ) ?: "1"

                val completionPrice =
                    pricing?.optString(
                        "completion",
                        "1"
                    ) ?: "1"

                val requestPrice =
                    pricing?.optString(
                        "request",
                        "0"
                    ) ?: "0"

                val imagePrice =
                    pricing?.optString(
                        "image",
                        "0"
                    ) ?: "0"

                val isFree =
                    isZeroPrice(promptPrice) &&
                    isZeroPrice(completionPrice) &&
                    isZeroPrice(requestPrice) &&
                    (
                        !supportsImageGen ||
                            isZeroPrice(imagePrice)
                    )

                val lower =
                    "$id $name".lowercase()

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
                    supportsImageGeneration =
                        supportsImageGen,
                    recommended = recommended,
                    contextLength =
                        obj.optLong(
                            "context_length",
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
                        .thenByDescending {
                            it.isFree
                        }
                        .thenBy {
                            it.name
                        }
                )
        }
    }

    // ============================================================
    // OpenAI
    // ============================================================

    private fun getOpenAIModels(
        apiKey: String
    ): List<ModelInfo> {

        if (apiKey.isBlank()) {
            throw IOException(
                "OpenAI: أدخل API Key أولاً"
            )
        }

        val request =
            Request.Builder()
                .url(
                    "https://api.openai.com/v1/models"
                )
                .get()
                .addHeader(
                    "Authorization",
                    "Bearer $apiKey"
                )
                .build()

        client.newCall(request)
            .execute()
            .use { response ->

                val body =
                    response.body?.string()
                        .orEmpty()

                if (!response.isSuccessful) {
                    throw IOException(
                        "OpenAI ${response.code}: " +
                            body.take(200)
                    )
                }

                val data =
                    JSONObject(body)
                        .optJSONArray("data")
                        ?: return emptyList()

                val result =
                    mutableListOf<ModelInfo>()

                for (i in 0 until data.length()) {

                    val obj =
                        data.optJSONObject(i)
                            ?: continue

                    val id =
                        obj.optString("id")

                    if (id.isBlank()) continue

                    val lower =
                        id.lowercase()

                    val isChat =
                        lower.startsWith("gpt-") ||
                        lower.startsWith("o1") ||
                        lower.startsWith("o3") ||
                        lower.startsWith("o4")

                    if (!isChat) continue

                    result += ModelInfo(
                        id = id,
                        name = id,
                        provider = "openai",
                        isFree = false,

                        supportsVision =
                            lower.contains("gpt-4o") ||
                            lower.contains("gpt-4.1") ||
                            lower.contains("gpt-5"),

                        recommended =
                            lower.contains("gpt-4o") ||
                            lower.contains("gpt-4.1") ||
                            lower.contains("gpt-5")
                    )
                }

                return result
                    .distinctBy { it.id }
                    .sortedWith(
                        compareByDescending<ModelInfo> {
                            it.recommended
                        }
                            .thenBy {
                                it.name
                            }
                    )
            }
    }

    // ============================================================
    // Mistral
    // ============================================================

    private fun getMistralModels(
        apiKey: String
    ): List<ModelInfo> {

        if (apiKey.isBlank()) {
            throw IOException(
                "Mistral: أدخل API Key أولاً"
            )
        }

        val request =
            Request.Builder()
                .url(
                    "https://api.mistral.ai/v1/models"
                )
                .get()
                .addHeader(
                    "Authorization",
                    "Bearer $apiKey"
                )
                .build()

        client.newCall(request)
            .execute()
            .use { response ->

                val body =
                    response.body?.string()
                        .orEmpty()

                if (!response.isSuccessful) {
                    throw IOException(
                        "Mistral ${response.code}: " +
                            body.take(200)
                    )
                }

                val data =
                    JSONObject(body)
                        .optJSONArray("data")
                        ?: return emptyList()

                val result =
                    mutableListOf<ModelInfo>()

                for (i in 0 until data.length()) {

                    val obj =
                        data.optJSONObject(i)
                            ?: continue

                    val id =
                        obj.optString("id")

                    if (id.isBlank()) continue

                    val capabilities =
                        obj.optJSONObject(
                            "capabilities"
                        )

                    val isChat =
                        capabilities?.optBoolean(
                            "completion_chat",
                            false
                        ) ?: false

                    if (!isChat) continue

                    val vision =
                        capabilities?.optBoolean(
                            "vision",
                            false
                        ) ?: false

                    val lower =
                        id.lowercase()

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

                        contextLength =
                            obj.optLong(
                                "max_context_length",
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
                            .thenByDescending {
                                it.isFree
                            }
                            .thenBy {
                                it.name
                            }
                    )
            }
    }

    // ============================================================
    // Groq
    // ============================================================

    private fun getGroqModels(
        apiKey: String
    ): List<ModelInfo> {

        if (apiKey.isBlank()) {
            throw IOException(
                "Groq: أدخل API Key أولاً"
            )
        }

        val request =
            Request.Builder()
                .url(
                    "https://api.groq.com/openai/v1/models"
                )
                .get()
                .addHeader(
                    "Authorization",
                    "Bearer $apiKey"
                )
                .build()

        client.newCall(request)
            .execute()
            .use { response ->

                val body =
                    response.body?.string()
                        .orEmpty()

                if (!response.isSuccessful) {
                    throw IOException(
                        "Groq ${response.code}: " +
                            body.take(200)
                    )
                }

                val data =
                    JSONObject(body)
                        .optJSONArray("data")
                        ?: return emptyList()

                val result =
                    mutableListOf<ModelInfo>()

                for (i in 0 until data.length()) {

                    val obj =
                        data.optJSONObject(i)
                            ?: continue

                    val id =
                        obj.optString("id")

                    if (id.isBlank()) continue

                    val lower =
                        id.lowercase()

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
                            .thenBy {
                                it.name
                            }
                    )
            }
    }

    // ============================================================
    // AI Horde
    // ============================================================

    private fun getHordeModels(
        forImages: Boolean
    ): List<ModelInfo> {

        val request =
            Request.Builder()
                .url(
                    "https://aihorde.net/api/v2/status/models"
                )
                .get()
                .addHeader(
                    "Client-Agent",
                    "AiChat:1.0:anonymous"
                )
                .build()

        client.newCall(request)
            .execute()
            .use { response ->

                val body =
                    response.body?.string()
                        .orEmpty()

                if (!response.isSuccessful) {
                    throw IOException(
                        "Horde ${response.code}: " +
                            body.take(200)
                    )
                }

                val array =
                    JSONArray(body)

                val result =
                    mutableListOf<ModelInfo>()

                for (i in 0 until array.length()) {

                    val obj =
                        array.optJSONObject(i)
                            ?: continue

                    val name =
                        obj.optString("name")
                            .trim()

                    if (name.isBlank()) continue

                    val lower =
                        name.lowercase()

                    val isImage =
                        lower.contains(
                            "stable diffusion"
                        ) ||
                        lower.contains("sdxl") ||
                        lower.contains("flux") ||
                        lower.contains(
                            "dreamshaper"
                        ) ||
                        lower.contains(
                            "realistic"
                        ) ||
                        lower.contains(
                            "deliberate"
                        )

                    if (forImages != isImage) {
                        continue
                    }

                    result += ModelInfo(
                        id = name,
                        name = name,
                        provider = "horde",
                        isFree = true,
                        supportsVision = false,
                        supportsImageGeneration =
                            isImage,

                        recommended =
                            lower.contains("flux") ||
                            lower.contains("sdxl") ||
                            lower.contains("mistral") ||
                            lower.contains("llama-3")
                    )
                }

                return result
                    .distinctBy { it.id }
                    .sortedWith(
                        compareByDescending<ModelInfo> {
                            it.recommended
                        }
                            .thenBy {
                                it.name
                            }
                    )
            }
    }

    // ============================================================
// NVIDIA NIM - Dynamic Models
// ============================================================
private fun getNvidiaModels(apiKey: String): List<ModelInfo> {

    // إذا لم يكن هناك مفتاح، أرجع قائمة أساسية
    if (apiKey.isBlank()) {
        return getNvidiaFallbackModels()
    }

    return try {
        val request = Request.Builder()
            .url("https://integrate.api.nvidia.com/v1/models")
            .get()
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()

            // ✅ إذا فشل الـ API أرجع القائمة الاحتياطية
            if (!response.isSuccessful) {
                return getNvidiaFallbackModels()
            }

            val data = JSONObject(body).optJSONArray("data")
                ?: return getNvidiaFallbackModels()

            val result = mutableListOf<ModelInfo>()

            for (i in 0 until data.length()) {
                val obj = data.optJSONObject(i) ?: continue
                val id  = obj.optString("id").trim()
                if (id.isBlank()) continue

                val lower = id.lowercase()

                // ✅ فقط نماذج المحادثة
                val isChat = lower.contains("instruct") ||
                             lower.contains("chat")     ||
                             lower.contains("nemotron") ||
                             lower.contains("llama")    ||
                             lower.contains("mistral")  ||
                             lower.contains("gemma")    ||
                             lower.contains("qwen")     ||
                             lower.contains("deepseek") ||
                             lower.contains("phi")

                if (!isChat) continue

                // ✅ استبعاد نماذج الصور والصوت
                val isMedia = lower.contains("stable-diffusion") ||
                              lower.contains("whisper")           ||
                              lower.contains("embed")             ||
                              lower.contains("rerank")            ||
                              lower.contains("vlm")               ||
                              lower.contains("vision")

                if (isMedia) continue

                val contextLength = obj.optLong("context_window", 0L)
                    .takeIf { it > 0 }
                    ?: obj.optLong("max_tokens", 0L)

                result += ModelInfo(
                    id            = id,
                    name          = formatNvidiaName(id),
                    provider      = "nvidia",
                    isFree        = true,
                    supportsVision = false,
                    recommended   = lower.contains("nemotron") ||
                                    lower.contains("deepseek") ||
                                    lower.contains("qwen3"),
                    contextLength = contextLength
                )
            }

            // ✅ إذا كانت القائمة فارغة أرجع الاحتياطية
            if (result.isEmpty()) {
                return getNvidiaFallbackModels()
            }

            result
                .distinctBy { it.id }
                .sortedWith(
                    compareByDescending<ModelInfo> { it.recommended }
                        .thenBy { it.name }
                )
        }
    } catch (e: Exception) {
        // ✅ أي خطأ = أرجع القائمة الاحتياطية بدل رمي استثناء
        getNvidiaFallbackModels()
    }
}

// ✅ تنسيق اسم النموذج من ID
private fun formatNvidiaName(id: String): String {
    return id
        .substringAfterLast("/")
        .replace("-", " ")
        .split(" ")
        .joinToString(" ") { word ->
            word.replaceFirstChar { it.uppercase() }
        }
}

// ✅ قائمة احتياطية تظهر إذا فشل API
private fun getNvidiaFallbackModels(): List<ModelInfo> {
    return listOf(
        ModelInfo(
            id            = "nvidia/llama-3.1-nemotron-ultra-253b-v1",
            name          = "Nemotron Ultra 253B ⭐",
            provider      = "nvidia",
            isFree        = true,
            recommended   = true,
            contextLength = 128000
        ),
        ModelInfo(
            id            = "nvidia/llama-3.3-nemotron-super-49b-v1",
            name          = "Nemotron Super 49B",
            provider      = "nvidia",
            isFree        = true,
            recommended   = true,
            contextLength = 128000
        ),
        ModelInfo(
            id            = "nvidia/llama-3.1-nemotron-70b-instruct",
            name          = "Nemotron 70B",
            provider      = "nvidia",
            isFree        = true,
            recommended   = true,
            contextLength = 128000
        ),
        ModelInfo(
            id            = "meta/llama-3.3-70b-instruct",
            name          = "Llama 3.3 70B",
            provider      = "nvidia",
            isFree        = true,
            recommended   = true,
            contextLength = 128000
        ),
        ModelInfo(
            id            = "meta/llama-3.1-405b-instruct",
            name          = "Llama 3.1 405B",
            provider      = "nvidia",
            isFree        = true,
            recommended   = false,
            contextLength = 128000
        ),
        ModelInfo(
            id            = "deepseek-ai/deepseek-r1-0528",
            name          = "DeepSeek R1 0528",
            provider      = "nvidia",
            isFree        = true,
            recommended   = true,
            contextLength = 128000
        ),
        ModelInfo(
            id            = "qwen/qwen3-235b-a22b",
            name          = "Qwen3 235B",
            provider      = "nvidia",
            isFree        = true,
            recommended   = true,
            contextLength = 40960
        ),
        ModelInfo(
            id            = "mistralai/mistral-large-2-instruct",
            name          = "Mistral Large 2",
            provider      = "nvidia",
            isFree        = true,
            recommended   = false,
            contextLength = 128000
        ),
        ModelInfo(
            id            = "google/gemma-3-27b-it",
            name          = "Gemma 3 27B",
            provider      = "nvidia",
            isFree        = true,
            recommended   = false,
            contextLength = 128000
        ),
        ModelInfo(
            id            = "microsoft/phi-4",
            name          = "Phi 4",
            provider      = "nvidia",
            isFree        = true,
            recommended   = false,
            contextLength = 16384
        )
    )
}


    // ============================================================
    // Helpers
    // ============================================================

    private fun jsonArrayContains(
        array: JSONArray?,
        value: String
    ): Boolean {

        if (array == null) return false

        for (i in 0 until array.length()) {

            if (
                array.optString(i)
                    .equals(
                        value,
                        ignoreCase = true
                    )
            ) {
                return true
            }
        }

        return false
    }

    private fun isZeroPrice(
        value: String
    ): Boolean =
        value.trim()
            .toDoubleOrNull()
            ?.let { it == 0.0 }
            ?: false
}
