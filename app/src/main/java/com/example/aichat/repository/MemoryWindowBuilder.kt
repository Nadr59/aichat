package com.example.aichat.repository

import com.example.aichat.data.model.MemoryItem
import java.security.MessageDigest
import kotlin.math.max

/**
 * MemoryWindowBuilder (Production V2)
 *
 * Selected IDs -> Candidates -> Structured Memory Window
 *
 * - لا يلخّص
 * - لا يعيد ترتيب معنى المحتوى
 * - فقط: تغليف + metadata + قص ضمن ميزانية + sanitization
 */
class MemoryWindowBuilder {

    companion object {
        // مطابق للـ Curator الحالي (take(5) في sanitization بالCurator نفسه)
        private const val MAX_MEMORIES = 5

        // Budget إجمالي لنافذة الذاكرة فقط (تقريبي tokens)
        // ارفع/اخفض حسب مزودك وسعة السياق، لكن لا تجعله غير محدود.
        private const val MAX_TOTAL_TOKENS = 1200

        // Budget لكل ذاكرة حتى لا تهيمن ذاكرة واحدة
        private const val MAX_TOKENS_PER_MEMORY = 420

        // حماية ثانية بالـ chars
        private const val MAX_CHARS_PER_MEMORY = 1800
    }

    fun build(
        selectedIds: List<Long>,
        candidates: List<MemoryItem>
    ): String {
        if (selectedIds.isEmpty() || candidates.isEmpty()) return ""

        val byId = candidates.associateBy { it.id }

        // نحافظ على ترتيب selectedIds (قرار الوسيط)، مع إزالة التكرار
        val picked = selectedIds
            .asSequence()
            .distinct()
            .mapNotNull { byId[it] }
            .take(MAX_MEMORIES)
            .toList()

        if (picked.isEmpty()) return ""

        var usedTokens = 0
        val out = ArrayList<String>(picked.size)

        // Dedup بسيط لتفادي تكرار chunks المتشابهة
        val seen = HashSet<String>()

        for (m in picked) {
            val raw = m.content.trim()
            if (raw.isBlank()) continue

            val normalized = sanitize(raw)
            if (normalized.isBlank()) continue

            val dedupKey = (m.contentHash.takeIf { it.isNotBlank() } ?: sha256(normalized))
            if (!seen.add(dedupKey)) continue

            val trimmed = truncateToBudget(
                text = normalized,
                maxTokens = MAX_TOKENS_PER_MEMORY,
                maxChars = MAX_CHARS_PER_MEMORY
            )

            val t = estimateTokens(trimmed)
            if (usedTokens + t > MAX_TOTAL_TOKENS) break
            usedTokens += t

            // Metadata
            val category = escapeXmlAttr(m.category.trim())
            val createdAt = m.createdAt
            val scid = m.sourceConversationId?.toString() ?: ""
            val smid = m.sourceMessageId?.toString() ?: ""

            out += buildString {
                append(
                    """<memory id="${m.id}" category="$category" createdAt="$createdAt""""
                )
                if (scid.isNotBlank()) append(""" sourceConversationId="$scid"""")
                if (smid.isNotBlank()) append(""" sourceMessageId="$smid"""")
                append(">")
                append("\n")
                append(escapeXmlText(trimmed))
                append("\n</memory>")
            }
        }

        if (out.isEmpty()) return ""

        // نافذة منظمة: سهلة للفهم + سهلة لعمل citations لاحقاً
        return buildString {
            appendLine("<shared_memories>")
            out.forEach { appendLine(it) }
            appendLine("</shared_memories>")
        }.trim()
    }

    // ------------------------------------------------------------
    // Sanitization / Injection-hardening (خفيف محافظ)
    // ------------------------------------------------------------

    private fun sanitize(text: String): String {
        return text
            // control chars
            .replace(Regex("[\\u0000-\\u001F\\u007F]"), " ")
            // منع كسر markdown fences
            .replace("```", "")
            // كلمات حقن شائعة (إنجليزي + عربي)
            .replace(Regex("(?i)ignore\\s+previous\\s+instructions"), "[filtered]")
            .replace(Regex("(?i)system\\s+prompt"), "[filtered]")
            .replace(Regex("(?i)developer\\s+message"), "[filtered]")
            .replace(Regex("تجاهل\\s+التعليمات\\s+السابقة"), "[filtered]")
            .replace(Regex("رسالة\\s+النظام"), "[filtered]")
            // توحيد المسافات
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun escapeXmlText(text: String): String =
        text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

    private fun escapeXmlAttr(text: String): String =
        escapeXmlText(text)
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

    // ------------------------------------------------------------
    // Budget helpers
    // ------------------------------------------------------------

    private fun estimateTokens(text: String): Int {
        if (text.isBlank()) return 0
        // تقريب مشابه لما عندك في OpenAICompatibleProvider
        val arabicChars = text.count { it in '\u0600'..'\u06FF' }
        val ratio = arabicChars.toDouble() / max(1, text.length)
        return if (ratio > 0.3) {
            (text.length * 0.6).toInt().coerceAtLeast(1)
        } else {
            (text.length / 4).coerceAtLeast(1)
        }
    }

    private fun truncateToBudget(
        text: String,
        maxTokens: Int,
        maxChars: Int
    ): String {
        var t = text
        if (t.length > maxChars) {
            t = t.take(maxChars).trimEnd() + " …[TRUNCATED]"
        }
        if (estimateTokens(t) <= maxTokens) return t

        // binary search على طول النص للاقتراب من maxTokens
        var lo = 0
        var hi = t.length
        while (lo + 1 < hi) {
            val mid = (lo + hi) / 2
            val sub = t.substring(0, mid)
            if (estimateTokens(sub) <= maxTokens) lo = mid else hi = mid
        }
        val cut = t.substring(0, lo).trimEnd()
        return if (cut.isBlank()) "" else "$cut …[TRUNCATED]"
    }

    private fun sha256(text: String): String =
        try {
            MessageDigest.getInstance("SHA-256")
                .digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            text.hashCode().toString()
        }
                                              }
