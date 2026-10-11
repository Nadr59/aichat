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

        private const val CONTEXT_DISCLAIMER =
            "\n\n⚠️ ملاحظة: هذا كل ما هو مؤكد ومتاح بخصوص هذا الموضوع تحديداً. " +
                "لا تُضف تفاصيل تقنية أو تاريخية أو مؤسسية إضافية من معرفتك العامة " +
                "غير مذكورة صراحة أعلاه، حتى لو بدت مألوفة أو مرتبطة لديك."

        private const val MAX_REWRITE_LEN = 700
        private const val MIN_KEYWORD_LEN = 2
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
            val result = callCuratorProvider(prompt = prompt, forceJson = false)

            if (result.isBlank() || result.trim().equals("NONE", ignoreCase = true)) {
                Log.d(TAG, "⚪ Curator found nothing relevant")
                ""
            } else {
                val trimmedResult = result.trim()
                Log.d(TAG, "✅ Curated: ${trimmedResult.take(80)}...")
                if (candidates.isNotEmpty()) trimmedResult + CONTEXT_DISCLAIMER else trimmedResult
            }

        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Curator ($curatorProvider) EXCEPTION: ${e::class.java.simpleName}: ${e.message}", e)
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

        Log.d(TAG, "NEW CURATE START | query=$userQuery | candidates=${candidates.size}")

        if (candidates.isEmpty()) {
            return@withContext CuratorResult(selectedIds = emptyList(), reasoning = "No candidates available.")
        }

        if (!settings.memoryCuratorEnabled) {
            return@withContext fallbackResult(candidates, "Curator disabled by setting")
        }

        val prompt = MemoryCuratorPrompt.buildSelectionPrompt(query = userQuery, candidates = candidates)
        val provider = settings.memoryCuratorProvider
        val model = settings.getMemoryCuratorModel()

        Log.d(TAG, "NEW CURATE PROMPT | provider=$provider | model=$model | len=${prompt.length}")

        try {
            val rawResponse = callCuratorProvider(prompt = prompt, forceJson = true)
            Log.d(TAG, "NEW CURATE RAW RESPONSE | ${rawResponse.take(500)}")

            if (rawResponse.isBlank()) {
                return@withContext fallbackResult(candidates, "Empty curator response")
            }
            parseCuratorResponse(rawResponse, candidates)
        } catch (e: Exception) {
            Log.w(TAG, "NEW CURATE EXCEPTION | ${e::class.java.simpleName}: ${e.message}", e)
            fallbackResult(candidates, "Curator exception: ${e::class.java.simpleName}: ${e.message}")
        }
    }

    // ============================================================
    // Query enhancement (JSON minimal)
    // ============================================================

    suspend fun enhanceQuery(
        userQuery: String,
        mediatorIdentityText: String,
        memoryCandidates: List<MemoryItem> = emptyList()
    ): String = withContext(Dispatchers.IO) {

        val original = userQuery.trim()
        Log.d(TAG, "🧠 ENHANCE START | queryLen=${original.length} | identityLen=${mediatorIdentityText.length} | memoryCandidates=${memoryCandidates.size}")

        if (original.isBlank()) return@withContext userQuery

        try {
            val prompt = MemoryCuratorPrompt.buildEnhancerPrompt(
                userQuery = original,
                mediatorIdentityText = mediatorIdentityText
            )

            // ✅ forceJson=true لأننا نريد JSON minimal دائماً
            val raw = callCuratorProvider(prompt = prompt, forceJson = true).trim()
            Log.d(TAG, "🧠 ENHANCE RAW | ${raw.take(500)}")

            val rewritten = parseEnhancerJson(raw)?.let { cleanupRewrittenText(it) }.orEmpty()

            val isValid =
                rewritten.isNotBlank() &&
                    !rewritten.equals("NONE", ignoreCase = true) &&
                    rewritten.length >= 4 &&
                    rewritten.length <= MAX_REWRITE_LEN &&
                    hasKeywordOverlap(original, rewritten)

            if (isValid) {
                Log.d(TAG, "🧠 ENHANCE SUCCESS | len=${rewritten.length} | ${rewritten.take(200)}")
                rewritten
            } else {
                Log.d(TAG, "🧠 ENHANCE INVALID -> original | rewrittenLen=${rewritten.length}")
                original
            }

        } catch (e: Exception) {
            Log.w(TAG, "🧠 ENHANCE FAILED | ${e::class.java.simpleName}: ${e.message}", e)
            original
        }
    }

    // ============================================================
    // Style refinement (كما هو)
    // ============================================================

    suspend fun refineQueryStyle(
        userQuery: String,
        style: QueryStyle
    ): String = withContext(Dispatchers.IO) {
        if (!settings.memoryCuratorEnabled) return@withContext userQuery
        try {
            val prompt = MemoryCuratorPrompt.buildStyleRefinementPrompt(userQuery, style)
            val result = callCuratorProvider(prompt = prompt, forceJson = false).trim()
            if (result.isBlank() || result.equals("NONE", ignoreCase = true)) userQuery else result
        } catch (e: Exception) {
            Log.w(TAG, "🧠 STYLE REFINEMENT FAILED | ${e.message}", e)
            userQuery
        }
    }

    // ============================================================
    // Enhancer JSON parser + cleanup
    // ============================================================

    private fun parseEnhancerJson(rawResponse: String): String? {
        val jsonText = extractJsonObject(rawResponse)
        val obj = JSONObject(jsonText)

        // مفاتيحنا الرسمية: rewritten + outOfDomain
        val rewritten = obj.optString("rewritten", "").trim()
        if (rewritten.isBlank()) return null
        return rewritten
    }

    private fun cleanupRewrittenText(text: String): String {
        var t = text.trim()
        t = t.replace("```", "").trim()
        t = t.trim().trim('"').trim()
        // توحيد المسافات والأسطر (نريد عبارة “يلعب بها المستخدم” بدون فوضى)
        t = t.replace(Regex("\\s+"), " ").trim()
        return if (t.length <= MAX_REWRITE_LEN) t else t.take(MAX_REWRITE_LEN).trimEnd()
    }

    private fun hasKeywordOverlap(original: String, rewritten: String): Boolean {
        fun keywords(s: String): Set<String> =
            s.lowercase()
                .replace(Regex("[^\\p{L}\\p{Nd}\\s]"), " ")
                .split(Regex("\\s+"))
                .map { it.trim() }
                .filter { it.length >= MIN_KEYWORD_LEN }
                .toSet()

        val a = keywords(original)
        val b = keywords(rewritten)

        // إذا السؤال قصير جداً، لا نمنع التحسين بسبب overlap
        if (a.isEmpty()) return true
        if (b.isEmpty()) return false

        return a.any { it in b }
    }

    // ============================================================
    // Provider dispatcher
    // ============================================================

    private suspend fun callCuratorProvider(prompt: String, forceJson: Boolean): String {
        val provider = settings.memoryCuratorProvider.lowercase()
        val model = settings.getMemoryCuratorModel()

        return when (provider) {
            "groq" -> {
                val apiKey = settings.groqKey
                if (apiKey.isBlank()) throw Exception("Groq API key is blank")
                if (model.isBlank()) throw Exception("Groq curator model is blank")
                callOpenAiCompatibleProviderWithJsonRetry(
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
                if (apiKey.isBlank()) throw Exception("Mistral API key is blank")
                if (model.isBlank()) throw Exception("Mistral curator model is blank")
                callOpenAiCompatibleProviderWithJsonRetry(
                    providerName = "Mistral",
                    baseUrl = "https://api.mistral.ai/v1/chat/completions",
                    apiKey = apiKey,
                    model = model,
                    prompt = prompt,
                    forceJson = forceJson
                )
            }

            "ollama" -> {
                if (model.isBlank()) throw Exception("Ollama curator model is blank")
                callOllama(prompt, model, forceJson)
            }

            "custom" -> {
                val baseUrl = settings.customUrl.trim()
                if (baseUrl.isBlank() || model.isBlank()) throw Exception("Custom curator provider not configured")
                callCustomProviderWithJsonRetry(prompt, baseUrl, settings.customKey, model, forceJson)
            }

            "gemini" -> {
                val apiKey = settings.geminiKey
                if (apiKey.isBlank()) throw Exception("Gemini API key is blank")
                if (model.isBlank()) throw Exception("Gemini curator model is blank")
                callGeminiFlash(prompt, apiKey, model, forceJson)
            }

            else -> throw Exception("Unsupported curator provider: $provider")
        }
    }

    // ============================================================
    // Gemini / OpenAI / Ollama / Custom
    // ============================================================

    private fun callGeminiFlash(prompt: String, apiKey: String, model: String, forceJson: Boolean): String {
        val generationConfig = JSONObject().apply {
            put("temperature", 0.2)
            put("maxOutputTokens", 600)
            if (forceJson) put("responseMimeType", "application/json")
        }

        val json = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", prompt) })
                    })
                })
            })
            put("generationConfig", generationConfig)
        }

        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
        val request = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("Gemini failed: ${response.code} - ${response.body?.string()}")
            val body = response.body?.string() ?: throw Exception("Empty response from Gemini")
            return JSONObject(body)
                .getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")
        }
    }

    /**
     * ✅ مهم: بعض المزودين OpenAI-compatible قد لا يدعمون response_format.
     * لذلك إذا forceJson وفشل الطلب، نعيد المحاولة بدون response_format ونثق بالـ prompt.
     */
    private fun callOpenAiCompatibleProviderWithJsonRetry(
        providerName: String,
        baseUrl: String,
        apiKey: String,
        model: String,
        prompt: String,
        forceJson: Boolean
    ): String {
        fun buildBody(useResponseFormat: Boolean): String {
            val json = JSONObject().apply {
                put("model", model)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                    })
                })
                put("temperature", 0.2)
                put("max_tokens", 700)
                if (forceJson && useResponseFormat) {
                    put("response_format", JSONObject().apply { put("type", "json_object") })
                }
            }
            return json.toString()
        }

        fun execute(bodyJson: String): String {
            val request = Request.Builder()
                .url(baseUrl)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(bodyJson.toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string()
                if (!response.isSuccessful) throw HttpLikeException(response.code, body ?: "")
                val raw = body ?: throw Exception("Empty response from $providerName")
                val content = JSONObject(raw)
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .optString("content", "")
                if (content.isBlank()) throw Exception("$providerName returned empty content. Raw: ${raw.take(500)}")
                return content
            }
        }

        return try {
            execute(buildBody(useResponseFormat = true))
        } catch (e: HttpLikeException) {
            if (!forceJson) throw e
            // retry without response_format
            Log.w(TAG, "⚠️ $providerName forceJson failed (maybe response_format unsupported). Retrying without response_format. code=${e.code} body=${e.body.take(200)}")
            execute(buildBody(useResponseFormat = false))
        }
    }

    private fun callOllama(prompt: String, model: String, forceJson: Boolean): String {
        val json = JSONObject().apply {
            put("model", model)
            put("stream", false)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            })
            put("options", JSONObject().apply { put("temperature", 0.2) })
            if (forceJson) put("format", "json")
        }

        val request = Request.Builder()
            .url("http://127.0.0.1:11434/api/chat")
            .addHeader("Content-Type", "application/json")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("Ollama failed: ${response.code} - ${response.body?.string()}")
            val body = response.body?.string() ?: throw Exception("Empty response from Ollama")
            val content = JSONObject(body).getJSONObject("message").optString("content", "")
            if (content.isBlank()) throw Exception("Ollama returned empty content. Raw: ${body.take(500)}")
            return content
        }
    }

    private fun callCustomProviderWithJsonRetry(prompt: String, baseUrl: String, apiKey: String, model: String, forceJson: Boolean): String {
        fun buildBody(useResponseFormat: Boolean): String {
            val json = JSONObject().apply {
                put("model", model)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                    })
                })
                put("temperature", 0.2)
                put("max_tokens", 2000)
                if (forceJson && useResponseFormat) {
                    put("response_format", JSONObject().apply { put("type", "json_object") })
                }
            }
            return json.toString()
        }

        fun execute(bodyJson: String): String {
            val builder = Request.Builder()
                .url(baseUrl)
                .addHeader("Content-Type", "application/json")
                .post(bodyJson.toRequestBody("application/json".toMediaType()))
            if (apiKey.isNotBlank()) builder.addHeader("Authorization", "Bearer $apiKey")

            client.newCall(builder.build()).execute().use { response ->
                val body = response.body?.string()
                if (!response.isSuccessful) throw HttpLikeException(response.code, body ?: "")
                val raw = body ?: throw Exception("Empty response from custom")
                val message = JSONObject(raw).getJSONArray("choices").getJSONObject(0).getJSONObject("message")

                message.optString("content", "")
                    .takeIf { it.isNotBlank() && it != "null" }
                    ?.let { return it }

                message.optString("reasoning", "")
                    .takeIf { it.isNotBlank() && it != "null" }
                    ?.let { return it }

                throw Exception("Both 'content' and 'reasoning' are empty. Raw: ${raw.take(300)}")
            }
        }

        return try {
            execute(buildBody(useResponseFormat = true))
        } catch (e: HttpLikeException) {
            if (!forceJson) throw e
            Log.w(TAG, "⚠️ Custom forceJson failed (maybe response_format unsupported). Retrying without response_format. code=${e.code} body=${e.body.take(200)}")
            execute(buildBody(useResponseFormat = false))
        }
    }

    private class HttpLikeException(val code: Int, val body: String) : Exception("HTTP $code")

    // ============================================================
    // Parser (Selection)
    // ============================================================

    private fun parseCuratorResponse(rawResponse: String, candidates: List<MemoryItem>): CuratorResult {
        val jsonText = extractJsonObject(rawResponse)
        val json = JSONObject(jsonText)
        val validIds = candidates.map { it.id }.toSet()

        val selected = jsonArrayToLongs(json.optJSONArray("selectedIds"))
        val irrelevant = jsonArrayToLongs(json.optJSONArray("irrelevantIds"))
        val reasoning = json.optString("reasoning", "").trim()

        val sanitizedSelected = selected.filter { it in validIds }.distinct().take(5)
        val sanitizedIrrelevant = irrelevant.filter { it in validIds }.distinct().filterNot { it in sanitizedSelected }

        if (selected.isEmpty()) {
            return CuratorResult(
                emptyList(),
                if (reasoning.isNotBlank()) reasoning else "No directly relevant memory selected.",
                sanitizedIrrelevant,
                false,
                null,
                rawResponse
            )
        }
        if (sanitizedSelected.isEmpty()) {
            return fallbackResult(candidates, "All selected IDs were invalid", rawResponse)
        }
        return CuratorResult(
            sanitizedSelected,
            if (reasoning.isNotBlank()) reasoning else "Selected relevant memories.",
            sanitizedIrrelevant,
            false,
            null,
            rawResponse
        )
    }

    private fun extractJsonObject(raw: String): String {
        val trimmed = raw.trim().replace("```", "").trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) return trimmed
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start >= 0 && end > start) return trimmed.substring(start, end + 1)
        throw Exception("No JSON object found in response: ${trimmed.take(500)}")
    }

    private fun jsonArrayToLongs(array: JSONArray?): List<Long> {
        if (array == null) return emptyList()
        val result = mutableListOf<Long>()
        for (i in 0 until array.length()) {
            when (val v = array.opt(i)) {
                is Number -> result.add(v.toLong())
                is String -> v.toLongOrNull()?.let { result.add(it) }
            }
        }
        return result
    }

    private fun fallbackResult(candidates: List<MemoryItem>, reason: String, rawResponse: String? = null): CuratorResult {
        val ids = candidates.take(5).map { it.id }
        Log.w(TAG, "FALLBACK REASON | $reason | selected=$ids")
        return CuratorResult(ids, "Fallback to top candidates", emptyList(), true, reason, rawResponse)
    }
}

data class CuratorResult(
    val selectedIds: List<Long>,
    val reasoning: String,
    val irrelevantIds: List<Long> = emptyList(),
    val isFallback: Boolean = false,
    val fallbackReason: String? = null,
    val rawResponse: String? = null
)
