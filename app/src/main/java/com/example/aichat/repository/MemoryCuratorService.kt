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

    /**
     * نتيجة اختيار الذاكرة.
     *
     * هذه الحقول الداخلية لا يطلبها الـ LLM.
     */
    data class CuratorResult(
        val selectedIds: List<Long>,
        val reasoning: String,
        val irrelevantIds: List<Long> = emptyList(),
        val isFallback: Boolean = false,
        val fallbackReason: String? = null,
        val rawResponse: String? = null
    )

    sealed interface CuratorOutcome {
        data class Success(val result: CuratorResult) : CuratorOutcome
        data class Fallback(val result: CuratorResult) : CuratorOutcome
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * المرحلة 2:
     * اختيار الذكريات بواسطة IDs الحقيقية.
     *
     * هذه الدالة لا تعيد نصاً مولداً من الـ LLM.
     * تعيد فقط IDs لمصادر الذاكرة الأصلية.
     */
    suspend fun curateSelection(
        userQuery: String,
        candidates: List<MemoryItem>
    ): CuratorResult = withContext(Dispatchers.IO) {

        if (candidates.isEmpty()) {
            return@withContext CuratorResult(
                selectedIds = emptyList(),
                reasoning = "No candidates",
                rawResponse = null
            )
        }

        if (!settings.memoryCuratorEnabled) {
            return@withContext fallbackResult(
                candidates = candidates,
                reason = "Curator disabled"
            )
        }

        val prompt = MemoryCuratorPrompt.buildSelectionPrompt(
            query = userQuery,
            candidates = candidates
        )

        val curatorProvider = settings.memoryCuratorProvider

        try {
            val rawResponse = when (curatorProvider) {

                "custom" -> {
                    val baseUrl = settings.customUrl.trim()
                    val model = settings.customModel.trim()

                    if (baseUrl.isBlank() || model.isBlank()) {
                        return@withContext fallbackResult(
                            candidates,
                            "Custom curator URL or model blank"
                        )
                    }

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
                        return@withContext fallbackResult(
                            candidates,
                            "Gemini API key blank"
                        )
                    }

                    callGeminiFlash(
                        prompt = prompt,
                        apiKey = apiKey,
                        model = settings.memoryCuratorModel
                    )
                }
            }

            val parsed = parseCuratorResponse(rawResponse)

            val sanitized = sanitize(
                result = parsed.copy(rawResponse = rawResponse),
                candidates = candidates
            )

            if (sanitized.isFallback) {
                Log.w(
                    TAG,
                    "⚠️ Curator validation fallback: ${sanitized.fallbackReason}"
                )
            } else {
                Log.d(
                    TAG,
                    "✅ Curator selected IDs: ${sanitized.selectedIds}"
                )
            }

            sanitized

        } catch (e: Exception) {

            Log.w(
                TAG,
                "⚠️ Curator ($curatorProvider) EXCEPTION: ${e.message}",
                e
            )

            fallbackResult(
                candidates = candidates,
                reason = "API/Parser failure: ${e.message}"
            )
        }
    }

    /**
     * التوافق مع المسار القديم.
     *
     * مهم:
     * لا نعيد JSON للـ ChatViewModel.
     * نعيد محتوى الذكريات الأصلية المختارة فقط.
     *
     * سيتم حذف هذه الدالة بعد الانتقال إلى MemoryWindowBuilder
     * في المرحلة 3.
     */
    @Deprecated("Use curateSelection for MemoryWindow")
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

        /*
         * إذا لم توجد ذاكرة، نحافظ على السلوك القديم الخاص بالهوية.
         * لا نغير مسار enhancer الحالي.
         */
        if (candidates.isEmpty()) {
            return@withContext try {
                val prompt = MemoryCuratorPrompt.build(
                    userQuery,
                    candidates,
                    mediatorIdentityText
                )

                val result = callCuratorProvider(prompt)

                if (result.isBlank() || result.trim().equals("NONE", ignoreCase = true)) {
                    ""
                } else {
                    result.trim()
                }
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Legacy curator failed: ${e.message}")
                ""
            }
        }

        val result = curateSelection(
            userQuery = userQuery,
            candidates = candidates
        )

        val selected = result.selectedIds
            .mapNotNull { id ->
                candidates.find { it.id == id }
            }

        if (selected.isEmpty()) {
            return@withContext ""
        }

        selected.joinToString("\n\n") { it.content } + CONTEXT_DISCLAIMER
    }

    /**
     * Parser للـ JSON الخاص بالـ Curator.
     *
     * يقبل:
     * 1. JSON مباشر.
     * 2. JSON داخل ```json ... ```
     * 3. نصاً يحتوي JSON يمكن استخراج أول object منه.
     */
    private fun parseCuratorResponse(raw: String): CuratorResult {

        val cleaned = extractJsonObject(raw)

        val json = JSONObject(cleaned)

        val selectedIds = parseLongArray(
            json.optJSONArray("selectedIds")
        )

        val irrelevantIds = parseLongArray(
            json.optJSONArray("irrelevantIds")
        )

        val reasoning = json.optString(
            "reasoning",
            ""
        ).trim()

        return CuratorResult(
            selectedIds = selectedIds,
            reasoning = reasoning,
            irrelevantIds = irrelevantIds
        )
    }

    /**
     * استخراج JSON حتى لو وضعه النموذج داخل Markdown.
     */
    private fun extractJsonObject(raw: String): String {

        val text = raw.trim()

        if (text.startsWith("{") && text.endsWith("}")) {
            return text
        }

        val withoutMarkdown = text
            .replace("```json", "", ignoreCase = true)
            .replace("```", "")
            .trim()

        if (withoutMarkdown.startsWith("{") &&
            withoutMarkdown.endsWith("}")
        ) {
            return withoutMarkdown
        }

        val start = withoutMarkdown.indexOf('{')
        val end = withoutMarkdown.lastIndexOf('}')

        if (start >= 0 && end > start) {
            return withoutMarkdown.substring(start, end + 1)
        }

        throw IllegalArgumentException("No JSON object found")
    }

    private fun parseLongArray(array: JSONArray?): List<Long> {

        if (array == null) {
            return emptyList()
        }

        val result = mutableListOf<Long>()

        for (i in 0 until array.length()) {
            when (val value = array.opt(i)) {
                is Number -> result.add(value.toLong())
                is String -> value.toLongOrNull()?.let { result.add(it) }
            }
        }

        return result
    }

    /**
     * طبقة التحقق الإلزامية بعد الـ LLM.
     *
     * تمنع:
     * - IDs وهمية.
     * - التكرار.
     * - التداخل بين selected و irrelevant.
     *
     * وإذا أصبحت القائمة المختارة فارغة بسبب IDs غير صالحة،
     * نعود إلى أفضل نتائج RRF.
     *
     * ملاحظة:
     * selectedIds=[] من الـ LLM تعتبر اختياراً صالحاً.
     * لذلك لا نعمل fallback إلا إذا كانت القائمة الأصلية غير فارغة
     * وتم إرسال IDs غير صالحة أدت إلى حذف الاختيار.
     */
    private fun sanitize(
        result: CuratorResult,
        candidates: List<MemoryItem>,
        limit: Int = MAX_SELECTED
    ): CuratorResult {

        val validIds = candidates
            .map { it.id }
            .toSet()

        val originalSelected = result.selectedIds

        val cleanSelected = originalSelected
            .filter { it in validIds }
            .distinct()
            .take(limit)

        val cleanIrrelevant = result.irrelevantIds
            .filter { it in validIds && it !in cleanSelected }
            .distinct()

        /*
         * إذا لم يرسل الـ LLM أي ID أصلاً، فهذا اختيار صحيح:
         * لا توجد ذاكرة مناسبة.
         */
        if (originalSelected.isEmpty()) {
            return result.copy(
                selectedIds = emptyList(),
                irrelevantIds = cleanIrrelevant,
                isFallback = false,
                fallbackReason = null
            )
        }

        /*
         * إذا أرسل IDs لكن كلها وهمية/غير موجودة:
         * استخدم أفضل مرشحات RRF.
         */
        if (cleanSelected.isEmpty() && candidates.isNotEmpty()) {

            val fallbackIds = candidates
                .take(limit)
                .map { it.id }

            return CuratorResult(
                selectedIds = fallbackIds,
                reasoning = "Fallback: LLM returned invalid IDs",
                irrelevantIds = emptyList(),
                isFallback = true,
                fallbackReason =
                    "Invalid IDs returned by LLM: $originalSelected",
                rawResponse = result.rawResponse
            )
        }

        return result.copy(
            selectedIds = cleanSelected,
            irrelevantIds = cleanIrrelevant
        )
    }

    private fun fallbackResult(
        candidates: List<MemoryItem>,
        reason: String
    ): CuratorResult {

        val ids = candidates
            .take(MAX_SELECTED)
            .map { it.id }

        Log.w(TAG, "↩️ RRF fallback: $reason")

        return CuratorResult(
            selectedIds = ids,
            reasoning = "Fallback to RRF candidates",
            irrelevantIds = emptyList(),
            isFallback = true,
            fallbackReason = reason,
            rawResponse = null
        )
    }

    private suspend fun callCuratorProvider(prompt: String): String {
        return when (settings.memoryCuratorProvider) {

            "custom" -> {
                val baseUrl = settings.customUrl.trim()
                val model = settings.customModel.trim()

                if (baseUrl.isBlank() || model.isBlank()) {
                    throw Exception("Custom curator not configured")
                }

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

                callGeminiFlash(
                    prompt,
                    apiKey,
                    settings.memoryCuratorModel
                )
            }
        }
    }

    /**
     * تحسين طلب المستخدم بناءً على الهوية فقط.
     */
    suspend fun enhanceQuery(
        userQuery: String,
        mediatorIdentityText: String
    ): String = withContext(Dispatchers.IO) {

        if (mediatorIdentityText.isBlank()) {
            Log.d(TAG, "⚪ No identity text, returning original query")
            return@withContext userQuery
        }

        if (!settings.memoryCuratorEnabled) {
            Log.d(TAG, "⚪ Curator disabled, returning original query")
            return@withContext userQuery
        }

        try {
            Log.d(
                TAG,
                "🔄 Enhancing query (identity-only): ${userQuery.take(50)}..."
            )

            val prompt = MemoryCuratorPrompt.buildEnhancerPrompt(
                userQuery = userQuery,
                mediatorIdentityText = mediatorIdentityText
            )

            val enhanced = callConfiguredProvider(prompt).trim()

            val isValid =
                enhanced.isNotBlank() &&
                enhanced != userQuery &&
                !enhanced.equals("NONE", ignoreCase = true) &&
                enhanced.length >= 10

            if (isValid) {
                Log.d(
                    TAG,
                    "✅ Enhanced (no memory): ${enhanced.take(80)}..."
                )
                enhanced
            } else {
                Log.d(
                    TAG,
                    "⚠️ Enhancement invalid or identical, returning original"
                )
                userQuery
            }

        } catch (e: Exception) {
            Log.w(
                TAG,
                "❌ Query enhancement failed: ${e.message}",
                e
            )
            userQuery
        }
    }

    /**
     * تحسين صياغة السؤال فقط.
     */
    suspend fun refineQueryStyle(
        userQuery: String,
        style: QueryStyle
    ): String = withContext(Dispatchers.IO) {

        if (userQuery.isBlank()) {
            Log.d(TAG, "⚪ Empty query, returning as-is")
            return@withContext userQuery
        }

        if (!settings.memoryCuratorEnabled) {
            Log.d(TAG, "⚪ Curator disabled, returning original")
            return@withContext userQuery
        }

        try {
            Log.d(
                TAG,
                "🎨 Refining query style: ${style.displayName} - ${userQuery.take(50)}..."
            )

            val prompt = MemoryCuratorPrompt.buildStyleRefinementPrompt(
                userQuery = userQuery,
                style = style
            )

            val refined = callConfiguredProvider(prompt).trim()

            val isValid =
                refined.isNotBlank() &&
                refined.length >= 5 &&
                !refined.equals("NONE", ignoreCase = true)

            if (isValid) {
                Log.d(
                    TAG,
                    "✅ Refined (${style.displayName}): ${refined.take(80)}..."
                )
                refined
            } else {
                Log.d(
                    TAG,
                    "⚠️ Refinement invalid, returning original"
                )
                userQuery
            }

        } catch (e: Exception) {
            Log.w(
                TAG,
                "❌ Query style refinement failed: ${e.message}",
                e
            )
            userQuery
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Helper: الموفر المستخدم للتحسين وإعادة الصياغة
    // ─────────────────────────────────────────────────────────────

    private suspend fun callConfiguredProvider(
        prompt: String
    ): String {

        return when (settings.memoryCuratorProvider) {

            "ollama" -> {
                val model = settings.memoryCuratorOllamaModel.trim()

                if (model.isBlank()) {
                    throw Exception("Ollama model not configured")
                }

                Log.d(TAG, "🤖 Using Ollama (local): $model")

                callOllama(
                    prompt = prompt,
                    model = model
                )
            }

            "custom" -> {
                val baseUrl = settings.customUrl.trim()
                val model = settings.customModel.trim()

                if (baseUrl.isBlank() || model.isBlank()) {
                    throw Exception("Custom provider not configured")
                }

                Log.d(TAG, "🔧 Using Custom: $baseUrl")

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
                    "✨ Using Gemini: ${settings.memoryCuratorModel}"
                )

                callGeminiFlash(
                    prompt = prompt,
                    apiKey = apiKey,
                    model = settings.memoryCuratorModel
                )
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Ollama
    // ─────────────────────────────────────────────────────────────

    private fun callOllama(
        prompt: String,
        model: String
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
            put("stream", false)

            // إجبار Ollama على JSON
            put("format", "json")
        }

        val request = Request.Builder()
            .url("http://127.0.0.1:11434/v1/chat/completions")
            .addHeader("Content-Type", "application/json")
            .post(
                json.toString()
                    .toRequestBody("application/json".toMediaType())
            )
            .build()

        client.newCall(request).execute().use { response ->

            if (!response.isSuccessful) {
                throw Exception(
                    "Ollama failed: ${response.code} - " +
                        "${response.body?.string()}\n" +
                        "تأكد من تشغيل Ollama في Termux: ollama serve"
                )
            }

            val body = response.body?.string()
                ?: throw Exception("Empty response from Ollama")

            return try {

                JSONObject(body)
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")

            } catch (e: Exception) {

                throw Exception(
                    "خطأ في تحليل رد Ollama. " +
                        "تأكد من تشغيل النموذج: $model"
                )
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Gemini Flash
    // ─────────────────────────────────────────────────────────────

    private fun callGeminiFlash(
        prompt: String,
        apiKey: String,
        model: String
    ): String {

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
                                            put("text", prompt)
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
                JSONObject().apply {
                    put("temperature", 0.2)
                    put("maxOutputTokens", 2000)

                    // إجبار Gemini على JSON
                    put("responseMimeType", "application/json")
                }
            )
        }

        val request = Request.Builder()
            .url(
                "https://generativelanguage.googleapis.com/v1beta/models/" +
                    "$model:generateContent"
            )
            .addHeader("x-goog-api-key", apiKey)
            .addHeader("Content-Type", "application/json")
            .post(
                json.toString()
                    .toRequestBody("application/json".toMediaType())
            )
            .build()

        client.newCall(request).execute().use { response ->

            if (!response.isSuccessful) {
                throw Exception(
                    "Gemini Flash failed: " +
                        "${response.code} - ${response.body?.string()}"
                )
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

    // ─────────────────────────────────────────────────────────────
    // Custom OpenAI-compatible
    // ─────────────────────────────────────────────────────────────

    private fun callCustomProvider(
        prompt: String,
        baseUrl: String,
        apiKey: String,
        model: String
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
        }

        val requestBuilder = Request.Builder()
            .url(baseUrl)
            .addHeader("Content-Type", "application/json")
            .post(
                json.toString()
                    .toRequestBody("application/json".toMediaType())
            )

        if (apiKey.isNotBlank()) {
            requestBuilder.addHeader(
                "Authorization",
                "Bearer $apiKey"
            )
        }

        client.newCall(requestBuilder.build())
            .execute()
            .use { response ->

                if (!response.isSuccessful) {
                    throw Exception(
                        "Custom curator failed: " +
                            "${response.code} - ${response.body?.string()}"
                    )
                }

                val body = response.body?.string()
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
                            it.isNotBlank() && it != "null"
                        }

                if (content != null) {
                    return content
                }

                val reasoning =
                    message
                        .optString("reasoning", "")
                        .takeIf {
                            it.isNotBlank() && it != "null"
                        }

                if (reasoning != null) {
                    Log.d(
                        TAG,
                        "ℹ️ Custom curator: using 'reasoning' field"
                    )
                    return reasoning
                }

                throw Exception(
                    "Both 'content' and 'reasoning' fields are empty. " +
                        "Raw: ${body.take(300)}"
                )
            }
    }
}
