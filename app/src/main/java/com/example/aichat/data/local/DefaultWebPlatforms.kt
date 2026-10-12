package com.example.aichat.data.local

import com.example.aichat.data.model.WebEngine
import com.example.aichat.data.model.WebPlatform

object DefaultWebPlatforms {

    val all: List<WebPlatform> = listOf(
        builtIn(
            id = "huggingchat",
            name = "HuggingChat",
            url = "https://huggingface.co/chat",
            emoji = "🤗",
            account = false,
            order = 1
        ),
        builtIn(
            id = "you",
            name = "You.com",
            url = "https://you.com",
            emoji = "🔎",
            account = false,
            order = 2
        ),
        builtIn(
            id = "perplexity",
            name = "Perplexity",
            url = "https://www.perplexity.ai",
            emoji = "🧭",
            account = false,
            order = 3
        ),

        // ✅ جديد
        builtIn(
            id = "arena",
            name = "Arena.ai",
            url = "https://arena.ai/",
            emoji = "🏟️",
            account = false,
            order = 4,
            // إرسال تلقائي (عام كبداية)
            inputSelector = "textarea, [contenteditable='true']",
            sendSelector = "button[type='submit'], button[aria-label*='send' i]",
            sendWithEnter = true
        ),

        builtIn(
            id = "venice",
            name = "Venice AI",
            url = "https://venice.ai",
            emoji = "🎭",
            account = false,
            order = 5,
            // إرسال تلقائي (عام كبداية)
            inputSelector = "textarea, [contenteditable='true']",
            sendSelector = "button[type='submit'], button[aria-label*='send' i]",
            sendWithEnter = true
        ),
        builtIn(
            id = "chatgpt",
            name = "ChatGPT",
            url = "https://chatgpt.com",
            emoji = "💬",
            account = true,
            order = 6,
            // إرسال تلقائي (مخصص)
            inputSelector = "textarea#prompt-textarea, textarea[name='prompt']",
            sendSelector = "button[data-testid='send-button'], button[aria-label*='Send' i]",
            sendWithEnter = true,
            // (اختياري) selector لالتقاط ردود المساعد إن كنت تستخدمه
            aiMessageSelector = "div[data-message-author-role='assistant']"
        ),
        builtIn(
            id = "claude",
            name = "Claude",
            url = "https://claude.ai",
            emoji = "🟠",
            account = true,
            order = 7
        ),
        builtIn(
            id = "gemini",
            name = "Gemini Web",
            url = "https://gemini.google.com",
            emoji = "✨",
            account = true,
            order = 8
        ),
        builtIn(
            id = "grok",
            name = "Grok",
            url = "https://grok.com",
            emoji = "🚀",
            account = true,
            order = 9
        ),
        builtIn(
            id = "copilot",
            name = "Copilot",
            url = "https://copilot.microsoft.com",
            emoji = "🧩",
            account = true,
            order = 10
        ),
        builtIn(
            id = "lechat",
            name = "Le Chat (Mistral)",
            url = "https://chat.mistral.ai",
            emoji = "🌬️",
            account = true,
            order = 11
        )
    )

    private fun builtIn(
        id: String,
        name: String,
        url: String,
        emoji: String,
        account: Boolean,
        order: Int,
        // هذه الحقول موجودة أصلاً عندك
        aiMessageSelector: String = "",
        memoryEnabled: Boolean = true,
        // ✅ الحقول الجديدة للإرسال التلقائي (تأكد أنك أضفتها في WebPlatform + Migration)
        inputSelector: String = "",
        sendSelector: String = "",
        sendWithEnter: Boolean = true
    ) = WebPlatform(
        id = id,
        name = name,
        url = url,
        iconEmoji = emoji,
        requiresAccount = account,
        preferredEngine = WebEngine.GECKO.name,
        isBuiltIn = true,
        sortOrder = order,
        aiMessageSelector = aiMessageSelector,
        memoryEnabled = memoryEnabled,
        inputSelector = inputSelector,
        sendSelector = sendSelector,
        sendWithEnter = sendWithEnter
    )

    /**
     * ✅ مهم جداً:
     * insertAll = IGNORE → لن يحدّث منصات موجودة.
     * هذه الدالة:
     * - تضمن وجود المنصات
     * - وتملأ selectors فقط إذا كانت فارغة (بدون كسر تخصيص المستخدم)
     */
    suspend fun ensureSeeded(dao: WebPlatformDao) {
        dao.insertAll(all)

        // Patch send selectors only when empty
        all.forEach { p ->
            dao.patchSendConfigIfEmpty(
                id = p.id,
                inputSelector = p.inputSelector,
                sendSelector = p.sendSelector,
                sendWithEnter = p.sendWithEnter
            )
            dao.patchAiMessageSelectorIfEmpty(
                id = p.id,
                aiMessageSelector = p.aiMessageSelector
            )
        }
    }
}
