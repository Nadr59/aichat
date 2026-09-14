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
        extraHeaders: Map<String, String> = emptyMap(),
        forceVision: Boolean = false,
        onCustomRequest: (() -> Unit)? = null
    ): String {

        val cleanModel = model.trim()

        if (apiKey.isBlank()) {
            throw IOException(
                "$providerName: المفتاح فارغ"
            )
        }

        if (cleanModel.isBlank()) {
            throw IOException(
                "$providerName: النموذج فارغ"
            )
        }

        if (baseUrl.isBlank()) {
            throw IOException(
                "$providerName: الرابط فارغ"
            )
        }

        val messages = JSONArray()

        messages.put(
            JSONObject().apply {
                put("role", "system")
                put(
                    "content",
                    "You are a helpful AI assistant."
                )
            }
        )

        history.takeLast(maxHistory).forEach { msg ->

            messages.put(
                JSONObject().apply {
                    put("role", msg.role)
                    put("content", msg.content)
                }
            )
        }

        val hasImage =
            imageBase64 != null &&
                (
                    forceVision ||
                        supportsVision(cleanModel)
                )

        if (hasImage) {

            messages.put(
                JSONObject().apply {

                    put("role", "user")

                    put(
                        "content",
                        JSONArray().apply {

                            put(
                                JSONObject().apply {

                                    put(
                                        "type",
                                        "image_url"
                                    )

                                    put(
                                        "image_url",
                                        JSONObject().apply {
                                            put(
                                                "url",
                                                "data:image/jpeg;base64,$imageBase64"
                                            )
                                            put(
                                                "detail",
                                                "low"
                                            )
                                        }
                                    )
                                }
                            )

                            put(
                                JSONObject().apply {
                                    put(
                                        "type",
                                        "text"
                                    )
                                    put(
                                        "text",
                                        userMessage
                                    )
                                }
                            )
                        }
                    )
                }
            )

        } else {

            messages.put(
                JSONObject().apply {
                    put("role", "user")
                    put("content", userMessage)
                }
            )
        }

        val requestJson = JSONObject().apply {
            put("model", cleanModel)
            put("messages", messages)
            put("temperature", 0.7)
            put("max_tokens", maxTokens)
        }

        val requestBuilder =
            Request.Builder()
                .url(baseUrl)
                .post(
                    requestJson.toString()
                        .toRequestBody(
                            "application/json".toMediaType()
                        )
                )
                .addHeader(
                    "Authorization",
                    "Bearer $apiKey"
                )
                .addHeader(
                    "Content-Type",
                    "application/json"
                )

        extraHeaders.forEach { (key, value) ->
            requestBuilder.addHeader(key, value)
        }

        client.newCall(
            requestBuilder.build()
        ).execute().use { response ->

            val body =
                response.body?.string().orEmpty()

            if (!response.isSuccessful) {

                val msg = runCatching {

                    val j = JSONObject(body)

                    j.optJSONObject("error")
                        ?.optString("message")
                        ?: j.optString(
                            "message",
                            body
                        )

                }.getOrDefault(body)

                throw IOException(
                    when (response.code) {

                        429 ->
                            "⚠️ $providerName: تجاوزت حد الطلبات"

                        401 ->
                            "❌ $providerName: المفتاح غير صحيح"

                        403 ->
                            "❌ $providerName: لا توجد صلاحية"

                        422 ->
                            "❌ $providerName: صيغة خاطئة: $msg"

                        500 ->
                            "❌ $providerName: خطأ في الخادم"

                        else ->
                            "$providerName ${response.code}: $msg"
                    }
                )
            }

            val text =
                JSONObject(body)
                    .optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content", "")
                    ?.trim()

            val result =
                text?.takeIf {
                    it.isNotBlank()
                } ?: throw IOException(
                    "$providerName: الرد فارغ"
                )

            if (
                providerName.equals(
                    "Custom",
                    ignoreCase = true
                )
            ) {
                onCustomRequest?.invoke()
            }

            return result
        }
    }

    private fun supportsVision(
        model: String
    ): Boolean {

        val m = model.lowercase()

        return listOf(
            "gpt-4o",
            "gpt-4-turbo",
            "gpt-4-vision",
            "gpt-4.1",
            "gpt-5",
            "gemini",
            "claude-3",
            "claude-3.5",
            "claude-3.7",
            "pixtral",
            "llava",
            "vision",
            "qwen-vl",
            "qwen2-vl",
            "qwen2.5-vl",
            "minicpm-v",
            "internvl",
            "phi-3-vision",
            "phi-4-vision",
            "moondream",
            "bakllava",
            "cogvlm",
            "yi-vl"
        ).any {
            m.contains(it)
        }
    }
}
