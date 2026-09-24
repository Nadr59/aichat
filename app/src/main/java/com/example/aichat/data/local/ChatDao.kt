package com.example.aichat.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.example.aichat.data.model.Conversation
import com.example.aichat.data.model.Message
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {

    // ============================================================
    // Conversations
    // ============================================================

    @Insert
    suspend fun insertConversation(conversation: Conversation): Long

    @Update
    suspend fun updateConversation(conversation: Conversation)

    @Delete
    suspend fun deleteConversation(conversation: Conversation)

    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun getAllConversations(): Flow<List<Conversation>>

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteMessagesByConversation(conversationId: Long)

    // ✅ جديد — لاستعادة حالة memoryAccessEnabled عند فتح محادثة
    @Query("SELECT * FROM conversations WHERE id = :id LIMIT 1")
    suspend fun getConversationById(id: Long): Conversation?

    // ✅ جديد — يحل محل updateConversation(Conversation(...)) الخطير
    // (لا يُصفّر createdAt ولا memoryAccessEnabled عند كل رسالة)
    @Query("UPDATE conversations SET title = :title, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateTitleAndTimestamp(id: Long, title: String, updatedAt: Long)

    // ✅ جديد — تحديث معزول تماماً عن باقي الحقول
    @Query("UPDATE conversations SET memoryAccessEnabled = :enabled WHERE id = :id")
    suspend fun updateMemoryAccess(id: Long, enabled: Boolean)

    // ============================================================
    // Messages
    // ============================================================

    @Insert
    suspend fun insertMessage(message: Message): Long

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    fun getMessages(conversationId: Long): Flow<List<Message>>

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteMessages(conversationId: Long)

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    suspend fun getMessagesOnce(conversationId: Long): List<Message>
}
