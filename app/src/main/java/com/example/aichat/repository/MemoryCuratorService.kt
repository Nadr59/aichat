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

    /**
     * الدالة الرئيسية للوسيط الذكي.
     */
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

            val prompt = MemoryCuratorPrompt.build(
                userQuery,
                candidates,
                mediatorIdentityText
            )

            val curatorProvider = settings.memoryCuratorProvider

            try {
                val result: String = when (curatorProvider) {

                    "custom" -> {
                        val baseUrl = settings.customUrl.trim()
                        val model = settings.customModel.trim()

                        if (baseUrl.isBlank() || model.isBlank()) {
                            Log.w(TAG, "⚠️ Custom curator: URL or model blank")
                            return@withContext fallbackBuilder.build(candidates)
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
                            Log.w(TAG, "⚠️ No Gemini key (blank)")
                            return@withContext fallbackBuilder.build(candidates)
                        }

                        callGeminiFlash(
                            prompt,
                            apiKey,
                            settings.memoryCuratorModel
                        )
                    }
                }

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
                    "⚠️ Curator ($curatorProvider) EXCEPTION: ${e.message}",
                    e
                )

                fallbackBuilder.build(candidates)
            }
        }

    /**
     * تحسين طلب المستخدم بناءً على الهوية المفعّلة والسياق من الذاكرة.
     *
     * هذه الدالة هي نقطة الاختبار الأساسية للاتصال بالوسيط.
     */
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

    /**
     * يحدد مزود الوسيط المستخدم في عملية التحسين.
     */
    private suspend fun callConfiguredProvider(
        prompt: String
    ): String {

        return when (settings.memoryCuratorProvider) {

            "custom" -> {

                val baseUrl = settings.customUrl.trim()
                val model = settings.customModel.trim()

                if (baseUrl.isBlank() || model.isBlank()) {
                    throw Exception("Custom provider not configured")
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
                    "🧠 ENHANCE PROVIDER | Gemini | model=${settings.memoryCuratorModel}"
                )

                callGeminiFlash(
                    prompt,
                    apiKey,
                    settings.memoryCuratorModel
                )
            }
        }
    }

    // ── Gemini Flash ──────────────────────────────────────────────────

    private fun callGeminiFlash(
        prompt: String,
        apiKey: String,
        model: String
    ): String {

        Log.d(
            TAG,
            "🧠 ENHANCE REQUEST SENT | Gemini | model=$model | promptLen=${prompt.length}"
        )

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
                    put("maxOutputTokens", 500)
                }
            )
        }

        val request = Request.Builder()
            .url(
                "https://generativelanguage.googleapis.com/" +
                    "v1beta/models/$model:generateContent"
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

                val errorBody = response.body?.string()

                Log.w(
                    TAG,
                    "🧠 ENHANCE GEMINI HTTP ERROR | " +
                        "code=${response.code}"
                )

                throw Exception(
                    "Gemini Flash failed: ${response.code} - $errorBody"
                )
            }

            val body = response.body?.string()
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
                "🧠 ENHANCE GEMINI RESPONSE PARSED | len=${result.length()}"
            )

            return result
        }
    }

    // ── Custom (OpenAI-compatible chat/completions) ──────────────────

    private fun callCustomProvider(
        prompt: String,
        baseUrl: String,
        apiKey: String,
        model: String
    ): String {

        Log.d(
            TAG,
            "🧠 ENHANCE REQUEST SENT | Custom | model=$model | promptLen=${prompt.length}"
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

        client.newCall(requestBuilder.build()).execute().use { response ->

            if (!response.isSuccessful) {

                val errorBody = response.body?.string()

                Log.w(
                    TAG,
                    "🧠 ENHANCE CUSTOM HTTP ERROR | " +
                        "code=${response.code}"
                )

                throw Exception(
                    "Custom curator failed: " +
                        "${response.code} - $errorBody"
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

                Log.d(
                    TAG,
                    "🧠 ENHANCE CUSTOM RESPONSE PARSED | len=${content.length}"
                )

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
