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

/**
 * وسيط ذكي سحابي لاستخلاص السياق من الذاكرة قبل إرساله للمزود.
 * مستقل تماماً عن settings.provider — يستخدم geminiKey الموجود
 * أصلاً في EmbeddingService (نفس المفتاح، بلا احتكاك إضافي).
 *
 * التحكم بالكامل بيد المستخدم عبر settings.memoryCuratorEnabled:
 * - مفعّل  → يُستدعى دائماً عند وجود مرشحين، بصرف النظر عن عددهم
 * - معطّل → fallback فوري لـ MemoryContextBuilder (السلوك الأصلي)
 *
 * Fail-safe: أي فشل تقني (خطأ شبكة، لا مفتاح) → fallback أيضاً، بلا انهيار.
 */
class MemoryCuratorService(
    private val settings: AiSettings,
    private val fallbackBuilder: MemoryContextBuilder
) {

    companion object {
        private const val TAG = "MemoryCurator"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun curate(userQuery: String, candidates: List<MemoryItem>): String =
        withContext(Dispatchers.IO) {

            if (candidates.isEmpty()) return@withContext ""

            if (!settings.memoryCuratorEnabled) {
                Log.d(TAG, "⚪ DEBUG-TEMP: Curator DISABLED by setting")
                return@withContext fallbackBuilder.build(candidates)
            }

            val apiKey = settings.geminiKey
            if (apiKey.isBlank()) {
                Log.w(TAG, "⚠️ DEBUG-TEMP: No Gemini key (blank)")
                return@withContext fallbackBuilder.build(candidates)
            }

            try {
                val prompt = MemoryCuratorPrompt.build(userQuery, candidates)
                Log.d(TAG, "🔄 DEBUG-TEMP: Calling Gemini Flash now...")

                val result = callGeminiFlash(prompt, apiKey, settings.memoryCuratorModel)

                Log.d(TAG, "✅ DEBUG-TEMP: Gemini Flash succeeded: ${result.take(100)}")

                if (result.isBlank() || result.trim().equals("NONE", ignoreCase = true)) {
                    Log.d(TAG, "⚪ Curator found nothing relevant")
                    ""
                } else {
                    Log.d(TAG, "✅ Curated: ${result.take(80)}...")
                    result.trim()
                }
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ DEBUG-TEMP: Curator EXCEPTION: ${e.message}", e)
                fallbackBuilder.build(candidates)
            }
        }

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
}
