package com.example.aichat.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class WebEngine { GECKO, WEBVIEW, EXTERNAL }

@Entity(tableName = "web_platforms")
data class WebPlatform(
    @PrimaryKey val id: String,
    val name: String,
    val url: String,
    val iconEmoji: String = "🌐",
    val requiresAccount: Boolean = false,
    val preferredEngine: String = WebEngine.GECKO.name,
    val isBuiltIn: Boolean = false,
    val isEnabled: Boolean = true,
    val sortOrder: Int = 100,
    val createdAt: Long = System.currentTimeMillis()
)
