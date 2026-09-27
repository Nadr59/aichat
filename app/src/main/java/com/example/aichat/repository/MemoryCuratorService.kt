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

    /**
     * ✅ تحسين طلب المستخدم بناءً على الهوية المفعّلة فقط (بدون ذاكرة).
     * 
     * تُستخدَم عندما يطلب المستخدم صراحة "تحسين السؤال" من الواجهة
     * (بالضغط على زر التحسين)، وليست عملية تلقائية.
     * 
     * @param userQuery السؤال الأصلي كما كتبه المستخدم
     * @param mediatorIdentityText وصف الهوية/الأسلوب من المطلوب من الإعدادات
     * 
     * @return السؤال المُحسّن (مُعاد صياغته بالكامل)، أو السؤال الأصلي
     *         دون تغيير إن فشل التحسين أو لم يكن ممكناً منطقياً.
     * 
     * ⚠️ التغيير: حذف معامل memoryCandidates - التحسين يعتمد فقط على السؤال + الهوية
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
            Log.d(TAG, "🔄 Enhancing query (identity-only): ${userQuery.take(50)}...")

            // ✅ تمرير السؤال + الهوية فقط (بدون memoryCandidates)
            val prompt = MemoryCuratorPrompt.buildEnhancerPrompt(
                userQuery            = userQuery,
                mediatorIdentityText = mediatorIdentityText
            )

            val enhanced = callConfiguredProvider(prompt).trim()

            // التحقق من جودة الناتج
            val isValid = enhanced.isNotBlank() &&
                          enhanced != userQuery &&
                          !enhanced.equals("NONE", ignoreCase = true) &&
                          enhanced.length > userQuery.length / 2

            if (isValid) {
                Log.d(TAG, "✅ Enhanced (no memory): ${enhanced.take(80)}...")
                enhanced
            } else {
                Log.d(TAG, "⚠️ Enhancement invalid or identical, returning original")
                userQuery
            }

        } catch (e: Exception) {
            Log.w(TAG, "❌ Query enhancement failed: ${e.message}", e)
            userQuery
        }
    }
    /**
 * 🆕 تحسين صياغة السؤال فقط (بدون إضافة محتوى أو توسيع)
 * 
 * الفرق عن enhanceQuery():
 * - هذه: نفس المعنى، لغة أفضل (رسمي/عامي/أكاديمي/إبداعي)
 * - تلك: توسيع السؤال وإضافة تفاصيل بناءً على الهوية
 * 
 * @param userQuery السؤال الأصلي
 * @param style النمط المطلوب (FORMAL, CASUAL, ACADEMIC, CREATIVE)
 * 
 * @return السؤال بنفس المحتوى لكن بصياغة محسّنة
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
        Log.d(TAG, "🎨 Refining query style: ${style.displayName} - ${userQuery.take(50)}...")

        val prompt = MemoryCuratorPrompt.buildStyleRefinementPrompt(
            userQuery = userQuery,
            style = style
        )

        val refined = callConfiguredProvider(prompt).trim()

        // التحقق من الجودة (أقل صرامة من enhanceQuery)
        val isValid = refined.isNotBlank() &&
                      refined.length >= 5 &&
                      !refined.equals("NONE", ignoreCase = true)

        if (isValid) {
            Log.d(TAG, "✅ Refined (${style.displayName}): ${refined.take(80)}...")
            refined
        } else {
            Log.d(TAG, "⚠️ Refinement invalid, returning original")
            userQuery
        }

    } catch (e: Exception) {
        Log.w(TAG, "❌ Query style refinement failed: ${e.message}", e)
        userQuery
    }
}

    // ── Helper: استدعاء الموفر المُختار ──────────────────────────

    private suspend fun callConfiguredProvider(prompt: String): String {
        return when (settings.memoryCuratorProvider) {
            "custom" -> {
                val baseUrl = settings.customUrl.trim()
                val model   = settings.customModel.trim()
                if (baseUrl.isBlank() || model.isBlank()) {
                    throw Exception("Custom provider not configured")
                }
                callCustomProvider(prompt, baseUrl, settings.customKey, model)
            }
            else -> {
                val apiKey = settings.geminiKey
                if (apiKey.isBlank()) {
                    throw Exception("Gemini API key is blank")
                }
                callGeminiFlash(prompt, apiKey, settings.memoryCuratorModel)
            }
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
