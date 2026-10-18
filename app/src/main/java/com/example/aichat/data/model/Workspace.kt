// app/src/main/java/com/example/aichat/data/model/Workspace.kt
package com.example.aichat.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "workspaces",
    indices = [
        Index(value = ["scopeKey"], unique = true),
        Index(value = ["updatedAt"]),
    ]
)
data class Workspace(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /**
     * يحدد مجال الـ Workspace:
     * أمثلة:
     *  - api:gemini:gemini-3.6-flash
     *  - api:openai:gpt-4o-mini
     *  - web:venice
     *  - web:chatgpt
     */
    val scopeKey: String,

    val title: String = "",
    val summary: String = "",

    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)
