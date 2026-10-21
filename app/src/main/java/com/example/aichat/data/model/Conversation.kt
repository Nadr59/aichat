// app/src/main/java/com/example/aichat/data/model/Conversation.kt
package com.example.aichat.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "conversations",
    indices = [
        Index(value = ["updatedAt"]),
        Index(value = ["agentId"]),
        Index(value = ["workspaceId"]),
        Index(value = ["parentConversationId"]),
    ]
)
data class Conversation(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val memoryAccessEnabled: Boolean = true,
    val agentId: String? = null,          // Agent لكل محادثة (API)
    val workspaceId: Long? = null,        // ✅ v11: ربط المحادثة بـ Workspace
    val parentConversationId: Long? = null, 
    // ✅ v11: لدعم شجرة/تفرعات اختيارياً
    val branchContextMessageCount: Int = 0
)
