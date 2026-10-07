package com.example.aichat.repository

import android.util.Log
import com.example.aichat.data.local.AiSettings
import com.example.aichat.data.model.MemoryItem
import com.example.aichat.data.model.QueryStyle
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

        private const val MAX_SELECTED = 5

        private const val CONTEXT_DISCLAIMER =
            "\n\n⚠️ ملاحظة: هذا كل ما هو مؤكد ومتاح بخصوص هذا الموضوع تحديداً. " +
            "لا تُضف تفاصيل تقنية أو تاريخية أو مؤسسية إضافية من معرفتك العامة " +
            "غير مذكورة صراحة أعلاه، حتى لو بدت مألوفة أو مرتبطة لديك."
    }

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

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    // ============================================================
    // Curate — المسار القديم للتوافق
    // ============================================================

    @Deprecated(
        "Use curateSelection() for the new ID-based curator flow."
    )
    suspend fun curate(
        userQuery: String,
        candidates: List<MemoryItem>,
        mediatorIdentityText: String = settings.mediatorIdentityText
    ): String =
        withContext(Dispatchers.IO) {

            if (candidates.isEmpty() && mediatorIdentityText.isBlank()) {
                return@withContext ""
            }

            if (!settings.memoryCuratorEnabled) {
                Log.d(TAG, "⚪ Curator DISABLED by setting")
                return@withContext fallbackBuilder.build(candidates)
            }

            try {
                val prompt = MemoryCuratorPrompt.build(
                    userQuery,
                    candidates,
                    mediatorIdentityText
                )

                val result = callCuratorProvider(prompt)

                if (
                    result.isBlank() ||
                    result.trim().equals("NONE", ignoreCase = true)
                ) {
                    Log.d(TAG, "⚪ Curator found nothing relevant")
                    return@withContext ""
                }

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

            } catch (e: Exception) {

                Log.w(
                    TAG,
                    "⚠️ Curator EXCEPTION: ${e.message}",
                    e
                )

                fallbackBuilder.build(candidates)
            }
        }

    // ============================================================
    // Curate Selection — اختيار IDs من المرشحين
    // ============================================================

    suspend fun curateSelection(
        userQuery: String,
        candidates: List<MemoryItem>
    ): CuratorResult =
        withContext(Dispatchers.IO) {

            if (candidates.isEmpty()) {
                return@withContext CuratorResult(
                    selectedIds = emptyList(),
                    reasoning = "No candidates"
                )
            }

            if (!settings.memoryCuratorEnabled) {
                return@withContext fallbackResult(
                    candidates = candidates,
                    reason = "Curator disabled"
                )
            }

            try {
                val prompt = MemoryCuratorPrompt.buildSelectionPrompt(
                    query = userQuery,
                    candidates = candidates
                )

                Log.d(
                    TAG,
                    "🧠 CURATOR SELECTION START | " +
                        "queryLen=${userQuery.length} | " +
                        "candidates=${candidates.size}"
                )

                val rawResponse = callCuratorProvider(
                    prompt = prompt,
                    forceJson = true
                )

                Log.d(
                    TAG,
                    "🧠 CURATOR RESPONSE RECEIVED | " +
                        "len=${rawResponse.length}"
                )

                val parsed = parseCuratorResponse(
                    rawResponse = rawResponse,
                    candidates = candidates
                )

                Log.d(
                    TAG,
                    "🧠 CURATOR PARSED | " +
                        "selected=${parsed.selectedIds} | " +
                        "irrelevant=${parsed.irrelevantIds}"
                )

                parsed

            } catch (e: Exception) {

                Log.w(
                    TAG,
                    "⚠️ CURATOR SELECTION FAILED: ${e.message}",
                    e
                )

                fallbackResult(
                    candidates = candidates,
                    reason = e.message ?: "Unknown curator error"
                )
            }
        }

    // ============================================================
    // تحسين السؤال — Enhanced Query
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
                mediatorIdentityText = mediatorIdentityText,
                memoryCandidates = memoryCandidates
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
    // تحسين الصياغة حسب النمط
    // ============================================================

    suspend fun refineQueryStyle(
        userQuery: String,
        style: QueryStyle
    ): String = withContext(Dispatchers.IO) {

        if (userQuery.isBlank()) {
            return@withContext userQuery
        }

        if (!settings.memoryCuratorEnabled) {
            Log.d(
                TAG,
                "🎨 STYLE REFINE SKIPPED | curator disabled"
            )

            return@withContext userQuery
        }

        try {
            val prompt = style.buildPrompt(userQuery)

            Log.d(
                TAG,
                "🎨 STYLE REFINE START | " +
                    "style=${style.displayName} | " +
                    "queryLen=${userQuery.length}"
            )

            val refined = callConfiguredProvider(prompt).trim()

            if (refined.isBlank()) {
                Log.d(
                    TAG,
                    "🎨 STYLE REFINE EMPTY | returning original"
                )

                return@withContext userQuery
            }

            Log.d(
                TAG,
                "🎨 STYLE REFINE SUCCESS | " +
                    "style=${style.displayName} | " +
                    "len=${refined.length}"
            )

            refined

        } catch (e: Exception) {

            Log.w(
                TAG,
                "🎨 STYLE REFINE FAILED | ${e.message}",
                e
            )

            userQuery
        }
    }

    // ============================================================
    // اختيار مزود الوسيط
    // ============================================================

    private suspend fun callConfiguredProvider(
        prompt: String
    ): String {

        return when (settings.memoryCuratorProvider) {

            "ollama" -> {

                val model =
                    settings.memoryCuratorOllamaModel.trim()

                if (model.isBlank()) {
                    throw Exception(
                        "Ollama curator model is blank"
                    )
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
                    throw Exception(
                        "Gemini API key is blank"
                    )
                }

                Log.d(
                    TAG,
                    "🧠 ENHANCE PROVIDER | Gemini | " +
                        "model=${settings.memoryCuratorModel}"
                )

                callGeminiFlash(
                    prompt,
                    apiKey,
                    settings.memoryCuratorModel
                )
            }
        }
    }

    // ============================================================
    // مزود الوسيط — يدعم JSON للـ Curator
    // ============================================================

    private suspend fun callCuratorProvider(
        prompt: String,
        forceJson: Boolean = false
    ): String {

        return when (settings.memoryCuratorProvider) {

            "ollama" -> {

                val model =
                    settings.memoryCuratorOllamaModel.trim()

                if (model.isBlank()) {
                    throw Exception(
                        "Ollama curator model is blank"
                    )
                }

                Log.d(
                    TAG,
                    "🧠 CURATOR PROVIDER | Ollama | model=$model"
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
                        "Custom curator not configured"
                    )
                }

                Log.d(
                    TAG,
                    "🧠 CURATOR PROVIDER | Custom | model=$model"
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
                    throw Exception(
                        "Gemini API key is blank"
                    )
                }

                Log.d(
                    TAG,
                    "🧠 CURATOR PROVIDER | Gemini | " +
                        "model=${settings.memoryCuratorModel}"
                )

                callGeminiFlash(
                    prompt = prompt,
                    apiKey = apiKey,
                    model = settings.memoryCuratorModel,
                    forceJson = forceJson
                )
            }
        }
    }

    // ============================================================
    // Gemini Flash
    // ============================================================

    private fun callGeminiFlash(
        prompt: String,
        apiKey: String,
        model: String,
        forceJson: Boolean = false
    ): String {

        Log.d(
            TAG,
            "🧠 REQUEST SENT | Gemini | model=$model | " +
                "json=$forceJson | promptLen=${prompt.length}"
        )

        val generationConfig = JSONObject().apply {
            put("temperature", 0.2)
            put("maxOutputTokens", 2000)

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
                        "code=${response.code}"
                )

                throw Exception(
                    "Gemini Flash failed: " +
                        "${response.code} - $errorBody"
                )
            }

            val body =
                response.body?.string()
                    ?: throw Exception(
                        "Empty response from Gemini Flash"
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
                "🧠 GEMINI RESPONSE RECEIVED | " +
                    "len=${result.length()}"
            )

            return result
        }
    }

    // ============================================================
    // Ollama
    // ============================================================

    private fun callOllama(
        prompt: String,
        model: String,
        forceJson: Boolean = false
    ): String {

        Log.d(
            TAG,
            "🧠 REQUEST SENT | Ollama | model=$model | " +
                "json=$forceJson | promptLen=${prompt.length}"
        )

        val json = JSONObject().apply {

            put("model", model)
            put("stream", false)

            if (forceJson) {
                put("format", "json")
            }

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
        }

        val request = Request.Builder()
            .url("http://127.0.0.1:11434/api/chat")
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
                        "code=${response.code}"
                )

                throw Exception(
                    "Ollama failed: " +
                        "${response.code} - $errorBody"
                )
            }

            val body =
                response.body?.string()
                    ?: throw Exception(
                        "Empty response from Ollama"
                    )

            val result =
                JSONObject(body)
                    .getJSONObject("message")
                    .optString("content", "")

            if (result.isBlank()) {
                throw Exception(
                    "Ollama returned empty content"
                )
            }

            Log.d(
                TAG,
                "🧠 OLLAMA RESPONSE RECEIVED | " +
                    "len=${result.length()}"
            )

            return result
        }
    }

    // ============================================================
    // Custom OpenAI-compatible
    // ============================================================

    private fun callCustomProvider(
        prompt: String,
        baseUrl: String,
        apiKey: String,
        model: String
    ): String {

        Log.d(
            TAG,
            "🧠 REQUEST SENT | Custom | model=$model | " +
                "promptLen=${prompt.length}"
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

        val requestBuilder = Request.Builder()
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
                        "code=${response.code}"
                )

                throw Exception(
                    "Custom curator failed: " +
                        "${response.code} - $errorBody"
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
                message
                    .optString("content", "")
                    .takeIf {
                        it.isNotBlank() &&
                            it != "null"
                    }

            if (content != null) {

                Log.d(
                    TAG,
                    "🧠 CUSTOM RESPONSE RECEIVED | " +
                        "len=${content.length}"
                )

                return content
            }

            val reasoning =
                message
                    .optString("reasoning", "")
                    .takeIf {
                        it.isNotBlank() &&
                            it != "null"
                    }

            if (reasoning != null) {

                Log.d(
                    TAG,
                    "ℹ️ Custom curator: using reasoning"
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
    // تحليل نتيجة Curator
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
            parseLongArray(
                json.optJSONArray("selectedIds")
            )

        val irrelevant =
            parseLongArray(
                json.optJSONArray("irrelevantIds")
            )

        val sanitizedSelected =
            selected
                .filter { it in validIds }
                .distinct()
                .take(MAX_SELECTED)

        val sanitizedIrrelevant =
            irrelevant
                .filter {
                    it in validIds &&
                        it !in sanitizedSelected
                }
                .distinct()

        val reasoning =
            json.optString(
                "reasoning",
                ""
            ).trim()

        /*
         * selectedIds فارغة صحيحة:
         * معناها أن الوسيط قرر أنه لا توجد ذاكرة
         * مرتبطة مباشرة بالسؤال.
         */
        if (selected.isEmpty()) {
            return CuratorResult(
                selectedIds = emptyList(),
                reasoning = reasoning,
                irrelevantIds = sanitizedIrrelevant,
                isFallback = false,
                rawResponse = rawResponse
            )
        }

        /*
         * إذا أعاد النموذج IDs كلها غير موجودة
         * فعليًا في المرشحين، فهذا فشل في الاختيار
         * وليس "لا توجد ذاكرة".
         */
        if (
            sanitizedSelected.isEmpty() &&
            selected.isNotEmpty()
        ) {
            return fallbackResult(
                candidates = candidates,
                reason = "Curator returned invalid IDs",
                rawResponse = rawResponse
            )
        }

        return CuratorResult(
            selectedIds = sanitizedSelected,
            reasoning = reasoning,
            irrelevantIds = sanitizedIrrelevant,
            isFallback = false,
            rawResponse = rawResponse
        )
    }

    private fun parseLongArray(
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
                    value.toLongOrNull()
                        ?.let { result.add(it) }
                }
            }
        }

        return result
    }

    private fun extractJsonObject(
        rawResponse: String
    ): String {

        val cleaned =
            rawResponse
                .trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()

        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')

        if (start < 0 || end <= start) {
            throw Exception(
                "No JSON object found in curator response"
            )
        }

        return cleaned.substring(
            start,
            end + 1
        )
    }

    // ============================================================
    // Fallback
    // ============================================================

    private fun fallbackResult(
        candidates: List<MemoryItem>,
        reason: String,
        rawResponse: String? = null
    ): CuratorResult {

        val fallbackIds =
            candidates
                .take(MAX_SELECTED)
                .map { it.id }

        Log.w(
            TAG,
            "⚠️ CURATOR FALLBACK | " +
                "reason=$reason | ids=$fallbackIds"
        )

        return CuratorResult(
            selectedIds = fallbackIds,
            reasoning = "Fallback to top candidates",
            irrelevantIds = emptyList(),
            isFallback = true,
            fallbackReason = reason,
            rawResponse = rawResponse
        )
    }
}
