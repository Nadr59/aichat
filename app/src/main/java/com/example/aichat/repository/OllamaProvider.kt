package com.example.aichat.repository

import com.example.aichat.data.local.AiSettings
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

class OllamaProvider(
    private val settings: AiSettings,
    private val client: OkHttpClient
) {

    fun send(
        userMessage: String,
        memoryContext: String = ""
    ): String {

        val model =
            settings.ollamaModel
                .trim()
                .ifBlank {
                    "qwen2.5:1.5b"
                }

        if (userMessage.isBlank()) {
            throw IOException(
                "Ollama: الرسالة فارغة"
            )
        }

        val prompt =
            buildString {

                if (memoryContext.isNotBlank()) {
                    append(memoryContext)
                    append("\n\n")
                }

                append(userMessage)
            }

        val requestJson = JSONObject().apply {
            put("model", model)
            put("prompt", prompt)
            put("stream", false)
        }

        val request = Request.Builder()
            .url(
                "http://127.0.0.1:11434/api/generate"
            )
            .post(
                requestJson.toString()
                    .toRequestBody(
                        "application/json".toMediaType()
                    )
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
                throw IOException(
                    "Ollama ${response.code}: " +
                        body.take(300)
                )
            }

            val text =
                runCatching {
                    JSONObject(body)
                        .optString(
                            "response",
                            ""
                        )
                        .trim()
                }.getOrElse {
                    throw IOException(
                        "Ollama: استجابة غير صالحة\n" +
                            body.take(300)
                    )
                }

            return text.takeIf {
                it.isNotBlank()
            } ?: throw IOException(
                "Ollama: الرد فارغ"
            )
        }
    }
}
