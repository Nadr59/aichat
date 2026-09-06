package com.example.aichat.repository

import android.content.Context
import com.example.aichat.data.local.AiSettings
import com.example.aichat.data.local.ChatDatabase
import com.example.aichat.data.model.Conversation
import com.example.aichat.data.model.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ChatRepository(context: Context) {

    private val dao = ChatDatabase.getDatabase(context).chatDao()
    private val settings = AiSettings(context)

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun getAllConversations(): Flow<List<Conversation>> =
        dao.getAllConversations()

    suspend fun createConversation(title: String): Long =
        dao.insertConversation(Conversation(title = title))

    suspend fun deleteConversation(conversation: Conversation) {
        dao.deleteMessagesByConversationId(conversation.id)
        dao.deleteConversation(conversation)
    }

    fun getMessages(conversationId: Long): Flow<List<Message>> =
        dao.getMessagesByConversationId(conversationId)

    suspend fun sendMessage(conversationId: Long, userMessage: String): String {
        val msg = Message(
            conversationId = conversationId,
            content = userMessage,
            isFromUser = true
        )
        dao.insertMessage(msg)

        val conv = Conversation(
            id = conversationId,
            title = "",
            updatedAt = System.currentTimeMillis()
        )
        dao.updateConversation(conv)

        return try {
            val aiResponse = callApi(userMessage)
            val aiMsg = Message(
                conversationId = conversationId,
                content = aiResponse,
                isFromUser = false
            )
            dao.insertMessage(aiMsg)
            aiResponse
        } catch (e: Exception) {
            val errorMsg = "Error: ${e.message}"
            val aiMsg = Message(
                conversationId = conversationId,
                content = errorMsg,
                isFromUser = false
            )
            dao.insertMessage(aiMsg)
            errorMsg
        }
    }

    suspend fun callApi(userMessage: String): String = withContext(Dispatchers.IO) {
        val messagesArray = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", settings.systemPrompt)
            })
            put(JSONObject().apply {
                put("role", "user")
                put("content", userMessage)
            })
        }

        val body = JSONObject().apply {
            put("model", settings.model)
            put("messages", messagesArray)
            put("temperature", settings.temperature.toDouble())
        }

        val request = Request.Builder()
            .url(settings.apiUrl)
            .addHeader("Authorization", "Bearer ${settings.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""

        val json = JSONObject(responseBody)
        json.getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
    }
}
