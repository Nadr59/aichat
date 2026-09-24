package com.example.aichat.repository

import com.example.aichat.data.local.ChatDao
import com.example.aichat.data.model.Conversation
import com.example.aichat.data.model.Message
import kotlinx.coroutines.flow.Flow

class ConversationRepository(
    private val chatDao: ChatDao
) {

    // ============================================================
    // Conversations
    // ============================================================

    fun getAllConversations(): Flow<List<Conversation>> {
        return chatDao.getAllConversations()
    }

    suspend fun insertConversation(conversation: Conversation): Long {
        return chatDao.insertConversation(conversation)
    }

    suspend fun updateConversation(conversation: Conversation) {
        chatDao.updateConversation(conversation)
    }

    suspend fun deleteConversation(conversation: Conversation) {
        chatDao.deleteConversation(conversation)
    }

    // ✅ جديد
    suspend fun getConversationById(id: Long): Conversation? {
        return chatDao.getConversationById(id)
    }

    // ✅ جديد
    suspend fun updateTitleAndTimestamp(id: Long, title: String, updatedAt: Long) {
        chatDao.updateTitleAndTimestamp(id, title, updatedAt)
    }

    // ✅ جديد
    suspend fun updateMemoryAccess(id: Long, enabled: Boolean) {
        chatDao.updateMemoryAccess(id, enabled)
    }

    // ============================================================
    // Messages
    // ============================================================

    fun getMessages(conversationId: Long): Flow<List<Message>> {
        return chatDao.getMessages(conversationId)
    }

    suspend fun getMessagesOnce(conversationId: Long): List<Message> {
        return chatDao.getMessagesOnce(conversationId)
    }

    suspend fun insertMessage(message: Message): Long {
        return chatDao.insertMessage(message)
    }

    suspend fun deleteMessages(conversationId: Long) {
        chatDao.deleteMessages(conversationId)
    }
}
