package com.example.aichat.repository

import com.example.aichat.data.model.AgentProfile

object AgentPromptBuilder {
    fun build(agent: AgentProfile): String = buildString {
        appendLine("Name: ${agent.name}")

        if (agent.description.isNotBlank()) {
            appendLine()
            appendLine("Description:")
            appendLine(agent.description.trim())
        }

        if (agent.personality.isNotBlank()) {
            appendLine()
            appendLine("Personality:")
            appendLine(agent.personality.trim())
        }

        if (agent.scenario.isNotBlank()) {
            appendLine()
            appendLine("Scenario:")
            appendLine(agent.scenario.trim())
        }

        if (agent.exampleMessages.isNotBlank()) {
            appendLine()
            appendLine("Example Messages:")
            appendLine(agent.exampleMessages.trim())
        }

        if (agent.postHistoryInstructions.isNotBlank()) {
            appendLine()
            appendLine("Post-History Instructions:")
            appendLine(agent.postHistoryInstructions.trim())
        }

        if (agent.systemPrompt.isNotBlank()) {
            appendLine()
            appendLine("System Prompt:")
            appendLine(agent.systemPrompt.trim())
        }

        if (agent.creatorNotes.isNotBlank()) {
            appendLine()
            appendLine("Creator Notes:")
            appendLine(agent.creatorNotes.trim())
        }
    }.trim()
}
