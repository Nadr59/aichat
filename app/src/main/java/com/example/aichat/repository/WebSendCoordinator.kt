package com.example.aichat.repository

import java.util.Locale

/**
 * منطق بناء نص الإرسال النهائي لمنصات الويب (مستخرج من GeckoTestScreen.kt
 * دون أي تغيير سلوكي)، ليكون قابلاً لإعادة الاستخدام من أي واجهة مستقبلية
 * (مثل مكوّن GeckoView مخفي داخل ChatScreen).
 */
object WebSendCoordinator {

    enum class WebKind { CHATGPT, VENICE, ARENA, OTHER }

    private const val AICHAT_CTX_BEGIN = "<<<AICHAT_HIDDEN_CONTEXT_BEGIN>>>"
    private const val AICHAT_CTX_END = "<<<AICHAT_HIDDEN_CONTEXT_END>>>"

    fun classifyHost(host: String): WebKind {
        val h = host.lowercase(Locale.ROOT)
        return when {
            h.contains("chatgpt.com") || h.contains("openai.com") -> WebKind.CHATGPT
            h.contains("venice.ai") -> WebKind.VENICE
            h.contains("lmsys") || h.contains("arena") -> WebKind.ARENA
            else -> WebKind.OTHER
        }
    }

    fun buildFinalTextForPlatform(
        kind: WebKind,
        contextBlock: String,
        question: String
    ): String {
        val ctx = contextBlock.trim()
        if (ctx.isBlank()) return question

        return when (kind) {
            WebKind.ARENA -> {
                "### Context (visible)\n$ctx\n### End Context\n\n$question"
            }
            WebKind.CHATGPT, WebKind.VENICE -> {
                "$AICHAT_CTX_BEGIN\n$ctx\n$AICHAT_CTX_END\n\n$question"
            }
            WebKind.OTHER -> {
                "$ctx\n\n$question"
            }
        }
    }

    /**
     * بناء موحّد لكتلة السياق (contextBlock) المرسلة لمنصات الويب.
     *
     * - workspaceSummary تُستخدم في مسار الإرسال العادي (onSend).
     * - handoffSummary تُستخدم في مسار الانتقال بين المنصات (Handoff)
     *   وهي بديلة عن workspaceSummary (لا تُستخدمان معًا عمليًا).
     * - الترتيب مطابق تمامًا لما كان مكتوبًا يدويًا في GeckoTestScreen.kt
     *   في كلا الموضعين قبل الاستخراج.
     */
    fun buildContextBlock(
        agentPrompt: String = "",
        workspaceSummary: String = "",
        handoffSummary: String = "",
        accuracyPrompt: String? = null,
        extraNotes: String = "",
        memoryXml: String = ""
    ): String {
        return buildString {
            if (agentPrompt.isNotBlank()) {
                appendLine("AGENT_PERSONA:")
                appendLine(agentPrompt.trim())
                appendLine()
            }

            if (workspaceSummary.isNotBlank()) {
                appendLine("WORKSPACE_SUMMARY:")
                appendLine(workspaceSummary.trim())
                appendLine()
            }

            if (handoffSummary.isNotBlank()) {
                appendLine("WEB_HANDOFF_SUMMARY:")
                appendLine(handoffSummary.trim())
                appendLine()
            }

            if (!accuracyPrompt.isNullOrBlank()) {
                appendLine("ACCURACY_POLICY:")
                appendLine(accuracyPrompt.trim())
                appendLine()
            }

            if (extraNotes.isNotBlank()) {
                appendLine("EXTRA_NOTES:")
                appendLine(extraNotes.trim())
                appendLine()
            }

            if (memoryXml.isNotBlank()) {
                appendLine("SHARED_MEMORIES_XML:")
                appendLine(memoryXml.trim())
                appendLine()
            }
        }.trim()
    }
}
