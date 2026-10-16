package com.example.aichat.repository

import com.example.aichat.data.local.ChatDao
import com.example.aichat.data.model.Conversation
import com.example.aichat.data.model.Message
import kotlinx.coroutines.flow.Flow

class ConversationRepository(
    private val chatDao: ChatDao
) {
    fun getAllConversations(): Flow<List<Conversation>> = chatDao.getAllConversations()
    suspend fun insertConversation(conversation: Conversation): Long = chatDao.insertConversation(conversation)
    suspend fun updateConversation(conversation: Conversation) = chatDao.updateConversation(conversation)
    suspend fun deleteConversation(conversation: Conversation) = chatDao.deleteConversation(conversation)

    suspend fun getConversationById(id: Long): Conversation? = chatDao.getConversationById(id)
    suspend fun updateTitleAndTimestamp(id: Long, title: String, updatedAt: Long) =
        chatDao.updateTitleAndTimestamp(id, title, updatedAt)

    suspend fun updateMemoryAccess(id: Long, enabled: Boolean) = chatDao.updateMemoryAccess(id, enabled)

    // ✅ جديد
    suspend fun updateAgentId(id: Long, agentId: String?) =
        chatDao.updateAgentId(id = id, agentId = agentId, updatedAt = System.currentTimeMillis())

    fun getMessages(conversationId: Long): Flow<List<Message>> = chatDao.getMessages(conversationId)
    suspend fun getMessagesOnce(conversationId: Long): List<Message> = chatDao.getMessagesOnce(conversationId)
    suspend fun insertMessage(message: Message): Long = chatDao.insertMessage(message)
    suspend fun deleteMessages(conversationId: Long) = chatDao.deleteMessages(conversationId)
}
