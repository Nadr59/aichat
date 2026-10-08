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
    // Strict ID-based curator
    // ============================================================

    suspend fun curateSelection(
        userQuery: String,
        candidates: List<MemoryItem>
    ): CuratorResult = withContext(Dispatchers.IO) {

        Log.d(
            TAG,
            "NEW CURATE START | query=$userQuery | " +
                "candidates=${candidates.size}"
        )

        if (candidates.isEmpty()) {
            Log.d(TAG, "NEW CURATE | no candidates")

            return@withContext CuratorResult(
                selectedIds = emptyList(),
                reasoning = "No candidates available."
            )
        }

        if (!settings.memoryCuratorEnabled) {
            Log.d(TAG, "NEW CURATE | curator disabled")

            return@withContext fallbackResult(
                candidates = candidates,
                reason = "Curator disabled by setting"
            )
        }

        val prompt = MemoryCuratorPrompt.buildSelectionPrompt(
            query = userQuery,
            candidates = candidates
        )

        val provider = settings.memoryCuratorProvider
        val model = settings.getMemoryCuratorModel()

        Log.d(
            TAG,
            "NEW CURATE PROMPT | provider=$provider | " +
                "model=$model | len=${prompt.length}"
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

                return@withContext fallbackResult(
                    candidates = candidates,
                    reason = "Empty curator response"
                )
            }

            parseCuratorResponse(
                rawResponse = rawResponse,
                candidates = candidates
            )

        } catch (e: Exception) {

            Log.w(
                TAG,
                "NEW CURATE EXCEPTION | " +
                    "${e::class.java.simpleName}: ${e.message}",
                e
            )

            fallbackResult(
                candidates = candidates,
                reason =
                    "Curator exception: " +
                        "${e::class.java.simpleName}: ${e.message}"
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

            val enhanced = callConfiguredProvider(prompt).trim()

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

        val provider = settings.memoryCuratorProvider.lowercase()
        val model = settings.getMemoryCuratorModel()

        return when (provider) {

            // ====================================================
            // Groq
            // ====================================================

            "groq" -> {
                val apiKey = settings.groqKey

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
                    baseUrl =
                        "https://api.groq.com/openai/v1/chat/completions",
                    apiKey = apiKey,
                    model = model,
                    prompt = prompt,
                    forceJson = forceJson
                )
            }

            // ====================================================
            // Mistral
            // ====================================================

            "mistral" -> {
                val apiKey = settings.mistralKey

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
                    baseUrl =
                        "https://api.mistral.ai/v1/chat/completions",
                    apiKey = apiKey,
                    model = model,
                    prompt = prompt,
                    forceJson = forceJson
                )
            }

            // ====================================================
            // Ollama
            // ====================================================

            "ollama" -> {
                if (model.isBlank()) {
                    throw Exception(
                        "Ollama curator model is blank"
                    )
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

            // ====================================================
            // Custom
            // ====================================================

            "custom" -> {
                val baseUrl = settings.customUrl.trim()

                if (
                    baseUrl.isBlank() ||
                    model.isBlank()
                ) {
                    throw Exception(
                        "Custom curator provider not configured"
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
                    model = model,
                    forceJson = forceJson
                )
            }

            // ====================================================
            // Gemini
            // ====================================================

            "gemini" -> {
                val apiKey = settings.geminiKey

                if (apiKey.isBlank()) {
                    throw Exception(
                        "Gemini API key is blank"
                    )
                }

                if (model.isBlank()) {
                    throw Exception(
                        "Gemini curator model is blank"
                    )
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

            else -> {
                throw Exception(
                    "Unsupported curator provider: $provider"
                )
            }
        }
    }

    // ============================================================
    // Configured provider — enhancement/refinement
    // ============================================================

    private suspend fun callConfiguredProvider(
        prompt: String
    ): String {

        val provider = settings.memoryCuratorProvider.lowercase()
        val model = settings.getMemoryCuratorModel()

        return when (provider) {

            "groq" -> {
                val apiKey = settings.groqKey

                if (apiKey.isBlank()) {
                    throw Exception("Groq API key is blank")
                }

                if (model.isBlank()) {
                    throw Exception("Groq curator model is blank")
                }

                callOpenAiCompatibleProvider(
                    providerName = "Groq",
                    baseUrl =
                        "https://api.groq.com/openai/v1/chat/completions",
                    apiKey = apiKey,
                    model = model,
                    prompt = prompt,
                    forceJson = false
                )
            }

            "mistral" -> {
                val apiKey = settings.mistralKey

                if (apiKey.isBlank()) {
                    throw Exception("Mistral API key is blank")
                }

                if (model.isBlank()) {
                    throw Exception("Mistral curator model is blank")
                }

                callOpenAiCompatibleProvider(
                    providerName = "Mistral",
                    baseUrl =
                        "https://api.mistral.ai/v1/chat/completions",
                    apiKey = apiKey,
                    model = model,
                    prompt = prompt,
                    forceJson = false
                )
            }

            "ollama" -> {
                if (model.isBlank()) {
                    throw Exception(
                        "Ollama curator model is blank"
                    )
                }

                callOllama(
                    prompt = prompt,
                    model = model,
                    forceJson = false
                )
            }

            "custom" -> {
                val baseUrl = settings.customUrl.trim()

                if (
                    baseUrl.isBlank() ||
                    model.isBlank()
                ) {
                    throw Exception(
                        "Custom curator provider not configured"
                    )
                }

                callCustomProvider(
                    prompt = prompt,
                    baseUrl = baseUrl,
                    apiKey = settings.customKey,
                    model = model,
                    forceJson = false
                )
            }

            "gemini" -> {
                val apiKey = settings.geminiKey

                if (apiKey.isBlank()) {
                    throw Exception(
                        "Gemini API key is blank"
                    )
                }

                if (model.isBlank()) {
                    throw Exception(
                        "Gemini curator model is blank"
                    )
                }

                callGeminiFlash(
                    prompt = prompt,
                    apiKey = apiKey,
                    model = model,
                    forceJson = false
                )
            }

            else -> {
                throw Exception(
                    "Unsupported curator provider: $provider"
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

            return JSONObject(body)
                .getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")
        }
    }

    // ============================================================
    // Groq / Mistral — OpenAI compatible
    // ============================================================

    private fun callOpenAiCompatibleProvider(
        providerName: String,
        baseUrl: String,
        apiKey: String,
        model: String,
        prompt: String,
        forceJson: Boolean
    ): String {

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

            put(
                "options",
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
        model: String,
        forceJson: Boolean
    ): String {

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

            if (forceJson) {
                put(
                    "response_format",
                    JSONObject().apply {
                        put("type", "json_object")
                    }
                )
            }
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

        val json =
            JSONObject(jsonText)

        val validIds =
            candidates
                .map { it.id }
                .toSet()

        val selected =
            jsonArrayToLongs(
                json.optJSONArray("selectedIds")
            )

        val irrelevant =
            jsonArrayToLongs(
                json.optJSONArray("irrelevantIds")
            )

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

        if (selected.isEmpty()) {

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

        if (sanitizedSelected.isEmpty()) {

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

            when (val value = array.opt(i)) {

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
            "FALLBACK REASON | $reason | selected=$ids"
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
// Result model
// ================================================================

data class CuratorResult(
    val selectedIds: List<Long>,
    val reasoning: String,
    val irrelevantIds: List<Long> = emptyList(),
    val isFallback: Boolean = false,
    val fallbackReason: String? = null,
    val rawResponse: String? = null
)
