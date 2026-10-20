// app/src/main/java/com/example/aichat/data/local/ChatDao.kt
package com.example.aichat.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.aichat.data.model.Conversation
import com.example.aichat.data.model.Message
import com.example.aichat.data.model.Workspace
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

    @Query("SELECT * FROM conversations WHERE id = :id LIMIT 1")
    suspend fun getConversationById(id: Long): Conversation?

    @Query("UPDATE conversations SET title = :title, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateTitleAndTimestamp(id: Long, title: String, updatedAt: Long)

    @Query("UPDATE conversations SET memoryAccessEnabled = :enabled WHERE id = :id")
    suspend fun updateMemoryAccess(id: Long, enabled: Boolean)

    // ✅ تحديث Agent للمحادثة
    @Query("UPDATE conversations SET agentId = :agentId, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateAgentId(id: Long, agentId: String?, updatedAt: Long)

    // ✅ v11: ربط المحادثة بـ Workspace + parentConversationId (لدعم الشجرة)
    @Query(
        """
        UPDATE conversations 
        SET workspaceId = :workspaceId, parentConversationId = :parentConversationId, updatedAt = :updatedAt 
        WHERE id = :id
        """
    )
    suspend fun updateConversationWorkspace(
        id: Long,
        workspaceId: Long?,
        parentConversationId: Long?,
        updatedAt: Long
    )

    // اختياري (سيُستخدم لاحقًا في “الذاكرة الشجرية/Workspace UI”)
    @Query("SELECT * FROM conversations WHERE workspaceId = :workspaceId ORDER BY updatedAt DESC")
    fun observeConversationsByWorkspace(workspaceId: Long): Flow<List<Conversation>>

    @Query("SELECT * FROM conversations WHERE workspaceId = :workspaceId AND parentConversationId IS NULL ORDER BY updatedAt DESC")
    fun observeRootConversationsByWorkspace(workspaceId: Long): Flow<List<Conversation>>

    @Query("SELECT * FROM conversations WHERE parentConversationId = :parentId ORDER BY updatedAt DESC")
    fun observeChildConversations(parentId: Long): Flow<List<Conversation>>

    // ✅ NEW: حذف شجرة Workspace بالكامل (محادثات + رسائل)
    @Query(
        """
        DELETE FROM messages 
        WHERE conversationId IN (SELECT id FROM conversations WHERE workspaceId = :workspaceId)
        """
    )
    suspend fun deleteMessagesByWorkspace(workspaceId: Long)

    @Query("DELETE FROM conversations WHERE workspaceId = :workspaceId")
    suspend fun deleteConversationsByWorkspace(workspaceId: Long)

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

    // ============================================================
    // Workspaces (v11)
    // ============================================================

    /**
     * إنشاء Workspace حسب scopeKey.
     * نستخدم IGNORE لتفادي crash إذا كان موجودًا مسبقًا (scopeKey فريد).
     * يعيد id جديد أو -1 إذا كان موجودًا.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertWorkspaceIgnore(workspace: Workspace): Long

    @Query("SELECT * FROM workspaces WHERE id = :id LIMIT 1")
    suspend fun getWorkspaceById(id: Long): Workspace?

    @Query("SELECT * FROM workspaces WHERE scopeKey = :scopeKey LIMIT 1")
    suspend fun getWorkspaceByScopeKey(scopeKey: String): Workspace?

    @Query("SELECT * FROM workspaces ORDER BY updatedAt DESC")
    fun observeAllWorkspaces(): Flow<List<Workspace>>

    @Query("UPDATE workspaces SET title = :title, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateWorkspaceTitle(id: Long, title: String, updatedAt: Long)

    @Query("UPDATE workspaces SET summary = :summary, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateWorkspaceSummary(id: Long, summary: String, updatedAt: Long)
}
