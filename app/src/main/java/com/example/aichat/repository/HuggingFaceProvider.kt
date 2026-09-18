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

class HuggingFaceProvider(
    private val settings: AiSettings,
    private val client: OkHttpClient
) {

    fun send(
        history:       List<Message>,
        userMessage:   String,
        memoryContext: String = "",
        systemPrompt:  String = ""   // ✅ جديد
    ): String {

        val apiKey = settings.huggingfaceKey.trim()
        val model  = settings.huggingfaceModel.trim()

        if (apiKey.isBlank()) throw IOException(
            "Hugging Face: أدخل API Token\nمن: huggingface.co/settings/tokens"
        )
        if (model.isBlank()) throw IOException("Hugging Face: اختر نموذجاً")

        // ── بناء messages ─────────────────────────────────────────────────
        val messages = JSONArray()

        // ✅ System Prompt — الأولوية للوثيقة الكاملة
        val sysContent = when {
            systemPrompt.isNotBlank()  -> systemPrompt
            memoryContext.isNotBlank() -> "You are a helpful AI assistant.\n\n$memoryContext"
            else                       -> "You are a helpful AI assistant."
        }

        messages.put(JSONObject().apply {
            put("role", "system")
            put("content", sysContent)
        })

        history.takeLast(6).forEach { msg ->
            messages.put(JSONObject().apply {
                put("role", msg.role)
                put("content", msg.content)
            })
        }

        messages.put(JSONObject().apply {
            put("role", "user")
            put("content", userMessage)
        })

        // ── بناء الطلب ───────────────────────────────────────────────────
        val requestJson = JSONObject().apply {
            put("model",       model)
            put("messages",    messages)
            put("max_tokens",  1024)
            put("temperature", 0.7)
            put("stream",      false)
        }

        val request = Request.Builder()
            .url("https://router.huggingface.co/v1/chat/completions")
            .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .build()

        client.newCall(request).execute().use { response ->

            val body = response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                val msg = runCatching {
                    val json = JSONObject(body)
                    json.optJSONObject("error")?.optString("message")
                        ?: json.optString("error", body).ifBlank {
                            json.optString("message", body)
                        }
                }.getOrDefault(body)

                throw IOException(when (response.code) {
                    401  -> "❌ HuggingFace: التوكن غير صحيح"
                    403  -> "❌ HuggingFace: لا توجد صلاحية\nتأكد من قبول شروط النموذج على huggingface.co/$model"
                    404  -> "❌ HuggingFace: النموذج غير موجود\n$model"
                    422  -> "❌ HuggingFace: النموذج لا يدعم المحادثة\nجرب نموذجاً آخر"
                    429  -> "⚠️ HuggingFace: تجاوزت الحد المجاني"
                    503  -> "⏳ HuggingFace: الخادم مشغول - حاول لاحقاً"
                    else -> "HuggingFace ${response.code}: $msg"
                })
            }

            val text = JSONObject(body)
                .optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content", "")
                ?.trim()

            return text?.takeIf { it.isNotBlank() }
                ?: throw IOException("HuggingFace: الرد فارغ\n${body.take(200)}")
        }
    }
}
