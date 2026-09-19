package com.example.aichat.repository

import com.example.aichat.data.model.Message

data class AiRequest(
    val history:      List<Message> = emptyList(),
    val userMessage:  String,
    val imageBase64:  String?       = null,
    val systemPrompt: String        = "",
    val maxHistory:   Int           = 10,
    val maxTokens:    Int           = 4096
)

interface AiProvider {
    val id: String
    suspend fun send(request: AiRequest): String
}
