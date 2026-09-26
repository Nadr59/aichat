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

        /**
         * تحذير مُلصَق حتمياً بعد المحتوى المُستخلَص من الذاكرة تحديداً
         * (لا بعد نقاط الهوية الأسلوبية)، بهدف تقريب مسافة الانتباه بين
         * التحذير والحقائق نفسها.
         *
         * ⚠️ تجربة غير مؤكدة الفعالية بعد: محاولات سابقة لمنع النموذج
         * المُجيب من "إثراء" السياق بمعرفته العامة عبر تعليمات في
         * SystemPrompt فشلت بدليل تجريبي مباشر (نموذج pixtral-12b-2409
         * استمر بمزج تفاصيل عامة غير مؤكدة حتى مع تعطيل وثيقة الدقة
         * بالكامل). هذا تدخّل مختلف (قرب فيزيائي من المحتوى بدل فقرة
         * منفصلة)، ويحتاج اختباراً فعلياً قبل اعتباره حلاً ناجحاً.
         *
         * ملاحظة تصميمية (جديد): تُلحَق هذه العبارة فقط إذا كانت هناك
         * ذاكرة فعلية مسترجَعة (candidates.isNotEmpty())، لأنها تتحدث
         * صراحة عن "حقائق مؤكدة بخصوص الموضوع". إن كان مخرج الوسيط
         * نقاط هوية أسلوبية فقط بلا ذاكرة، لا معنى لإلحاق تحذير عن
         * حقائق غير موجودة أصلاً.
         */
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
     *
     * @param mediatorIdentityText نص هوية/أسلوب اختياري (من الإعدادات افتراضياً).
     *        فارغ = سلوك الوسيط الأصلي بالضبط (تنقية ذاكرة فقط، بلا كسر توافق).
     *        غير فارغ = يتحول الوسيط أيضاً لمحسّن طلبات ضمن نفس الاستدعاء الواحد،
     *        بلا أي استدعاء شبكة إضافي عن السابق.
     *
     * ملاحظة توافق: هذا المعامل له قيمة افتراضية، لذا أي استدعاء قديم
     * لـ curate(userQuery, candidates) بدون تعديل يستمر بالعمل بلا كسر.
     */
    suspend fun curate(
        userQuery: String,
        candidates: List<MemoryItem>,
        mediatorIdentityText: String = settings.mediatorIdentityText
    ): String =
        withContext(Dispatchers.IO) {

            // لا داعي لاستدعاء LLM إطلاقاً إن لم توجد ذاكرة ولا نص هوية
            if (candidates.isEmpty() && mediatorIdentityText.isBlank()) {
                return@withContext ""
            }

            if (!settings.memoryCuratorEnabled) {
                Log.d(TAG, "⚪ Curator DISABLED by setting")
                // ملاحظة: عند تعطيل الوسيط، تُتجاهَل نقاط الهوية تماماً
                // (تحتاج LLM بالضرورة)، ويُكتفى بالمسار الاحتياطي الخام.
                return@withContext fallbackBuilder.build(candidates)
            }

            val prompt = MemoryCuratorPrompt.build(userQuery, candidates, mediatorIdentityText)
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
                    val trimmedResult = result.trim()
                    Log.d(TAG, "✅ Curated: ${trimmedResult.take(80)}...")

                    // التحذير يُلحَق فقط عند وجود ذاكرة فعلية مسترجَعة
                    if (candidates.isNotEmpty()) {
                        trimmedResult + CONTEXT_DISCLAIMER
                    } else {
                        trimmedResult
                    }
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
            put("max_tokens", 2000)
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

            val content = message.optString("content", "")
                .takeIf { it.isNotBlank() && it != "null" }

            if (content != null) return content

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
