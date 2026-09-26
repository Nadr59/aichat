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
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun curate(userQuery: String, candidates: List<MemoryItem>): String =
        withContext(Dispatchers.IO) {

            if (candidates.isEmpty()) return@withContext ""

            if (!settings.memoryCuratorEnabled) {
                Log.d(TAG, "⚪ Curator DISABLED by setting")
                return@withContext fallbackBuilder.build(candidates)
            }

            val prompt = MemoryCuratorPrompt.build(userQuery, candidates)
            val curatorProvider = settings.memoryCuratorProvider

            try {
                val result: String = when (curatorProvider) {

                    "custom" -> {
                        val baseUrl = settings.customUrl.trim()
                        val model   = settings.customModel.trim()

                        if (baseUrl.isBlank() || model.isBlank()) {
                            Log.w(TAG, "⚠️ Custom curator: URL or model blank")
                            return@withContext fallbackBuilder.build(candidates)
                        }

                        callCustomProvider(
                            prompt  = prompt,
                            baseUrl = baseUrl,
                            apiKey  = settings.customKey,
                            model   = model
                        )
                    }

                    else -> {
                        val apiKey = settings.geminiKey
                        if (apiKey.isBlank()) {
                            Log.w(TAG, "⚠️ No Gemini key (blank)")
                            return@withContext fallbackBuilder.build(candidates)
                        }
                        callGeminiFlash(prompt, apiKey, settings.memoryCuratorModel)
                    }
                }

                if (result.isBlank() || result.trim().equals("NONE", ignoreCase = true)) {
                    Log.d(TAG, "⚪ Curator found nothing relevant")
                    ""
                } else {
                    Log.d(TAG, "✅ Curated: ${result.take(80)}...")
                    result.trim()
                }
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Curator ($curatorProvider) EXCEPTION: ${e.message}", e)
                fallbackBuilder.build(candidates)
            }
        }

    // ── Gemini Flash ──────────────────────────────────────────────────

    private fun callGeminiFlash(prompt: String, apiKey: String, model: String): String {
        val json = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", prompt) })
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.2)
                put("maxOutputTokens", 500)
            })
        }

        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .addHeader("x-goog-api-key", apiKey)
            .addHeader("Content-Type", "application/json")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("Gemini Flash failed: ${response.code} - ${response.body?.string()}")
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

    // ── Custom (OpenAI-compatible chat/completions) ──────────────────
    //
    // ⚠️ ملاحظة مهمة: بعض خوادم OpenAI-compatible (مثل Cloudflare Workers
    // AI مع نماذج reasoning كـ glm-4.7-flash) تُعيد الاستجابة الفعلية في
    // حقل "reasoning" غير القياسي بدل "content"، عندما لا يكتمل النموذج
    // من "التفكير" ضمن حد max_tokens. لذلك:
    // 1. رفعنا max_tokens بشكل كبير لإعطاء مساحة كافية لإكمال reasoning
    //    والوصول فعلياً لمرحلة content.
    // 2. أضفنا قراءة احتياطية لحقل reasoning إن كان content فارغاً/null،
    //    حتى لا نفقد استجابة مفيدة فعلياً وصلت لكنها في حقل غير متوقَّع.

    private fun callCustomProvider(
        prompt:  String,
        baseUrl: String,
        apiKey:  String,
        model:   String
    ): String {
        val json = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            })
            put("temperature", 0.2)
            put("max_tokens", 2000) // ← رُفع من 500: مساحة كافية لإكمال reasoning + content
        }

        val requestBuilder = Request.Builder()
            .url(baseUrl)
            .addHeader("Content-Type", "application/json")
            .post(json.toString().toRequestBody("application/json".toMediaType()))

        if (apiKey.isNotBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer $apiKey")
        }

        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception(
                    "Custom curator failed: ${response.code} - ${response.body?.string()}"
                )
            }

            val body = response.body?.string()
                ?: throw Exception("Empty response from custom curator")

            val message = JSONObject(body)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")

            // content أولاً (المسار القياسي)
            val content = message.optString("content", "")
                .takeIf { it.isNotBlank() && it != "null" }

            if (content != null) return content

            // fallback: بعض النماذج (reasoning models) تضع الناتج الفعلي
            // هنا بدل content، تحديداً عند نماذج مثل glm عبر Cloudflare
            val reasoning = message.optString("reasoning", "")
                .takeIf { it.isNotBlank() && it != "null" }

            if (reasoning != null) {
                Log.d(TAG, "ℹ️ Custom curator: using 'reasoning' field (content was empty)")
                return reasoning
            }

            throw Exception("Both 'content' and 'reasoning' fields are empty. Raw: ${body.take(300)}")
        }
    }
}
