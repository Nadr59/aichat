package com.example.aichat.repository.agents

import com.example.aichat.data.model.AgentProfile
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.UUID

object SillyTavernCardParser {

    /**
     * يدعم:
     * - V1: الحقول في الجذر
     * - V2: الحقول داخل data {}
     */
    fun parseToAgent(jsonText: String, avatarUri: String? = null): AgentProfile {
        val root = JsonParser.parseString(jsonText).asJsonObject
        val data = root.getObjOrNull("data") ?: root

        fun s(vararg keys: String): String {
            for (k in keys) {
                val v = data.get(k)
                if (v != null && v.isJsonPrimitive) {
                    val t = v.asString.trim()
                    if (t.isNotBlank()) return t
                }
            }
            return ""
        }

        val id = UUID.randomUUID().toString()
        val name = s("name", "char_name", "character_name").ifBlank { "Imported Agent" }

        return AgentProfile(
            id = id,
            name = name,
            avatarUri = avatarUri,
            description = s("description", "desc"),
            personality = s("personality"),
            scenario = s("scenario"),
            firstMessage = s("first_mes", "first_message"),
            exampleMessages = s("mes_example", "example_dialogue", "examples"),
            systemPrompt = s("system_prompt"),
            postHistoryInstructions = s("post_history_instructions"),
            creatorNotes = s("creator_notes", "notes")
        )
    }

    private fun JsonObject.getObjOrNull(key: String): JsonObject? {
        val e: JsonElement? = this.get(key)
        return if (e != null && e.isJsonObject) e.asJsonObject else null
    }
}
