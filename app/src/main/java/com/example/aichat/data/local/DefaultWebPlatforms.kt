package com.example.aichat.data.local

import com.example.aichat.data.model.WebPlatform
import com.example.aichat.data.model.WebEngine

object DefaultWebPlatforms {

    val all: List<WebPlatform> = listOf(
        builtIn("huggingchat", "HuggingChat",
            "https://huggingface.co/chat", "🤗", false, 1),
        builtIn("you", "You.com",
            "https://you.com", "🔎", false, 2),
        builtIn("perplexity", "Perplexity",
            "https://www.perplexity.ai", "🧭", false, 3),
        builtIn("venice", "Venice AI",
            "https://venice.ai", "🎭", false, 4),
        builtIn("chatgpt", "ChatGPT",
            "https://chatgpt.com", "💬", true, 5),
        builtIn("claude", "Claude",
            "https://claude.ai", "🟠", true, 6),
        builtIn("gemini", "Gemini Web",
            "https://gemini.google.com", "✨", true, 7),
        builtIn("grok", "Grok",
            "https://grok.com", "🚀", true, 8),
        builtIn("copilot", "Copilot",
            "https://copilot.microsoft.com", "🧩", true, 9),
        builtIn("lechat", "Le Chat (Mistral)",
            "https://chat.mistral.ai", "🌬️", true, 10),
    )

    private fun builtIn(
        id: String,
        name: String,
        url: String,
        emoji: String,
        account: Boolean,
        order: Int
    ) = WebPlatform(
        id              = id,
        name            = name,
        url             = url,
        iconEmoji       = emoji,
        requiresAccount = account,
        preferredEngine = WebEngine.GECKO.name,
        isBuiltIn       = true,
        sortOrder       = order
    )
}
