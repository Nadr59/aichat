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

        // Enhancement safety
        private const val MAX_ENHANCED_LEN = 900
        private const val MIN_KEYWORD_LEN = 2
        private const val MIN_BULLETS = 0
        private const val MAX_BULLETS = 5

        private val ALERT_LABELS = listOf("تنبيه", "alert")
        private val QUESTION_LABELS = listOf("سؤال", "question")
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
                if (candidates.isNotEmpty()) trimmedResult + CONTEXT_DISCLAIMER else trimmedResult
            }

        } catch (e: Exception) {
            Log.w(
                TAG,
                "⚠️ Curator ($curatorProvider) EXCEPTION: ${e::class.java.simpleName}: ${e.message}",
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
            return@withContext CuratorResult(
                selectedIds = emptyList(),
                reasoning = "No candidates available."
            )
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
    // Query enhancement
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

        try {
            val prompt = MemoryCuratorPrompt.buildEnhancerPrompt(
                userQuery = original,
                mediatorIdentityText = mediatorIdentityText
            )

            val raw = callCuratorProvider(prompt = prompt, forceJson = false).trim()
            Log.d(TAG, "🧠 ENHANCE RAW | ${raw.take(500)}")

            val enhanced = cleanupEnhancedText(raw)
            Log.d(TAG, "🧠 ENHANCE CLEAN | len=${enhanced.length} | ${enhanced.take(300)}")

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
            Log.w(TAG, "🧠 ENHANCE FAILED | ${e::class.java.simpleName}: ${e.message}", e)
            original
        }
    }

    // ============================================================
    // Style refinement
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
    // Enhancement helpers
    // ============================================================

    private fun cleanupEnhancedText(text: String): String {
        var t = text.trim().replace("```", "").trim()
        val rawLines = t.lines().map { it.trim() }.filter { it.isNotBlank() }
        if (rawLines.isEmpty()) return ""

        // Remove common meta lines (Arabic + English)
        val metaPatterns = listOf(
            Regex("^كمساعد", RegexOption.IGNORE_CASE),
            Regex("^ك(ـ|)مساعد", RegexOption.IGNORE_CASE),
            Regex("إليك\\s+(الطلب|السؤال)\\s+المحس", RegexOption.IGNORE_CASE),
            Regex("سأقوم\\s+ب", RegexOption.IGNORE_CASE),
            Regex("يتطلب\\s+توضيح", RegexOption.IGNORE_CASE),
            Regex("^as an ai", RegexOption.IGNORE_CASE),
            Regex("^i will", RegexOption.IGNORE_CASE),
            Regex("^here(?:'s| is)\\s+(the\\s+)?(improved|enhanced)", RegexOption.IGNORE_CASE)
        )

        val lines = rawLines
            .filterNot { line -> metaPatterns.any { it.containsMatchIn(line) } }
            .ifEmpty { rawLines }

        // Detect optional alert line (Arabic/English)
        val firstLine = lines.firstOrNull() ?: return ""
        val isAlertLine = isLabeledLine(firstLine, ALERT_LABELS)
        val alertLine = if (isAlertLine) normalizeLabelLine(firstLine) else null

        // Find question line (Arabic/English)
        val startIndex = if (isAlertLine) 1 else 0
        val qIndex = lines.drop(startIndex).indexOfFirst { isLabeledLine(it, QUESTION_LABELS) }
            .let { if (it >= 0) it + startIndex else -1 }

        // Fallback: if model didn't write "سؤال:"/"Question:", treat the first non-alert line as question.
        val actualQIndex = if (qIndex >= 0) qIndex else startIndex
        val qLine = lines.getOrNull(actualQIndex) ?: return ""

        // Decide language style based on label used, otherwise by text content
        val usesEnglishLabel = qLine.trimStart().startsWith("Question", ignoreCase = true)
        val questionLabel = if (usesEnglishLabel) "Question" else "سؤال"
        val alertLabel = if (usesEnglishLabel) "Alert" else "تنبيه"

        val questionText = if (isLabeledLine(qLine, QUESTION_LABELS)) {
            stripAnyLabel(qLine).trim().trim('"').trim()
        } else {
            qLine.trim().trim('"').trim()
        }
        if (questionText.length < 4) return ""

        fun toBullet(line: String): String? {
            val s = line.trim()
            if (s.isBlank()) return null
            if (isLabeledLine(s, ALERT_LABELS) || isLabeledLine(s, QUESTION_LABELS)) return null

            val normalized = when {
                s.startsWith("•") -> s.removePrefix("•").trim()
                s.startsWith("-") -> s.removePrefix("-").trim()
                s.startsWith("*") -> s.removePrefix("*").trim()
                s.startsWith("—") -> s.removePrefix("—").trim()
                s.startsWith("–") -> s.removePrefix("–").trim()
                // numeric bullets: 1) / ١) / 1. / ١. / 1- ...
                Regex("^[0-9٠-٩]+\\s*[.)-]\\s+").containsMatchIn(s) ->
                    s.replace(Regex("^[0-9٠-٩]+\\s*[.)-]\\s+"), "").trim()
                else -> return null
            }
            return normalized.takeIf { it.length >= 2 }?.let { "- $it" }
        }

        val afterQ = lines.drop(actualQIndex + 1)

        val bullets = mutableListOf<String>()
        afterQ.forEach { line -> toBullet(line)?.let { bullets.add(it) } }

        // Soft fallback: if no bullets were recognized, convert normal lines to bullets
        if (bullets.isEmpty()) {
            afterQ.take(MAX_BULLETS).forEach { line ->
                val s = line.trim()
                if (s.isNotBlank() && !isLabeledLine(s, ALERT_LABELS) && !isLabeledLine(s, QUESTION_LABELS)) {
                    bullets.add("- $s")
                }
            }
        }

        val finalBullets = bullets
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .distinct()
            .filter { it.length >= 4 }
            .take(MAX_BULLETS)

        // Normalize alert line label if present
        val normalizedAlert = alertLine?.let {
            // If the model used English output but alert came Arabic, or vice versa, normalize to chosen alertLabel
            val msg = stripAnyLabel(it).trim()
            "$alertLabel: $msg"
        }

        val built = buildString {
            if (!normalizedAlert.isNullOrBlank()) appendLine(normalizedAlert)
            appendLine("$questionLabel: $questionText")
            finalBullets.forEach { appendLine(it) }
        }.trim()

        return if (built.length <= MAX_ENHANCED_LEN) built else built.take(MAX_ENHANCED_LEN).trimEnd()
    }

    private fun looksLikeQuestionWithBullets(text: String): Boolean {
        if (text.isBlank()) return false
        val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return false

        var idx = 0
        if (isLabeledLine(lines[0], ALERT_LABELS)) {
            if (lines.size < 2) return false
            idx = 1
        }

        val qLine = lines.getOrNull(idx) ?: return false
        if (!isLabeledLine(qLine, QUESTION_LABELS)) return false

        val q = stripAnyLabel(qLine).trim()
        if (q.length < 4) return false

        val bulletCount = lines.drop(idx + 1).count { it.startsWith("- ") }
        return bulletCount in MIN_BULLETS..MAX_BULLETS
    }

    private fun hasKeywordOverlap(original: String, enhanced: String): Boolean {
        fun keywords(s: String): Set<String> =
            s.lowercase()
                .replace(Regex("[^\\p{L}\\p{Nd}\\s]"), " ")
                .split(Regex("\\s+"))
                .map { it.trim() }
                .filter { it.length >= MIN_KEYWORD_LEN }
                .toSet()

        val cleanEnhanced = enhanced.lines()
            .filterNot { isLabeledLine(it, ALERT_LABELS) }
            .joinToString(" ")

        val a = keywords(original)
        val b = keywords(cleanEnhanced)

        // If original is too short / yields no keywords, don't block enhancement.
        if (a.isEmpty()) return true
        if (b.isEmpty()) return false

        return a.any { it in b }
    }

    private fun isLabeledLine(line: String, labels: List<String>): Boolean {
        val t = line.trimStart()
        return labels.any { lbl ->
            t.startsWith("$lbl:", ignoreCase = true) || t.startsWith("$lbl :", ignoreCase = true)
        }
    }

    private fun stripAnyLabel(line: String): String {
        val t = line.trimStart()
        // Removes "سؤال:" / "Question:" / "تنبيه:" / "Alert:" generically
        val idx = t.indexOf(':')
        if (idx <= 0) return t
        val head = t.substring(0, idx).trim()
        val tail = t.substring(idx + 1)
        val isKnown = (QUESTION_LABELS + ALERT_LABELS).any { it.equals(head, ignoreCase = true) }
        return if (isKnown) tail else t
    }

    private fun normalizeLabelLine(line: String): String {
        // Just normalize spaces around ":" for label lines
        val t = line.trim()
        val idx = t.indexOf(':')
        if (idx < 0) return t
        val head = t.substring(0, idx).trim()
        val tail = t.substring(idx + 1).trim()
        return "$head: $tail"
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
                callOllama(prompt, model, forceJson)
            }

            "custom" -> {
                val baseUrl = settings.customUrl.trim()
                if (baseUrl.isBlank() || model.isBlank()) throw Exception("Custom curator provider not configured")
                callCustomProvider(prompt, baseUrl, settings.customKey, model, forceJson)
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

        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
        val request = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("Gemini failed: ${response.code} - ${response.body?.string()}")
            }
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
                throw Exception("$providerName failed: ${response.code} - ${response.body?.string()}")
            }
            val body = response.body?.string() ?: throw Exception("Empty response from $providerName")
            val content = JSONObject(body)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .optString("content", "")

            if (content.isBlank()) throw Exception("$providerName returned empty content. Raw: ${body.take(500)}")
            return content
        }
    }

    private fun callOllama(prompt: String, model: String, forceJson: Boolean): String {
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
            if (!response.isSuccessful) throw Exception("Ollama failed: ${response.code} - ${response.body?.string()}")
            val body = response.body?.string() ?: throw Exception("Empty response from Ollama")
            val content = JSONObject(body).getJSONObject("message").optString("content", "")
            if (content.isBlank()) throw Exception("Ollama returned empty content. Raw: ${body.take(500)}")
            return content
        }
    }

    private fun callCustomProvider(prompt: String, baseUrl: String, apiKey: String, model: String, forceJson: Boolean): String {
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

        val builder = Request.Builder()
            .url(baseUrl)
            .addHeader("Content-Type", "application/json")
            .post(json.toString().toRequestBody("application/json".toMediaType()))

        if (apiKey.isNotBlank()) builder.addHeader("Authorization", "Bearer $apiKey")

        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw Exception("Custom failed: ${response.code} - ${response.body?.string()}")
            val body = response.body?.string() ?: throw Exception("Empty response from custom")
            val message = JSONObject(body).getJSONArray("choices").getJSONObject(0).getJSONObject("message")

            message.optString("content", "")
                .takeIf { it.isNotBlank() && it != "null" }
                ?.let { return it }

            message.optString("reasoning", "")
                .takeIf { it.isNotBlank() && it != "null" }
                ?.let { return it }

            throw Exception("Both 'content' and 'reasoning' are empty. Raw: ${body.take(300)}")
        }
    }

    // ============================================================
    // Parser
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
                selectedIds = emptyList(),
                reasoning = if (reasoning.isNotBlank()) reasoning else "No directly relevant memory selected.",
                irrelevantIds = sanitizedIrrelevant,
                isFallback = false,
                fallbackReason = null,
                rawResponse = rawResponse
            )
        }

        if (sanitizedSelected.isEmpty()) {
            return fallbackResult(candidates, "All selected IDs were invalid", rawResponse)
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
        val trimmed = raw.trim().replace("```", "").trim()
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

    private fun fallbackResult(candidates: List<MemoryItem>, reason: String, rawResponse: String? = null): CuratorResult {
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
