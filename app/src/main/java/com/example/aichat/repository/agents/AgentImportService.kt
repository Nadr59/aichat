package com.example.aichat.repository.agents

import android.content.Context
import android.net.Uri
import com.example.aichat.data.model.AgentProfile

class AgentImportService(
    private val context: Context
) {
    suspend fun importFromUri(uri: Uri): AgentProfile {
        val bytes = context.contentResolver.openInputStream(uri)
            ?.use { it.readBytes() }
            ?: error("تعذر قراءة الملف")

        val isPng = bytes.size >= 8 && bytes[0] == 137.toByte() && bytes[1] == 80.toByte()

        val jsonText = if (isPng) {
            PngSillyTavernExtractor.extractCharaJson(bytes)
                ?: error("ملف PNG لا يحتوي بطاقة SillyTavern (tEXt/chara)")
        } else {
            String(bytes, Charsets.UTF_8)
        }

        return SillyTavernCardParser.parseToAgent(
            jsonText = jsonText,
            avatarUri = if (isPng) uri.toString() else null
        )
    }
}
