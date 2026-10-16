package com.example.aichat.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "agents")
data class AgentProfile(
    @PrimaryKey val id: String,
    val name: String,

    // optional avatar reference (e.g., imported PNG uri)
    val avatarUri: String? = null,

    // SillyTavern normalized fields
    val description: String = "",
    val personality: String = "",
    val scenario: String = "",
    val firstMessage: String = "",
    val exampleMessages: String = "",

    // advanced
    val systemPrompt: String = "",
    val postHistoryInstructions: String = "",
    val creatorNotes: String = "",

    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
