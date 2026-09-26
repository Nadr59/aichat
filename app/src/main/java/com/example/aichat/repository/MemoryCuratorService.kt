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
 * وسيط ذكي لاستخلاص السياق من الذاكرة قبل إرساله للمزود.
 *
 * يدعم مزوّدين مستقلّين تماماً عن settings.provider (مزوّد المحادثة الرئيسي):
 *  - "gemini" (افتراضي): يستخدم geminiKey + memoryCuratorModel عبر Gemini API مباشرة.
 *  - "custom": يعيد استخدام customUrl/customKey/customModel الموجودة أصلاً
 *    لمزوّد المحادثة "custom"، عبر بروتوكول OpenAI-compatible القياسي
 *    (chat/completions). مفيد تحديداً لتجاوز تحديد معدّل الطلبات (Rate
 *    Limiting) الخاص بمفتاح Gemini، بالانتقال لخادم آخر بلا أي تعديل كود.
 *
 * التبديل بين المزوّدين يتم بالكامل عبر settings.memoryCuratorProvider —
 * قيمة واحدة يغيّرها المستخدم من واجهة الإعدادات، بلا أي حاجة لإعادة
 * بناء التطبيق أو لمس هذا الملف مرة أخرى.
 *
 * التحكم الإضافي بيد المستخدم عبر settings.memoryCuratorEnabled:
 * - مفعّل  → يُستدعى دائماً عند وجود مرشحين، بصرف النظر عن عددهم
 * - معطّل → fallback فوري لـ MemoryContextBuilder (السلوك الأصلي)
 *
 * Fail-safe: أي فشل تقني (خطأ شبكة، لا مفتاح، لا رابط خادم) → fallback
 * أيضاً، بلا انهيار، بصرف النظر عن المزوّد المختار.
 *
 * ⚠️ DEBUG-TEMP نشط حالياً داخل مسار "custom" فقط، لتشخيص مشكلة ظهور
 * محتوى "null" من خادم مخصص. يجب التراجع عنه بعد انتهاء التشخيص —
 * التعليمات في نهاية الملف.
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
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun curate(userQuery: String, candidates: List<MemoryItem>): String =
        withContext(Dispatchers.IO) {

            if (candidates.isEmpty()) return@withContext ""

            if (!settings.memoryCuratorEnabled) {
                Log.d(TAG, "⚪ DEBUG-TEMP: Curator DISABLED by setting")
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
                            Log.w(
                                TAG,
                                "⚠️ DEBUG-TEMP: Custom curator selected but " +
                                "URL or model is blank (url=$baseUrl, model=$model)"
                            )
                            return@withContext fallbackBuilder.build(candidates)
                        }

                        Log.d(TAG, "🔄 DEBUG-TEMP: Calling custom curator ($baseUrl, $model)...")
                        callCustomProvider(
                            prompt  = prompt,
                            baseUrl = baseUrl,
                            apiKey  = settings.customKey,
                            model   = model
                        )
                    }

                    else -> { // "gemini" — السلوك الافتراضي الأصلي
                        val apiKey = settings.geminiKey
                        if (apiKey.isBlank()) {
                            Log.w(TAG, "⚠️ DEBUG-TEMP: No Gemini key (blank)")
                            return@withContext fallbackBuilder.build(candidates)
                        }

                        Log.d(TAG, "🔄 DEBUG-TEMP: Calling Gemini Flash now...")
                        callGeminiFlash(prompt, apiKey, settings.memoryCuratorModel)
                    }
                }

                Log.d(TAG, "✅ DEBUG-TEMP: Curator ($curatorProvider) succeeded: ${result.take(100)}")

                if (result.isBlank() || result.trim().equals("NONE", ignoreCase = true)) {
                    Log.d(TAG, "⚪ Curator found nothing relevant")
                    ""
                } else {
                    Log.d(TAG, "✅ Curated: ${result.take(80)}...")
                    result.trim()
                }
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ DEBUG-TEMP: Curator ($curatorProvider) EXCEPTION: ${e.message}", e)

                // ⚠️ DEBUG-TEMP: نعرض رسالة الخطأ مباشرة بدل الإخفاء الصامت
                // عبر fallback، لنتمكن من رؤية الاستجابة الخام في الواجهة
                // (Logcat غير متاح حالياً). احذف هذا الشرط وأعد السطر الأصلي
                // (fallbackBuilder.build(candidates)) بعد انتهاء التشخيص.
                if (curatorProvider == "custom") {
                    return@withContext "DEBUG-TEMP ERROR: ${e.message}"
                }

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
    // ⚠️ ملاحظة تصميمية مهمة: هذه الدالة تنفيذ مستقل وخفيف لبروتوكول
    // OpenAI القياسي، وليست إعادة استخدام لـ OpenAICompatibleProvider
    // الموجود في المشروع أصلاً (المستخدَم في مزوّد المحادثة الرئيسي).
    // السبب: OpenAICompatibleProvider مصمَّم لسياق محادثة كامل (history +
    // systemPrompt + رسالة مستخدم)، بينما الوسيط يحتاج فقط إرسال prompt
    // واحد مكتفٍ بذاته دون سياق محادثة. تنفيذ مستقل هنا أبسط وأكثر أماناً
    // من محاولة "حشر" استخدام مختلف داخل دالة مصمَّمة لغرض آخر.

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
            put("max_tokens", 500)
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

            // ⚠️ DEBUG-TEMP: تسجيل الاستجابة الخام كاملة قبل أي محاولة تحليل
            Log.d(TAG, "🔍 DEBUG-TEMP RAW RESPONSE: $body")

            val rawContent = JSONObject(body)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")

            // ⚠️ DEBUG-TEMP: كشف صريح لحالة "content": null القادمة من
            // بعض الخوادم، والتي لا ترميها org.json كاستثناء بل تُعيدها
            // كسلسلة نصية حرفية "null". احذف هذا الشرط بعد انتهاء التشخيص.
            if (rawContent == "null" || rawContent.isBlank()) {
                throw Exception(
                    "DEBUG-TEMP: Empty content field. Raw body: ${body.take(300)}"
                )
            }

            return rawContent
        }
    }
}
