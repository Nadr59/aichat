// app/src/main/java/com/example/aichat/repository/ConversationRepository.kt
package com.example.aichat.repository

import com.example.aichat.data.local.ChatDao
import com.example.aichat.data.model.Conversation
import com.example.aichat.data.model.Message
import com.example.aichat.data.model.Workspace
import kotlinx.coroutines.flow.Flow

class ConversationRepository(
    private val chatDao: ChatDao
) {
    // ============================================================
    // Conversations (existing)
    // ============================================================

    fun getAllConversations(): Flow<List<Conversation>> = chatDao.getAllConversations()

    suspend fun insertConversation(conversation: Conversation): Long =
        chatDao.insertConversation(conversation)

    suspend fun updateConversation(conversation: Conversation) =
        chatDao.updateConversation(conversation)

    suspend fun deleteConversation(conversation: Conversation) =
        chatDao.deleteConversation(conversation)

    suspend fun getConversationById(id: Long): Conversation? =
        chatDao.getConversationById(id)

    suspend fun updateTitleAndTimestamp(id: Long, title: String, updatedAt: Long) =
        chatDao.updateTitleAndTimestamp(id, title, updatedAt)

    suspend fun updateMemoryAccess(id: Long, enabled: Boolean) =
        chatDao.updateMemoryAccess(id, enabled)

    suspend fun updateAgentId(id: Long, agentId: String?) =
        chatDao.updateAgentId(
            id = id,
            agentId = agentId,
            updatedAt = System.currentTimeMillis()
        )

    // ✅ v11: ربط المحادثة بـ Workspace + parentConversationId
    suspend fun updateConversationWorkspace(
        conversationId: Long,
        workspaceId: Long?,
        parentConversationId: Long?
    ) {
        chatDao.updateConversationWorkspace(
            id = conversationId,
            workspaceId = workspaceId,
            parentConversationId = parentConversationId,
            updatedAt = System.currentTimeMillis()
        )
    }

    // اختياري لشاشة “الذاكرة الشجرية” لاحقاً
    fun observeConversationsByWorkspace(workspaceId: Long): Flow<List<Conversation>> =
        chatDao.observeConversationsByWorkspace(workspaceId)

    fun observeRootConversationsByWorkspace(workspaceId: Long): Flow<List<Conversation>> =
        chatDao.observeRootConversationsByWorkspace(workspaceId)

    fun observeChildConversations(parentConversationId: Long): Flow<List<Conversation>> =
        chatDao.observeChildConversations(parentConversationId)

    /**
     * ✅ Helper: إنشاء محادثة داخل Workspace (scopeKey) مباشرة
     * هذا يسهل علينا في ChatViewModel تطبيق “استكمال بدون انقطاع”.
     */
    suspend fun insertConversationInWorkspace(
        scopeKey: String,
        conversation: Conversation,
        workspaceTitle: String = "",
        parentConversationId: Long? = null
    ): Long {
        val ws = getOrCreateWorkspace(scopeKey = scopeKey, title = workspaceTitle)
        val convInWs = conversation.copy(
            workspaceId = ws.id,
            parentConversationId = parentConversationId
        )
        return chatDao.insertConversation(convInWs)
    }

    // ============================================================
    // Messages (existing)
    // ============================================================

    fun getMessages(conversationId: Long): Flow<List<Message>> =
        chatDao.getMessages(conversationId)

    suspend fun getMessagesOnce(conversationId: Long): List<Message> =
        chatDao.getMessagesOnce(conversationId)

    suspend fun insertMessage(message: Message): Long =
        chatDao.insertMessage(message)

    suspend fun deleteMessages(conversationId: Long) =
        chatDao.deleteMessages(conversationId)

    // ============================================================
    // Workspaces (v11) - Workspace Summary
    // ============================================================

    fun observeAllWorkspaces(): Flow<List<Workspace>> =
        chatDao.observeAllWorkspaces()

    suspend fun getWorkspaceById(id: Long): Workspace? =
        chatDao.getWorkspaceById(id)

    suspend fun getWorkspaceByScopeKey(scopeKey: String): Workspace? =
        chatDao.getWorkspaceByScopeKey(scopeKey)

    /**
     * ينشئ Workspace إذا غير موجود (scopeKey فريد).
     * - إذا تم الإدخال: يرجع Workspace الجديد
     * - إذا كان موجودًا: يرجع الموجود
     */
    suspend fun getOrCreateWorkspace(
        scopeKey: String,
        title: String = ""
    ): Workspace {
        // 1) حاول تجيب الموجود أولاً (أرخص)
        chatDao.getWorkspaceByScopeKey(scopeKey)?.let { existing ->
            // إذا العنوان فاضي بالمخزن وعندنا عنوان جديد، حدّثه
            if (existing.title.isBlank() && title.isNotBlank()) {
                chatDao.updateWorkspaceTitle(
                    id = existing.id,
                    title = title,
                    updatedAt = System.currentTimeMillis()
                )
                return existing.copy(title = title, updatedAt = System.currentTimeMillis())
            }
            return existing
        }

        // 2) لو غير موجود: جرّب insert IGNORE (في حال سباق)
        val now = System.currentTimeMillis()
        val insertedId = chatDao.insertWorkspaceIgnore(
            Workspace(
                scopeKey = scopeKey,
                title = title,
                summary = "",
                createdAt = now,
                updatedAt = now
            )
        )

        // 3) إذا نجح الإدخال: رجّع بالسحب من DB
        if (insertedId > 0) {
            return requireNotNull(chatDao.getWorkspaceById(insertedId)) {
                "Workspace inserted but cannot be loaded (id=$insertedId)"
            }
        }

        // 4) إذا كان موجودًا (insert رجع -1): اسحبه
        return requireNotNull(chatDao.getWorkspaceByScopeKey(scopeKey)) {
            "Workspace exists but cannot be loaded (scopeKey=$scopeKey)"
        }
    }

    suspend fun updateWorkspaceTitle(workspaceId: Long, title: String) {
        chatDao.updateWorkspaceTitle(
            id = workspaceId,
            title = title,
            updatedAt = System.currentTimeMillis()
        )
    }

    suspend fun updateWorkspaceSummary(workspaceId: Long, summary: String) {
        chatDao.updateWorkspaceSummary(
            id = workspaceId,
            summary = summary,
            updatedAt = System.currentTimeMillis()
        )
    }
}
