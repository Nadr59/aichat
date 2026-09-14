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

class MistralProvider(
    private val settings: AiSettings,
    private val client: OkHttpClient
) {

    fun send(
        history: List<Message>,
        userMessage: String,
        imageBase64: String?
    ): String {

        val apiKey = settings.mistralKey.trim()
        val model = settings.mistralModel.trim()

        if (apiKey.isBlank()) {
            throw IOException(
                "Mistral: المفتاح فارغ"
            )
        }

        if (model.isBlank()) {
            throw IOException(
                "Mistral: النموذج فارغ"
            )
        }

        val messages = JSONArray()

        val maxHistory =
            when {
                true -> 6
            }

        val maxTokens = 2048

        history.takeLast(maxHistory).forEach { msg ->

            messages.put(
                JSONObject().apply {
                    put("role", msg.role)
                    put("content", msg.content)
                }
            )
        }

        val isVision =
            model.contains("pixtral") ||
                model.contains("vision")

        if (
            imageBase64 != null &&
            isVision
        ) {

            messages.put(
                JSONObject().apply {

                    put("role", "user")

                    put(
                        "content",
                        JSONArray().apply {

                            put(
                                JSONObject().apply {
                                    put("type", "text")
                                    put("text", userMessage)
                                }
                            )

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
                                        }
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
            put("model", model)
            put("messages", messages)
            put("temperature", 0.7)
            put("max_tokens", maxTokens)
        }

        val request = Request.Builder()
            .url(
                "https://api.mistral.ai/v1/chat/completions"
            )
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
            .build()

        client.newCall(request).execute().use { response ->

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
                            "⚠️ Mistral: تجاوزت حد الطلبات\n" +
                                "النموذج: $model"

                        401 ->
                            "❌ Mistral: المفتاح غير صحيح"

                        422 ->
                            "❌ Mistral: صيغة خاطئة: $msg"

                        else ->
                            "Mistral ${response.code}: $msg"
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

            return text?.takeIf {
                it.isNotBlank()
            } ?: throw IOException(
                "Mistral: الرد فارغ"
            )
        }
    }
}
