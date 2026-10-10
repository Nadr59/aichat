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

        // Enhancement safety (بدون تقييد الهوية، فقط ضمان الارتباط + قابلية الإرسال للويب)
        private const val MAX_ENHANCED_LEN = 900
        private const val MIN_KEYWORD_LEN = 3

        // Soft mode: نقبل سؤال فقط (0 نقاط) لو الهوية لم تكن واضحة للموديل
        private const val MIN_BULLETS = 0
        private const val MAX_BULLETS = 5
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

            if (result.isBlank() || result.trim().equals("NONE", ignoreCase = true)) {
                Log.d(TAG, "⚪ Curator found nothing relevant")
                ""
            } else {
                val trimmedResult = result.trim()

                Log.d(TAG, "✅ Curated: ${trimmedResult.take(80)}...")

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

        Log.d(TAG, "NEW CURATE START | query=$userQuery | candidates=${candidates.size}")

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

        Log.d(TAG, "NEW CURATE PROMPT | provider=$provider | model=$model | len=${prompt.length}")

        try {
            val rawResponse = callCuratorProvider(prompt = prompt, forceJson = true)

            Log.d(TAG, "NEW CURATE RAW RESPONSE | ${rawResponse.take(500)}")

            if (rawResponse.isBlank()) {
                Log.w(TAG, "NEW CURATE EMPTY RESPONSE")
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
            Log.w(TAG, "NEW CURATE EXCEPTION | ${e::class.java.simpleName}: ${e.message}", e)

            fallbackResult(
                candidates = candidates,
                reason = "Curator exception: ${e::class.java.simpleName}: ${e.message}"
            )
        }
    }

    // ============================================================
    // Query enhancement (Identity-First)
    // ============================================================

    suspend fun enhanceQuery(
        userQuery: String,
        mediatorIdentityText: String,
        memoryCandidates: List<MemoryItem> = emptyList()
    ): String = withContext(Dispatchers.IO) {

        val original = userQuery.trim()

        Log.d(
            TAG,
            "🧠 ENHANCE START | queryLen=${original.length} | identityLen=${mediatorIdentityText.length} | memoryCandidates=${memoryCandidates.size}"
        )

        if (original.isBlank()) return@withContext userQuery

        if (mediatorIdentityText.isBlank()) {
            Log.d(TAG, "🧠 ENHANCE SKIPPED | identity text is blank")
            return@withContext original
        }

        // enhancement يعمل حتى لو curator معطل (فصلنا السلوك)
        if (!settings.memoryCuratorEnabled) {
            Log.d(TAG, "🧠 ENHANCE | curator disabled but enhancement is allowed (decoupled)")
        }

        try {
            val prompt = MemoryCuratorPrompt.buildEnhancerPrompt(
                userQuery = original,
                mediatorIdentityText = mediatorIdentityText
            )

            val raw = callConfiguredProvider(prompt).trim()
            val enhanced = cleanupEnhancedText(raw)

            val notNone = enhanced.isNotBlank() && !enhanced.equals("NONE", ignoreCase = true)
            val related = hasKeywordOverlap(original, enhanced)
            val looksFormatted = looksLikeQuestionWithBullets(enhanced)

            val isValid = notNone && related && looksFormatted

            if (isValid) {
                Log.d(TAG, "🧠 ENHANCE SUCCESS | resultLen=${enhanced.length}")
                enhanced
            } else {
                Log.d(
                    TAG,
                    "🧠 ENHANCE INVALID | returning original | related=$related formatted=$looksFormatted rawLen=${raw.length} finalLen=${enhanced.length}"
                )
                original
            }

        } catch (e: Exception) {
            Log.w(TAG, "🧠 ENHANCE FAILED | ${e.message}", e)
            original
        }
    }

    // ============================================================
    // Style refinement (كما هو)
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

            if (result.isBlank() || result.equals("NONE", ignoreCase = true)) {
                userQuery
            } else {
                result
            }

        } catch (e: Exception) {
            Log.w(TAG, "🧠 STYLE REFINEMENT FAILED | ${e.message}", e)
            userQuery
        }
    }

    // ============================================================
    // Enhancement helpers (Soft)
    // ============================================================

    private fun cleanupEnhancedText(text: String): String {
        var t = text.trim()
        t = t.replace("```", "").trim()

        val rawLines = t.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }

        if (rawLines.isEmpty()) return ""

        // إزالة ميتا-كلام شائع إن وُجد (Soft: لا نحذف كل شيء، فقط أسطر واضحة جدًا)
        val metaPatterns = listOf(
            Regex("^كمساعد", RegexOption.IGNORE_CASE),
            Regex("^ك(ـ|)مساعد", RegexOption.IGNORE_CASE),
            Regex("إليك\\s+(الطلب|السؤال)\\s+المحس", RegexOption.IGNORE_CASE),
            Regex("سأقوم\\s+ب", RegexOption.IGNORE_CASE),
            Regex("يتطلب\\s+توضيح", RegexOption.IGNORE_CASE)
        )

        val lines = rawLines.filterNot { line ->
            metaPatterns.any { it.containsMatchIn(line) }
        }.ifEmpty { rawLines }

        // ابحث عن سطر "سؤال:" إن وجد
        val qIndex = lines.indexOfFirst {
            it.startsWith("سؤال:", ignoreCase = true) || it.startsWith("سؤال :", ignoreCase = true)
        }

        val usable = if (qIndex >= 0) lines.drop(qIndex) else lines
        if (usable.isEmpty()) return ""

        // السؤال: إمّا من سطر "سؤال:" أو من أول سطر
        val first = usable.first()
        val q = first.replace(Regex("^سؤال\\s*:\\s*", RegexOption.IGNORE_CASE), "").trim().trim('"').trim()

        fun toBullet(line: String): String? {
            val s = line.trim()
            if (s.isBlank()) return null

            return when {
                s.startsWith("•") -> "- " + s.removePrefix("•").trim()
                s.startsWith("-") -> "- " + s.removePrefix("-").trim()
                Regex("^\\d+\\s*[.)-]\\s+").containsMatchIn(s) ->
                    "- " + s.replace(Regex("^\\d+\\s*[.)-]\\s+"), "").trim()
                else -> null
            }
        }

        // bullets من الأسطر التالية
        val bullets = mutableListOf<String>()

        // 1) bullets صريحة
        usable.drop(1).forEach { line ->
            toBullet(line)?.let { bullets.add(it) }
        }

        // 2) إذا لم يعط bullets لكن كتب فقرات قصيرة، حوّل أول 5 أسطر إضافية إلى bullets (Soft)
        if (bullets.isEmpty()) {
            usable.drop(1)
                .take(MAX_BULLETS)
                .forEach { line ->
                    val s = line.trim()
                    if (s.isNotBlank()) bullets.add("- $s")
                }
        }

        val finalBullets = bullets
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .distinct()
            .filter { it.length >= 4 }
            .take(MAX_BULLETS)

        val built = buildString {
            appendLine("سؤال: $q")
            finalBullets.forEach { appendLine(it) }
        }.trim()

        return if (built.length <= MAX_ENHANCED_LEN) built else built.take(MAX_ENHANCED_LEN).trimEnd()
    }

    private fun looksLikeQuestionWithBullets(text: String): Boolean {
        if (text.isBlank()) return false

        val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return false

        val first = lines.first()
        if (!first.startsWith("سؤال:", ignoreCase = true)) return false

        // سؤال حقيقي
        val q = first.removePrefix("سؤال:").trim()
        if (q.length < 4) return false

        // bullets اختيارية (Soft) — لكن إن وجدت يجب أن تكون بصيغة "- "
        val bulletCount = lines.drop(1).count { it.startsWith("- ") }
        return bulletCount >= MIN_BULLETS
    }

    private fun hasKeywordOverlap(original: String, enhanced: String): Boolean {
        fun keywords(s: String): Set<String> =
            s.lowercase()
                .replace(Regex("[^\\p{L}\\p{Nd}\\s]"), " ")
                .split(Regex("\\s+"))
                .map { it.trim() }
                .filter { it.length >= MIN_KEYWORD_LEN }
                .toSet()

        val a = keywords(original)
        val b = keywords(enhanced)

        if (a.isEmpty() || b.isEmpty()) return false
        return a.any { it in b }
    }

    // ============================================================
    // Provider dispatcher (كما هو عندك)
    // ============================================================

    private suspend fun callCuratorProvider(
        prompt: String,
        forceJson: Boolean
    ): String {
        val provider = settings.memoryCuratorProvider.lowercase()
        val model = settings.getMemoryCuratorModel()

        return when (provider) {
            "groq" -> {
                val apiKey = settings.groqKey
                if (apiKey.isBlank()) throw Exception("Groq API key is blank")
                if (model.isBlank()) throw Exception("Groq curator model is blank")

                Log.d(TAG, "🧠 CURATOR PROVIDER | Groq | model=$model | forceJson=$forceJson")

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
                if (apiKey.isBlank()) throw Exception("Mistral API key is blank")
                if (model.isBlank()) throw Exception("Mistral curator model is blank")

                Log.d(TAG, "🧠 CURATOR PROVIDER | Mistral | model=$model | forceJson=$forceJson")

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
                if (model.isBlank()) throw Exception("Ollama curator model is blank")
                Log.d(TAG, "🧠 CURATOR PROVIDER | Ollama | model=$model | forceJson=$forceJson")
                callOllama(prompt = prompt, model = model, forceJson = forceJson)
            }

            "custom" -> {
                val baseUrl = settings.customUrl.trim()
                if (baseUrl.isBlank() || model.isBlank()) {
                    throw Exception("Custom curator provider not configured")
                }
                Log.d(TAG, "🧠 CURATOR PROVIDER | Custom | model=$model | forceJson=$forceJson")
                callCustomProvider(
                    prompt = prompt,
                    baseUrl = baseUrl,
                    apiKey = settings.customKey,
                    model = model,
                    forceJson = forceJson
                )
            }

            "gemini" -> {
                val apiKey = settings.geminiKey
                if (apiKey.isBlank()) throw Exception("Gemini API key is blank")
                if (model.isBlank()) throw Exception("Gemini curator model is blank")
                Log.d(TAG, "🧠 CURATOR PROVIDER | Gemini | model=$model | forceJson=$forceJson")
                callGeminiFlash(prompt = prompt, apiKey = apiKey, model = model, forceJson = forceJson)
            }

            else -> throw Exception("Unsupported curator provider: $provider")
        }
    }

    private suspend fun callConfiguredProvider(prompt: String): String {
        val provider = settings.memoryCuratorProvider.lowercase()
        val model = settings.getMemoryCuratorModel()

        return when (provider) {
            "groq" -> {
                val apiKey = settings.groqKey
                if (apiKey.isBlank()) throw Exception("Groq API key is blank")
                if (model.isBlank()) throw Exception("Groq curator model is blank")
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
                if (apiKey.isBlank()) throw Exception("Mistral API key is blank")
                if (model.isBlank()) throw Exception("Mistral curator model is blank")
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
                if (model.isBlank()) throw Exception("Ollama curator model is blank")
                callOllama(prompt = prompt, model = model, forceJson = false)
            }

            "custom" -> {
                val baseUrl = settings.customUrl.trim()
                if (baseUrl.isBlank() || model.isBlank()) {
                    throw Exception("Custom curator provider not configured")
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
                if (apiKey.isBlank()) throw Exception("Gemini API key is blank")
                if (model.isBlank()) throw Exception("Gemini curator model is blank")
                callGeminiFlash(prompt = prompt, apiKey = apiKey, model = model, forceJson = false)
            }

            else -> throw Exception("Unsupported curator provider: $provider")
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
            if (forceJson) put("responseMimeType", "application/json")
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
                                    put(JSONObject().apply { put("text", prompt) })
                                }
                            )
                        }
                    )
                }
            )
            put("generationConfig", generationConfig)
        }

        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .addHeader("x-goog-api-key", apiKey)
            .addHeader("Content-Type", "application/json")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string()
                throw Exception("Gemini Flash failed: ${response.code} - $errorBody")
            }

            val body = response.body?.string()
                ?: throw Exception("Empty response from Gemini Flash")

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
    // OpenAI compatible (Groq/Mistral/...)
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
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                    })
                }
            )
            put("temperature", 0.2)
            put("max_tokens", 500)

            if (forceJson) {
                put("response_format", JSONObject().apply { put("type", "json_object") })
            }
        }

        val request = Request.Builder()
            .url(baseUrl)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string()
                throw Exception("$providerName curator failed: ${response.code} - $errorBody")
            }

            val body = response.body?.string() ?: throw Exception("Empty response from $providerName")

            val message =
                JSONObject(body)
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")

            val content = message.optString("content", "")
            if (content.isBlank()) {
                throw Exception("$providerName returned empty content. Raw: ${body.take(500)}")
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
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                    })
                }
            )
            put("options", JSONObject().apply { put("temperature", 0.2) })
            if (forceJson) put("format", "json")
        }

        val request = Request.Builder()
            .url("http://127.0.0.1:11434/api/chat")
            .addHeader("Content-Type", "application/json")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string()
                throw Exception("Ollama curator failed: ${response.code} - $errorBody")
            }

            val body = response.body?.string() ?: throw Exception("Empty response from Ollama")
            val message = JSONObject(body).getJSONObject("message")
            val content = message.optString("content", "")

            if (content.isBlank()) {
                throw Exception("Ollama returned empty content. Raw: ${body.take(500)}")
            }

            return content
        }
    }

    // ============================================================
    // Custom provider
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
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                    })
                }
            )
            put("temperature", 0.2)
            put("max_tokens", 2000)

            if (forceJson) {
                put("response_format", JSONObject().apply { put("type", "json_object") })
            }
        }

        val requestBuilder =
            Request.Builder()
                .url(baseUrl)
                .addHeader("Content-Type", "application/json")
                .post(json.toString().toRequestBody("application/json".toMediaType()))

        if (apiKey.isNotBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer $apiKey")
        }

        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string()
                throw Exception("Custom curator failed: ${response.code} - $errorBody")
            }

            val body = response.body?.string() ?: throw Exception("Empty response from custom curator")
            val message = JSONObject(body).getJSONArray("choices").getJSONObject(0).getJSONObject("message")

            val content = message.optString("content", "").takeIf { it.isNotBlank() && it != "null" }
            if (content != null) return content

            val reasoning = message.optString("reasoning", "").takeIf { it.isNotBlank() && it != "null" }
            if (reasoning != null) return reasoning

            throw Exception("Both 'content' and 'reasoning' fields are empty. Raw: ${body.take(300)}")
        }
    }

    // ============================================================
    // Parser
    // ============================================================

    private fun parseCuratorResponse(
        rawResponse: String,
        candidates: List<MemoryItem>
    ): CuratorResult {

        val jsonText = extractJsonObject(rawResponse)
        val json = JSONObject(jsonText)

        val validIds = candidates.map { it.id }.toSet()

        val selected = jsonArrayToLongs(json.optJSONArray("selectedIds"))
        val irrelevant = jsonArrayToLongs(json.optJSONArray("irrelevantIds"))
        val reasoning = json.optString("reasoning", "").trim()

        val sanitizedSelected =
            selected.filter { it in validIds }
                .distinct()
                .take(5)

        val sanitizedIrrelevant =
            irrelevant.filter { it in validIds }
                .distinct()
                .filterNot { it in sanitizedSelected }

        if (selected.isEmpty()) {
            return CuratorResult(
                selectedIds = emptyList(),
                reasoning = if (reasoning.isNotBlank()) reasoning else "No directly relevant memory selected.",
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
            reasoning = if (reasoning.isNotBlank()) reasoning else "Selected relevant memories.",
            irrelevantIds = sanitizedIrrelevant,
            isFallback = false,
            fallbackReason = null,
            rawResponse = rawResponse
        )
    }

    private fun extractJsonObject(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) return trimmed

        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')

        if (start >= 0 && end > start) return trimmed.substring(start, end + 1)

        throw Exception("No JSON object found in curator response: ${trimmed.take(500)}")
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

    private fun fallbackResult(
        candidates: List<MemoryItem>,
        reason: String,
        rawResponse: String? = null
    ): CuratorResult {

        val ids = candidates.take(5).map { it.id }
        Log.w(TAG, "FALLBACK REASON | $reason | selected=$ids")

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

data class CuratorResult(
    val selectedIds: List<Long>,
    val reasoning: String,
    val irrelevantIds: List<Long> = emptyList(),
    val isFallback: Boolean = false,
    val fallbackReason: String? = null,
    val rawResponse: String? = null
)
