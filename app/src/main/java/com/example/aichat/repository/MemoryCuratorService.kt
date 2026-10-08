package com.example.aichat.repository

import android.util.Log
import com.example.aichat.data.local.AiSettings
import com.example.aichat.data.model.MemoryItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class MemoryCuratorService(
    private val settings: AiSettings,
    private val fallbackBuilder: MemoryContextBuilder
) {

    companion object {
        private const val TAG = "MemoryCurator"

        private const val CONTEXT_DISCLAIMER =
            "\n\n⚠️ ملاحظة: هذا كل ما هو مؤكد ومتاح بخصوص هذا الموضوع تحديداً. " +
                "لا تُضف تفاصيل تقنية أو تاريخية أو مؤسسية إضافية من معرفتك العامة " +
                "غير مذكورة صراحة أعلاه، حتى لو بدت مألوفة أو مرتبطة لديك."
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    // ============================================================
    // Legacy curate()
    // ============================================================

    @Deprecated("Use curateSelection() instead.")
    suspend fun curate(
        userQuery: String,
        candidates: List<MemoryItem>,
        mediatorIdentityText: String = settings.mediatorIdentityText
    ): String = withContext(Dispatchers.IO) {

        if (candidates.isEmpty() && mediatorIdentityText.isBlank()) {
            return@withContext ""
        }

        if (!settings.memoryCuratorEnabled) {
            Log.d(TAG, "⚪ Curator DISABLED by setting")
            return@withContext fallbackBuilder.build(candidates)
        }

        val prompt = MemoryCuratorPrompt.build(
            userQuery,
            candidates,
            mediatorIdentityText
        )

        val curatorProvider = settings.memoryCuratorProvider

        try {
            val result = callCuratorProvider(
                prompt = prompt,
                forceJson = false
            )

            if (
                result.isBlank() ||
                result.trim().equals("NONE", ignoreCase = true)
            ) {
                Log.d(TAG, "⚪ Curator found nothing relevant")
                ""
            } else {
                val trimmedResult = result.trim()

                Log.d(
                    TAG,
                    "✅ Curated: ${trimmedResult.take(80)}..."
                )

                if (candidates.isNotEmpty()) {
                    trimmedResult + CONTEXT_DISCLAIMER
                } else {
                    trimmedResult
                }
            }

        } catch (e: Exception) {
            Log.w(
                TAG,
                "⚠️ Curator ($curatorProvider) EXCEPTION: " +
                    "${e::class.java.simpleName}: ${e.message}",
                e
            )

            fallbackBuilder.build(candidates)
        }
    }

    // ============================================================
    // New strict ID-based curator
    // ============================================================

    suspend fun curateSelection(
        userQuery: String,
        candidates: List<MemoryItem>
    ): CuratorOutcome = withContext(Dispatchers.IO) {

        Log.d(
            TAG,
            "NEW CURATE START | query=$userQuery | " +
                "candidates=${candidates.size}"
        )

        if (candidates.isEmpty()) {
            Log.d(TAG, "NEW CURATE | no candidates")
            return@withContext CuratorOutcome.Success(
                CuratorResult(
                    selectedIds = emptyList(),
                    reasoning = "No candidates available."
                )
            )
        }

        if (!settings.memoryCuratorEnabled) {
            Log.d(TAG, "NEW CURATE | curator disabled")

            return@withContext CuratorOutcome.Fallback(
                fallbackResult(
                    candidates = candidates,
                    reason = "Curator disabled by setting"
                )
            )
        }

        val prompt = MemoryCuratorPrompt.buildSelectionPrompt(
            query = userQuery,
            candidates = candidates
        )

        Log.d(
            TAG,
            "NEW CURATE PROMPT | provider=${settings.memoryCuratorProvider} | " +
                "model=${settings.memoryCuratorModel} | len=${prompt.length}"
        )

        try {
            val rawResponse = callCuratorProvider(
                prompt = prompt,
                forceJson = true
            )

            Log.d(
                TAG,
                "NEW CURATE RAW RESPONSE | " +
                    rawResponse.take(500)
            )

            if (rawResponse.isBlank()) {
                Log.w(
                    TAG,
                    "NEW CURATE EMPTY RESPONSE"
                )

                return@withContext CuratorOutcome.Fallback(
                    fallbackResult(
                        candidates = candidates,
                        reason = "Empty curator response"
                    )
                )
            }

            val result = parseCuratorResponse(
                rawResponse = rawResponse,
                candidates = candidates
            )

            CuratorOutcome.Success(result)

        } catch (e: Exception) {

            Log.w(
                TAG,
                "NEW CURATE EXCEPTION | " +
                    "${e::class.java.simpleName}: ${e.message}",
                e
            )

            Log.w(
                TAG,
                "FALLBACK REASON | " +
                    "${e::class.java.simpleName}: ${e.message}"
            )

            CuratorOutcome.Fallback(
                fallbackResult(
                    candidates = candidates,
                    reason =
                        "Curator exception: " +
                            "${e::class.java.simpleName}: ${e.message}"
                )
            )
        }
    }

    // ============================================================
    // Query enhancement
    // ============================================================

    suspend fun enhanceQuery(
        userQuery: String,
        mediatorIdentityText: String,
        memoryCandidates: List<MemoryItem> = emptyList()
    ): String = withContext(Dispatchers.IO) {

        Log.d(
            TAG,
            "🧠 ENHANCE START | queryLen=${userQuery.length} | " +
                "identityLen=${mediatorIdentityText.length} | " +
                "memoryCandidates=${memoryCandidates.size}"
        )

        if (mediatorIdentityText.isBlank()) {
            Log.d(
                TAG,
                "🧠 ENHANCE SKIPPED | identity text is blank"
            )

            return@withContext userQuery
        }

        if (!settings.memoryCuratorEnabled) {
            Log.d(
                TAG,
                "🧠 ENHANCE SKIPPED | curator disabled"
            )

            return@withContext userQuery
        }

        try {
            val prompt = MemoryCuratorPrompt.buildEnhancerPrompt(
                userQuery = userQuery,
                mediatorIdentityText = mediatorIdentityText
            )

            Log.d(
                TAG,
                "🧠 ENHANCE PROMPT READY | len=${prompt.length}"
            )

            val enhanced = callConfiguredProvider(prompt).trim()

            Log.d(
                TAG,
                "🧠 ENHANCE RESPONSE RECEIVED | len=${enhanced.length}"
            )

            val isValid =
                enhanced.isNotBlank() &&
                    enhanced != userQuery &&
                    !enhanced.equals("NONE", ignoreCase = true) &&
                    enhanced.length > userQuery.length / 2

            if (isValid) {

                Log.d(
                    TAG,
                    "🧠 ENHANCE SUCCESS | resultLen=${enhanced.length}"
                )

                Log.d(
                    TAG,
                    "🧠 ENHANCE RESULT | ${enhanced.take(120)}"
                )

                enhanced

            } else {

                Log.d(
                    TAG,
                    "🧠 ENHANCE INVALID | returning original query"
                )

                userQuery
            }

        } catch (e: Exception) {

            Log.w(
                TAG,
                "🧠 ENHANCE FAILED | ${e.message}",
                e
            )

            userQuery
        }
    }

    // ============================================================
    // Style refinement
    // ============================================================

    suspend fun refineQueryStyle(
        userQuery: String,
        style: com.example.aichat.data.model.QueryStyle
    ): String = withContext(Dispatchers.IO) {

        if (!settings.memoryCuratorEnabled) {
            return@withContext userQuery
        }

        try {
            val prompt = MemoryCuratorPrompt.buildStyleRefinementPrompt(
                userQuery = userQuery,
                style = style
            )

            val result = callConfiguredProvider(prompt).trim()

            if (
                result.isBlank() ||
                result.equals("NONE", ignoreCase = true)
            ) {
                userQuery
            } else {
                result
            }

        } catch (e: Exception) {

            Log.w(
                TAG,
                "🧠 STYLE REFINEMENT FAILED | ${e.message}",
                e
            )

            userQuery
        }
    }

    // ============================================================
    // Curator provider dispatcher
    // ============================================================

    private suspend fun callCuratorProvider(
        prompt: String,
        forceJson: Boolean
    ): String {

        return when (settings.memoryCuratorProvider.lowercase()) {

            "groq" -> {
                val apiKey = settings.groqKey
                val model = settings.memoryCuratorModel.trim()

                if (apiKey.isBlank()) {
                    throw Exception("Groq API key is blank")
                }

                if (model.isBlank()) {
                    throw Exception("Groq curator model is blank")
                }

                Log.d(
                    TAG,
                    "🧠 CURATOR PROVIDER | Groq | model=$model | " +
                        "forceJson=$forceJson"
                )

                callOpenAiCompatibleProvider(
                    providerName = "Groq",
                    baseUrl = "https://api.groq.com/openai/v1/chat/completions",
                    apiKey = apiKey,
                    model = model,
                    prompt = prompt,
                    forceJson = forceJson
                )
            }

            "mistral" -> {
                val apiKey = settings.mistralKey
                val model = settings.memoryCuratorModel.trim()

                if (apiKey.isBlank()) {
                    throw Exception("Mistral API key is blank")
                }

                if (model.isBlank()) {
                    throw Exception("Mistral curator model is blank")
                }

                Log.d(
                    TAG,
                    "🧠 CURATOR PROVIDER | Mistral | model=$model | " +
                        "forceJson=$forceJson"
                )

                callOpenAiCompatibleProvider(
                    providerName = "Mistral",
                    baseUrl = "https://api.mistral.ai/v1/chat/completions",
                    apiKey = apiKey,
                    model = model,
                    prompt = prompt,
                    forceJson = forceJson
                )
            }

            "ollama" -> {
                val model = settings.memoryCuratorOllamaModel.trim()

                if (model.isBlank()) {
                    throw Exception("Ollama curator model is blank")
                }

                Log.d(
                    TAG,
                    "🧠 CURATOR PROVIDER | Ollama | model=$model | " +
                        "forceJson=$forceJson"
                )

                callOllama(
                    prompt = prompt,
                    model = model,
                    forceJson = forceJson
                )
            }

            "custom" -> {
                val baseUrl = settings.customUrl.trim()
                val model = settings.customModel.trim()

                if (baseUrl.isBlank() || model.isBlank()) {
                    throw Exception(
                        "Custom provider not configured"
                    )
                }

                Log.d(
                    TAG,
                    "🧠 CURATOR PROVIDER | Custom | model=$model | " +
                        "forceJson=$forceJson"
                )

                callCustomProvider(
                    prompt = prompt,
                    baseUrl = baseUrl,
                    apiKey = settings.customKey,
                    model = model
                )
            }

            else -> {
                val apiKey = settings.geminiKey
                val model = settings.memoryCuratorModel.trim()

                if (apiKey.isBlank()) {
                    throw Exception("Gemini API key is blank")
                }

                if (model.isBlank()) {
                    throw Exception("Gemini curator model is blank")
                }

                Log.d(
                    TAG,
                    "🧠 CURATOR PROVIDER | Gemini | model=$model | " +
                        "forceJson=$forceJson"
                )

                callGeminiFlash(
                    prompt = prompt,
                    apiKey = apiKey,
                    model = model,
                    forceJson = forceJson
                )
            }
        }
    }

    // ============================================================
    // Configured provider — used by enhancement/refinement
    // ============================================================

    private suspend fun callConfiguredProvider(
        prompt: String
    ): String {

        return when (settings.memoryCuratorProvider.lowercase()) {

            "groq" -> {
                val apiKey = settings.groqKey
                val model = settings.memoryCuratorModel.trim()

                if (apiKey.isBlank()) {
                    throw Exception("Groq API key is blank")
                }

                if (model.isBlank()) {
                    throw Exception("Groq curator model is blank")
                }

                Log.d(
                    TAG,
                    "🧠 ENHANCE PROVIDER | Groq | model=$model"
                )

                callOpenAiCompatibleProvider(
                    providerName = "Groq",
                    baseUrl = "https://api.groq.com/openai/v1/chat/completions",
                    apiKey = apiKey,
                    model = model,
                    prompt = prompt,
                    forceJson = false
                )
            }

            "mistral" -> {
                val apiKey = settings.mistralKey
                val model = settings.memoryCuratorModel.trim()

                if (apiKey.isBlank()) {
                    throw Exception("Mistral API key is blank")
                }

                if (model.isBlank()) {
                    throw Exception("Mistral curator model is blank")
                }

                Log.d(
                    TAG,
                    "🧠 ENHANCE PROVIDER | Mistral | model=$model"
                )

                callOpenAiCompatibleProvider(
                    providerName = "Mistral",
                    baseUrl = "https://api.mistral.ai/v1/chat/completions",
                    apiKey = apiKey,
                    model = model,
                    prompt = prompt,
                    forceJson = false
                )
            }

            "ollama" -> {
                val model = settings.memoryCuratorOllamaModel.trim()

                if (model.isBlank()) {
                    throw Exception("Ollama curator model is blank")
                }

                Log.d(
                    TAG,
                    "🧠 ENHANCE PROVIDER | Ollama | model=$model"
                )

                callOllama(
                    prompt = prompt,
                    model = model,
                    forceJson = false
                )
            }

            "custom" -> {
                val baseUrl = settings.customUrl.trim()
                val model = settings.customModel.trim()

                if (baseUrl.isBlank() || model.isBlank()) {
                    throw Exception(
                        "Custom provider not configured"
                    )
                }

                Log.d(
                    TAG,
                    "🧠 ENHANCE PROVIDER | Custom | model=$model"
                )

                callCustomProvider(
                    prompt = prompt,
                    baseUrl = baseUrl,
                    apiKey = settings.customKey,
                    model = model
                )
            }

            else -> {
                val apiKey = settings.geminiKey

                if (apiKey.isBlank()) {
                    throw Exception("Gemini API key is blank")
                }

                Log.d(
                    TAG,
                    "🧠 ENHANCE PROVIDER | Gemini | " +
                        "model=${settings.memoryCuratorModel}"
                )

                callGeminiFlash(
                    prompt = prompt,
                    apiKey = apiKey,
                    model = settings.memoryCuratorModel
                        .trim(),
                    forceJson = false
                )
            }
        }
    }

    // ============================================================
    // Gemini
    // ============================================================

    private fun callGeminiFlash(
        prompt: String,
        apiKey: String,
        model: String,
        forceJson: Boolean
    ): String {

        Log.d(
            TAG,
            "🧠 REQUEST SENT | Gemini | model=$model | " +
                "promptLen=${prompt.length} | forceJson=$forceJson"
        )

        val generationConfig = JSONObject().apply {
            put("temperature", 0.2)
            put("maxOutputTokens", 500)

            if (forceJson) {
                put(
                    "responseMimeType",
                    "application/json"
                )
            }
        }

        val json = JSONObject().apply {

            put(
                "contents",
                JSONArray().apply {
                    put(
                        JSONObject().apply {
                            put(
                                "parts",
                                JSONArray().apply {
                                    put(
                                        JSONObject().apply {
                                            put(
                                                "text",
                                                prompt
                                            )
                                        }
                                    )
                                }
                            )
                        }
                    )
                }
            )

            put(
                "generationConfig",
                generationConfig
            )
        }

        val request = Request.Builder()
            .url(
                "https://generativelanguage.googleapis.com/" +
                    "v1beta/models/$model:generateContent"
            )
            .addHeader(
                "x-goog-api-key",
                apiKey
            )
            .addHeader(
                "Content-Type",
                "application/json"
            )
            .post(
                json.toString()
                    .toRequestBody(
                        "application/json".toMediaType()
                    )
            )
            .build()

        client.newCall(request).execute().use { response ->

            if (!response.isSuccessful) {

                val errorBody =
                    response.body?.string()

                Log.w(
                    TAG,
                    "🧠 GEMINI HTTP ERROR | " +
                        "code=${response.code} | " +
                        "body=${errorBody?.take(500)}"
                )

                throw Exception(
                    "Gemini Flash failed: " +
                        "${response.code} - " +
                        errorBody
                )
            }

            val body =
                response.body?.string()
                    ?: throw Exception(
                        "Empty response from Gemini Flash"
                    )

            Log.d(
                TAG,
                "NEW CURATE GEMINI BODY | " +
                    body.take(500)
            )

            val result =
                JSONObject(body)
                    .getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")

            Log.d(
                TAG,
                "🧠 GEMINI RESPONSE PARSED | " +
                    "len=${result.length()}"
            )

            return result
        }
    }

    // ============================================================
    // Groq / Mistral — OpenAI-compatible
    // ============================================================

    private fun callOpenAiCompatibleProvider(
        providerName: String,
        baseUrl: String,
        apiKey: String,
        model: String,
        prompt: String,
        forceJson: Boolean
    ): String {

        Log.d(
            TAG,
            "🧠 REQUEST SENT | $providerName | " +
                "model=$model | promptLen=${prompt.length} | " +
                "forceJson=$forceJson"
        )

        val json = JSONObject().apply {

            put("model", model)

            put(
                "messages",
                JSONArray().apply {
                    put(
                        JSONObject().apply {
                            put("role", "user")
                            put("content", prompt)
                        }
                    )
                }
            )

            put("temperature", 0.2)
            put("max_tokens", 500)

            if (forceJson) {
                put(
                    "response_format",
                    JSONObject().apply {
                        put("type", "json_object")
                    }
                )
            }
        }

        val request = Request.Builder()
            .url(baseUrl)
            .addHeader(
                "Authorization",
                "Bearer $apiKey"
            )
            .addHeader(
                "Content-Type",
                "application/json"
            )
            .post(
                json.toString()
                    .toRequestBody(
                        "application/json".toMediaType()
                    )
            )
            .build()

        client.newCall(request).execute().use { response ->

            if (!response.isSuccessful) {

                val errorBody =
                    response.body?.string()

                Log.w(
                    TAG,
                    "🧠 $providerName HTTP ERROR | " +
                        "code=${response.code} | " +
                        "body=${errorBody?.take(500)}"
                )

                throw Exception(
                    "$providerName curator failed: " +
                        "${response.code} - " +
                        errorBody
                )
            }

            val body =
                response.body?.string()
                    ?: throw Exception(
                        "Empty response from $providerName"
                    )

            Log.d(
                TAG,
                "NEW CURATE $providerName BODY | " +
                    body.take(500)
            )

            val message =
                JSONObject(body)
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")

            val content =
                message.optString(
                    "content",
                    ""
                )

            if (content.isBlank()) {
                throw Exception(
                    "$providerName returned empty content. " +
                        "Raw: ${body.take(500)}"
                )
            }

            Log.d(
                TAG,
                "🧠 $providerName RESPONSE PARSED | " +
                    "len=${content.length}"
            )

            return content
        }
    }

    // ============================================================
    // Ollama
    // ============================================================

    private fun callOllama(
        prompt: String,
        model: String,
        forceJson: Boolean
    ): String {

        Log.d(
            TAG,
            "🧠 REQUEST SENT | Ollama | model=$model | " +
                "promptLen=${prompt.length} | forceJson=$forceJson"
        )

        val json = JSONObject().apply {

            put("model", model)
            put("stream", false)

            put(
                "messages",
                JSONArray().apply {
                    put(
                        JSONObject().apply {
                            put("role", "user")
                            put("content", prompt)
                        }
                    )
                }
            )

            put("options",
                JSONObject().apply {
                    put("temperature", 0.2)
                }
            )

            if (forceJson) {
                put("format", "json")
            }
        }

        val request = Request.Builder()
            .url(
                "http://127.0.0.1:11434/api/chat"
            )
            .addHeader(
                "Content-Type",
                "application/json"
            )
            .post(
                json.toString()
                    .toRequestBody(
                        "application/json".toMediaType()
                    )
            )
            .build()

        client.newCall(request).execute().use { response ->

            if (!response.isSuccessful) {

                val errorBody =
                    response.body?.string()

                Log.w(
                    TAG,
                    "🧠 OLLAMA HTTP ERROR | " +
                        "code=${response.code} | " +
                        "body=${errorBody?.take(500)}"
                )

                throw Exception(
                    "Ollama curator failed: " +
                        "${response.code} - " +
                        errorBody
                )
            }

            val body =
                response.body?.string()
                    ?: throw Exception(
                        "Empty response from Ollama"
                    )

            Log.d(
                TAG,
                "NEW CURATE OLLAMA BODY | " +
                    body.take(500)
            )

            val message =
                JSONObject(body)
                    .getJSONObject("message")

            val content =
                message.optString(
                    "content",
                    ""
                )

            if (content.isBlank()) {
                throw Exception(
                    "Ollama returned empty content. " +
                        "Raw: ${body.take(500)}"
                )
            }

            Log.d(
                TAG,
                "🧠 OLLAMA RESPONSE PARSED | " +
                    "len=${content.length}"
            )

            return content
        }
    }

    // ============================================================
    // Custom OpenAI-compatible provider
    // ============================================================

    private fun callCustomProvider(
        prompt: String,
        baseUrl: String,
        apiKey: String,
        model: String
    ): String {

        Log.d(
            TAG,
            "🧠 REQUEST SENT | Custom | " +
                "model=$model | promptLen=${prompt.length}"
        )

        val json = JSONObject().apply {

            put("model", model)

            put(
                "messages",
                JSONArray().apply {
                    put(
                        JSONObject().apply {
                            put("role", "user")
                            put("content", prompt)
                        }
                    )
                }
            )

            put("temperature", 0.2)
            put("max_tokens", 2000)
        }

        val requestBuilder =
            Request.Builder()
                .url(baseUrl)
                .addHeader(
                    "Content-Type",
                    "application/json"
                )
                .post(
                    json.toString()
                        .toRequestBody(
                            "application/json".toMediaType()
                        )
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

            if (!response.isSuccessful) {

                val errorBody =
                    response.body?.string()

                Log.w(
                    TAG,
                    "🧠 CUSTOM HTTP ERROR | " +
                        "code=${response.code} | " +
                        "body=${errorBody?.take(500)}"
                )

                throw Exception(
                    "Custom curator failed: " +
                        "${response.code} - " +
                        errorBody
                )
            }

            val body =
                response.body?.string()
                    ?: throw Exception(
                        "Empty response from custom curator"
                    )

            Log.d(
                TAG,
                "NEW CURATE CUSTOM BODY | " +
                    body.take(500)
            )

            val message =
                JSONObject(body)
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")

            val content =
                message.optString(
                    "content",
                    ""
                ).takeIf {
                    it.isNotBlank() &&
                        it != "null"
                }

            if (content != null) {

                Log.d(
                    TAG,
                    "🧠 CUSTOM RESPONSE PARSED | " +
                        "len=${content.length}"
                )

                return content
            }

            val reasoning =
                message.optString(
                    "reasoning",
                    ""
                ).takeIf {
                    it.isNotBlank() &&
                        it != "null"
                }

            if (reasoning != null) {

                Log.d(
                    TAG,
                    "ℹ️ Custom curator: " +
                        "using 'reasoning' field"
                )

                return reasoning
            }

            throw Exception(
                "Both 'content' and 'reasoning' fields " +
                    "are empty. Raw: ${body.take(300)}"
            )
        }
    }

    // ============================================================
    // Parser
    // ============================================================

    private fun parseCuratorResponse(
        rawResponse: String,
        candidates: List<MemoryItem>
    ): CuratorResult {

        val jsonText =
            extractJsonObject(rawResponse)

        Log.d(
            TAG,
            "NEW CURATE PARSER JSON | " +
                jsonText.take(500)
        )

        val json =
            JSONObject(jsonText)

        val validIds =
            candidates
                .map { it.id }
                .toSet()

        val selectedRaw =
            json.optJSONArray("selectedIds")

        val irrelevantRaw =
            json.optJSONArray("irrelevantIds")

        val selected =
            jsonArrayToLongs(selectedRaw)

        val irrelevant =
            jsonArrayToLongs(irrelevantRaw)

        val reasoning =
            json.optString(
                "reasoning",
                ""
            ).trim()

        val sanitizedSelected =
            selected
                .filter { it in validIds }
                .distinct()
                .take(5)

        val sanitizedIrrelevant =
            irrelevant
                .filter { it in validIds }
                .distinct()
                .filterNot {
                    it in sanitizedSelected
                }

        Log.d(
            TAG,
            "NEW CURATE PARSER SANITIZED | " +
                "selected=$sanitizedSelected | " +
                "irrelevant=$sanitizedIrrelevant | " +
                "reasoning=${reasoning.take(200)}"
        )

        /*
         * selectedIds=[] is a valid decision:
         * the curator found no relevant memory.
         */
        if (selected.isEmpty()) {

            Log.d(
                TAG,
                "NEW CURATE PARSER | " +
                    "valid empty selection"
            )

            return CuratorResult(
                selectedIds = emptyList(),
                reasoning =
                    if (reasoning.isNotBlank()) {
                        reasoning
                    } else {
                        "No directly relevant memory selected."
                    },
                irrelevantIds = sanitizedIrrelevant,
                isFallback = false,
                fallbackReason = null,
                rawResponse = rawResponse
            )
        }

        /*
         * If the model returned IDs, but every one of them
         * is invalid, fallback to the top candidates.
         */
        if (sanitizedSelected.isEmpty()) {

            Log.w(
                TAG,
                "NEW CURATE PARSER | " +
                    "all selected IDs invalid"
            )

            return fallbackResult(
                candidates = candidates,
                reason = "All selected IDs were invalid",
                rawResponse = rawResponse
            )
        }

        return CuratorResult(
            selectedIds = sanitizedSelected,
            reasoning =
                if (reasoning.isNotBlank()) {
                    reasoning
                } else {
                    "Selected relevant memories."
                },
            irrelevantIds = sanitizedIrrelevant,
            isFallback = false,
            fallbackReason = null,
            rawResponse = rawResponse
        )
    }

    // ============================================================
    // JSON helpers
    // ============================================================

    private fun extractJsonObject(
        raw: String
    ): String {

        val trimmed = raw.trim()

        if (
            trimmed.startsWith("{") &&
            trimmed.endsWith("}")
        ) {
            return trimmed
        }

        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')

        if (
            start >= 0 &&
            end > start
        ) {
            return trimmed.substring(
                start,
                end + 1
            )
        }

        throw Exception(
            "No JSON object found in curator response: " +
                trimmed.take(500)
        )
    }

    private fun jsonArrayToLongs(
        array: JSONArray?
    ): List<Long> {

        if (array == null) {
            return emptyList()
        }

        val result = mutableListOf<Long>()

        for (i in 0 until array.length()) {
            val value = array.opt(i)

            when (value) {
                is Number -> {
                    result.add(value.toLong())
                }

                is String -> {
                    value.toLongOrNull()?.let {
                        result.add(it)
                    }
                }
            }
        }

        return result
    }

    // ============================================================
    // Fallback
    // ============================================================

    private fun fallbackResult(
        candidates: List<MemoryItem>,
        reason: String,
        rawResponse: String? = null
    ): CuratorResult {

        val ids =
            candidates
                .take(5)
                .map { it.id }

        Log.w(
            TAG,
            "FALLBACK REASON | $reason | " +
                "selected=$ids"
        )

        return CuratorResult(
            selectedIds = ids,
            reasoning = "Fallback to top candidates",
            irrelevantIds = emptyList(),
            isFallback = true,
            fallbackReason = reason,
            rawResponse = rawResponse
        )
    }
}

// ================================================================
// Result models
// ================================================================

data class CuratorResult(
    val selectedIds: List<Long>,
    val reasoning: String,
    val irrelevantIds: List<Long> = emptyList(),
    val isFallback: Boolean = false,
    val fallbackReason: String? = null,
    val rawResponse: String? = null
)

sealed interface CuratorOutcome {

    data class Success(
        val result: CuratorResult
    ) : CuratorOutcome

    data class Fallback(
        val result: CuratorResult
    ) : CuratorOutcome
}
