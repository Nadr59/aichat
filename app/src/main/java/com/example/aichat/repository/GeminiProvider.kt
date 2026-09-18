package com.example.aichat.repository

import com.example.aichat.data.local.AiSettings
import com.example.aichat.data.model.Message
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

class GeminiProvider(
    private val settings: AiSettings,
    private val client: OkHttpClient
) {

    fun send(
        history:       List<Message>,
        userMessage:   String,
        imageBase64:   String?,
        memoryContext: String = "",
        systemPrompt:  String = ""   // ✅ جديد
    ): String {

        val apiKey = settings.geminiKey.trim()
        if (apiKey.isBlank()) throw IOException("Gemini: المفتاح فارغ")

        val model = settings.geminiModel.trim().ifBlank { "gemini-2.0-flash" }

        val endpoint =
            "https://generativelanguage.googleapis.com/v1beta/models/" +
            "$model:generateContent?key=$apiKey"

        // ── تاريخ المحادثة ────────────────────────────────────────────────
        val contents = JSONArray()

        history.takeLast(10).forEach { msg ->
            val role  = if (msg.role == "user") "user" else "model"
            val parts = JSONArray().apply {
                put(JSONObject().apply { put("text", msg.content) })
            }
            contents.put(JSONObject().apply {
                put("role", role)
                put("parts", parts)
            })
        }

        // ── رسالة المستخدم الحالية ────────────────────────────────────────
        val newParts = JSONArray()

        imageBase64?.let {
            newParts.put(JSONObject().apply {
                put("inline_data", JSONObject().apply {
                    put("mime_type", "image/jpeg")
                    put("data", it)
                })
            })
        }

        newParts.put(JSONObject().apply { put("text", userMessage) })

        contents.put(JSONObject().apply {
            put("role", "user")
            put("parts", newParts)
        })

        // ── بناء الطلب ───────────────────────────────────────────────────
        val requestJson = JSONObject().apply {

            put("contents", contents)

            // ✅ System Prompt — Gemini يستخدم systemInstruction
            // الأولوية: systemPrompt (يشمل الوثيقة + الذاكرة)
            // fallback: memoryContext فقط إذا لم يُمرَّر systemPrompt
            val instruction = when {
                systemPrompt.isNotBlank()  -> systemPrompt
                memoryContext.isNotBlank() -> memoryContext
                else                       -> null
            }

            instruction?.let {
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", it) })
                    })
                })
            }

            put("generationConfig", JSONObject().apply {
                put("temperature", 0.7)
                put("maxOutputTokens", 8192)
                put("topP", 0.95)
            })

            put("safetySettings", JSONArray().apply {
                listOf(
                    "HARM_CATEGORY_HARASSMENT",
                    "HARM_CATEGORY_HATE_SPEECH",
                    "HARM_CATEGORY_SEXUALLY_EXPLICIT",
                    "HARM_CATEGORY_DANGEROUS_CONTENT"
                ).forEach { category ->
                    put(JSONObject().apply {
                        put("category", category)
                        put("threshold", "BLOCK_ONLY_HIGH")
                    })
                }
            })
        }

        // ── الطلب ────────────────────────────────────────────────────────
        val request = Request.Builder()
            .url(endpoint)
            .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
            .addHeader("Content-Type", "application/json")
            .build()

        client.newCall(request).execute().use { response ->

            val body = response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                val msg = runCatching {
                    JSONObject(body).optJSONObject("error")?.optString("message") ?: body
                }.getOrDefault(body)

                throw IOException(when (response.code) {
                    429  -> "⚠️ Gemini: تجاوزت حد الطلبات - انتظر دقيقة"
                    401  -> "❌ Gemini: المفتاح غير صحيح"
                    else -> "Gemini ${response.code}: $msg"
                })
            }

            val candidates = JSONObject(body).optJSONArray("candidates")

            if (candidates == null || candidates.length() == 0) {
                val reason = JSONObject(body)
                    .optJSONObject("promptFeedback")
                    ?.optString("blockReason", "غير معروف")
                throw IOException("Gemini: محجوب ($reason)")
            }

            val candidate    = candidates.optJSONObject(0)
            val finishReason = candidate?.optString("finishReason", "")
            val text         = candidate
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
                ?.optJSONObject(0)
                ?.optString("text", "")
                ?.trim()

            if (!text.isNullOrBlank()) return text

            throw IOException(when (finishReason) {
                "SAFETY"     -> "Gemini: محجوب لأسباب أمان"
                "MAX_TOKENS" -> "Gemini: الرد طويل جداً"
                else         -> "Gemini: رد فارغ ($finishReason)"
            })
        }
    }
}
