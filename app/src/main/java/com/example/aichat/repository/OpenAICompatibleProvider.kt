package com.example.aichat.repository

import com.example.aichat.data.model.Message
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

class OpenAICompatibleProvider(
    private val client: OkHttpClient
) {

    fun send(
        baseUrl: String,
        apiKey: String,
        model: String,
        history: List<Message>,
        userMessage: String,
        imageBase64: String?,
        providerName: String,
        maxHistory: Int,
        maxTokens: Int,
        memoryContext: String = "",
        extraHeaders: Map<String, String> = emptyMap(),
        forceVision: Boolean = false,
        onCustomRequest: (() -> Unit)? = null
    ): String {

        android.util.Log.d("OpenAIProvider", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        android.util.Log.d("OpenAIProvider", "📤 Sending to: $providerName")
        android.util.Log.d("OpenAIProvider", "🤖 Model: $model")
        android.util.Log.d("OpenAIProvider", "💬 User message: ${userMessage.take(50)}...")
        android.util.Log.d("OpenAIProvider", "🧠 Memory context length: ${memoryContext.length}")

        if (memoryContext.isNotBlank()) {
            android.util.Log.d("OpenAIProvider", "✅ Memory context PRESENT")
            android.util.Log.d("OpenAIProvider", "Preview: ${memoryContext.take(100)}...")
        } else {
            android.util.Log.w("OpenAIProvider", "⚠️ Memory context is EMPTY")
        }

        val cleanModel = model.trim()

        if (apiKey.isBlank()) throw IOException("$providerName: المفتاح فارغ")
        if (cleanModel.isBlank()) throw IOException("$providerName: النموذج فارغ")
        if (baseUrl.isBlank()) throw IOException("$providerName: الرابط فارغ")

        val hasImage = imageBase64 != null && (forceVision || supportsVision(cleanModel))

        // ============================================================
        // تقدير الـ tokens المتاحة للتاريخ
        // مُصحَّح: العربية تستهلك tokens أكثر من الإنجليزية
        // ============================================================

        // نحجز 30% من maxTokens للرد، والباقي للمحادثة
        val tokensForHistory = (maxTokens * 0.4).toInt()

        val limitedHistory = limitHistoryByTokens(
            history = history.takeLast(maxHistory),
            maxTokens = tokensForHistory
        )

        android.util.Log.d(
            "OpenAIProvider",
            "📜 History: ${history.size} messages → limited to ${limitedHistory.size}"
        )

        val messages = JSONArray()

        // ============================================================
        // مُصحَّح: Vision + system message
        // Groq Vision لا يقبل system message منفصل مع الصورة
        // الحل: دمج memoryContext في رسالة المستخدم عند وجود صورة
        // ============================================================

        if (hasImage) {
            // ✅ Vision: بدون system message منفصل
            // نضع التاريخ النصي فقط (بدون صور في التاريخ)
            limitedHistory.forEach { msg ->
                if (msg.imageBase64 == null) {
                    messages.put(
                        JSONObject().apply {
                            put("role", msg.role)
                            put("content", msg.content)
                        }
                    )
                }
            }

            // ✅ دمج memoryContext في رسالة المستخدم
            val userTextWithContext = buildString {
                if (memoryContext.isNotBlank()) {
                    append("السياق المرجعي:\n")
                    append(memoryContext)
                    append("\n\n")
                }
                append(userMessage)
            }

            android.util.Log.d("OpenAIProvider", "🖼️ Vision mode: merging context into user message")
            android.util.Log.d("OpenAIProvider", "📋 Combined message length: ${userTextWithContext.length}")

            messages.put(
                JSONObject().apply {
                    put("role", "user")
                    put("content", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "image_url")
                            put("image_url", JSONObject().apply {
                                put("url", "data:image/jpeg;base64,$imageBase64")
                                put("detail", "low")
                            })
                        })
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", userTextWithContext)
                        })
                    })
                }
            )

        } else {
            // ✅ بدون صورة: system message عادي
            val systemContent = buildString {
                append("You are a helpful AI assistant.")
                if (memoryContext.isNotBlank()) {
                    append("\n\n")
                    append(memoryContext)
                }
            }

            android.util.Log.d("OpenAIProvider", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            android.util.Log.d("OpenAIProvider", "📋 System message length: ${systemContent.length}")
            android.util.Log.d("OpenAIProvider", systemContent.take(300))
            android.util.Log.d("OpenAIProvider", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

            messages.put(
                JSONObject().apply {
                    put("role", "system")
                    put("content", systemContent)
                }
            )

            // ✅ التاريخ المحدود بالـ tokens
            limitedHistory.forEach { msg ->
                messages.put(
                    JSONObject().apply {
                        put("role", msg.role)
                        put("content", msg.content)
                    }
                )
            }

            // ✅ رسالة المستخدم
            messages.put(
                JSONObject().apply {
                    put("role", "user")
                    put("content", userMessage)
                }
            )
        }

        // ============================================================
        // بناء الطلب
        // ============================================================

        val requestJson = JSONObject().apply {
            put("model", cleanModel)
            put("messages", messages)
            put("temperature", 0.7)
            put("max_tokens", maxTokens)
        }

        android.util.Log.d("OpenAIProvider", "📦 Total messages in request: ${messages.length()}")

        val requestBuilder = Request.Builder()
            .url(baseUrl)
            .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")

        extraHeaders.forEach { (key, value) ->
            requestBuilder.addHeader(key, value)
        }

        client.newCall(requestBuilder.build()).execute().use { response ->

            val body = response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                val msg = runCatching {
                    val j = JSONObject(body)
                    j.optJSONObject("error")?.optString("message")
                        ?: j.optString("message", body)
                }.getOrDefault(body)

                android.util.Log.e("OpenAIProvider", "❌ HTTP ${response.code}: $msg")

                throw IOException(
                    when (response.code) {
                        400 -> "❌ $providerName: طلب غير صالح (400) - $msg"
                        401 -> "❌ $providerName: المفتاح غير صحيح"
                        403 -> "❌ $providerName: لا توجد صلاحية"
                        422 -> "❌ $providerName: صيغة خاطئة: $msg"
                        429 -> "⚠️ $providerName: تجاوزت حد الطلبات"
                        500 -> "❌ $providerName: خطأ في الخادم"
                        else -> "$providerName ${response.code}: $msg"
                    }
                )
            }

            val text = JSONObject(body)
                .optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content", "")
                ?.trim()

            val result = text?.takeIf { it.isNotBlank() }
                ?: throw IOException("$providerName: الرد فارغ")

            if (providerName.equals("Custom", ignoreCase = true)) {
                onCustomRequest?.invoke()
            }

            android.util.Log.d("OpenAIProvider", "✅ Response received: ${result.take(100)}...")

            return result
        }
    }

    // ============================================================
    // تقدير tokens ومحدودية التاريخ
    // مُصحَّح: العربية تستهلك 2-3x tokens مقارنة بالإنجليزية
    // ============================================================

    private fun estimateTokens(text: String): Int {
        if (text.isBlank()) return 0

        val arabicChars = text.count { it in '\u0600'..'\u06FF' }
        val arabicRatio = arabicChars.toDouble() / text.length

        return if (arabicRatio > 0.3) {
            // نص عربي: كل حرف ≈ 0.6 token (العربية تُقسَّم إلى subwords أكثر)
            (text.length * 0.6).toInt().coerceAtLeast(1)
        } else {
            // نص إنجليزي: كل 4 أحرف ≈ 1 token
            (text.length / 4).coerceAtLeast(1)
        }
    }

    private fun limitHistoryByTokens(
        history: List<Message>,
        maxTokens: Int
    ): List<Message> {
        if (history.isEmpty()) return emptyList()

        var tokenCount = 0
        val result = mutableListOf<Message>()

        // من الأحدث للأقدم للحفاظ على السياق الأخير
        for (message in history.reversed()) {
            val msgTokens = estimateTokens(message.content)

            if (tokenCount + msgTokens > maxTokens) {
                android.util.Log.d(
                    "OpenAIProvider",
                    "✂️ History cut at ${result.size} messages (${tokenCount} tokens)"
                )
                break
            }

            result.add(0, message)
            tokenCount += msgTokens
        }

        return result
    }

    // ============================================================
    // التحقق من دعم Vision
    // ============================================================

    private fun supportsVision(model: String): Boolean {
        val m = model.lowercase()
        return listOf(
            "gpt-4o", "gpt-4-turbo", "gpt-4-vision", "gpt-4.1", "gpt-5",
            "gemini", "claude-3", "claude-3.5", "claude-3.7",
            "pixtral", "llava", "vision", "qwen-vl", "qwen2-vl", "qwen2.5-vl",
            "minicpm-v", "internvl", "phi-3-vision", "phi-4-vision",
            "moondream", "bakllava", "cogvlm", "yi-vl"
        ).any { m.contains(it) }
    }
}
