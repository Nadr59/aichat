package com.example.aichat.repository

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * يحوّل نصًا مشتركًا (ACTION_SEND) أو نصًا محدّدًا (PROCESS_TEXT) إلى MaterialItem،
 * بتنظيف مماثل لـ capture.js: حذف #fragment وبارامترات التتبّع، وقصّ علامات الترقيم
 * الملتصقة بنهاية الرابط، وتنظيف النص.
 *
 * لا يجلب أي صفحة من الشبكة: المحفوظ هو نص الرسالة وروابطها فقط.
 */
object SharedMaterialParser {

    const val METHOD_SHARE = "share"
    const val METHOD_PROCESS_TEXT = "process_text"

    private const val MAX_INPUT = 100_000
    private const val MAX_LINKS = 40
    private const val MIN_BODY_FOR_GOOD = 20

    // الرابط يتوقف عند: مسافة، علامات اقتباس وأقواس زاوية، •، « »، الفواصل العربية،
    // …، وعلامات الاتجاه وصفر العرض. هذا يمنع التصاق "•" بالرابط كما حدث سابقًا.
    private val URL_REGEX = Regex(
        "https?://[^\\s<>\"'`•«»“”‘’،؛؟…\\u200b-\\u200f\\u202a-\\u202e\\u2066-\\u2069\\ufeff]+",
        RegexOption.IGNORE_CASE
    )

    private val TRACKING = Regex("^(utm_|fbclid|gclid|igshid|ref_src)", RegexOption.IGNORE_CASE)

    fun parse(rawText: String, subject: String?, method: String): MaterialItem? {
        val input = rawText.take(MAX_INPUT)
        val subj = cleanText(subject.orEmpty()).replace('\n', ' ')

        val seen = LinkedHashSet<String>()

        // نستبدل كل رابط في النص بنسخته المنظّفة (مع إبقاء علامة الترقيم المقصوصة في مكانها)
        val cleaned = cleanText(
            URL_REGEX.replace(input) { m ->
                val (core, tail) = splitTrailing(m.value)
                if (hostOf(core).isEmpty()) return@replace m.value
                val href = cleanUrl(core)
                if (seen.size < MAX_LINKS) seen.add(href)
                href + tail
            }
        )
        if (cleaned.isEmpty()) return null

        val lines = cleaned.split('\n')
        val links = seen.map { href ->
            val line = lines.firstOrNull { it.contains(href) }.orEmpty()
            var label = line.replace(href, "").trim(' ', '•', '-', '–', '—', ':', '*', '>', '"')
            if (label.isEmpty() && seen.size == 1 && subj.isNotEmpty()) label = subj
            if (label.isEmpty()) label = hostOf(href)
            MaterialLink(label.take(160), href)
        }

        val firstLine = lines.map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
        val firstNoUrl = firstLine.replace(URL_REGEX, "").trim()
        val title = when {
            subj.isNotEmpty() -> subj
            firstNoUrl.isNotEmpty() -> firstNoUrl
            links.isNotEmpty() -> links[0].href
            else -> "نص مشترك"
        }.take(100)

        val id = if (links.size == 1) {
            "share:" + links[0].href
        } else {
            "share:t:" + Integer.toHexString(cleaned.take(300).hashCode())
        }

        // رابط فقط بلا نص حوله: نعلّمه ضعيفًا ليظهر ذلك في العلبة
        val body = URL_REGEX.replace(cleaned, "").replace(Regex("\\s+"), " ").trim()
        val linkOnly = links.isNotEmpty() && body.length < MIN_BODY_FOR_GOOD

        val arr = JSONArray()
        for (l in links) arr.put(JSONObject().put("text", l.text).put("href", l.href))

        val o = JSONObject()
            .put("id", id)
            .put("url", links.firstOrNull()?.href ?: "")
            .put("host", links.firstOrNull()?.let { hostOf(it.href) } ?: "")
            .put("title", title)
            .put("author", "")
            .put("date", "")
            .put("lang", detectLang(cleaned))
            .put("text", cleaned)
            .put("links", arr)
            .put("method", method)
            .put("quality", if (linkOnly) "weak" else "good")
            .put("reason", if (linkOnly) "link_only" else "")
            .put("truncated", false)
            .put("capturedAt", System.currentTimeMillis())

        // fromJson يطبّق حد 8000 حرف نفسه
        return MaterialItem.fromJson(o)
    }

    // ------------------------------------------------------------

    private fun cleanText(s: String): String = s
        .replace('\u00a0', ' ')
        .replace("\r", "")
        .replace(Regex("[ \t]+\n"), "\n")
        .replace(Regex("\n[ \t]+"), "\n")
        .replace(Regex("[ \t]{2,}"), " ")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

    private fun unbalanced(s: String, open: Char, close: Char): Boolean =
        s.count { it == close } > s.count { it == open }

    /** يفصل علامات الترقيم الزائدة في آخر الرابط. ")" تُقصّ فقط إن لم يكن لها "(" مقابل. */
    private fun splitTrailing(raw: String): Pair<String, String> {
        var end = raw.length
        while (end > 0) {
            val head = raw.substring(0, end)
            val strip = when (raw[end - 1]) {
                '.', ',', ';', ':', '!', '?' -> true
                ')' -> unbalanced(head, '(', ')')
                ']' -> unbalanced(head, '[', ']')
                '}' -> unbalanced(head, '{', '}')
                else -> false
            }
            if (!strip) break
            end--
        }
        return raw.substring(0, end) to raw.substring(end)
    }

    private fun cleanUrl(u: String): String {
        var s = u
        val h = s.indexOf('#')
        if (h >= 0) s = s.substring(0, h)

        val q = s.indexOf('?')
        if (q >= 0) {
            val base = s.substring(0, q)
            val kept = s.substring(q + 1).split('&').filter { p ->
                p.isNotEmpty() && !TRACKING.containsMatchIn(p.substringBefore('='))
            }
            s = if (kept.isEmpty()) base else base + "?" + kept.joinToString("&")
        }
        return s
    }

    private fun hostOf(u: String): String =
        runCatching { java.net.URL(u).host.lowercase(Locale.ROOT) }.getOrDefault("")

    private fun detectLang(text: String): String {
        val sample = text.take(1500)
        val ar = sample.count { it in '\u0600'..'\u06FF' }
        val la = sample.count { it in 'A'..'Z' || it in 'a'..'z' }
        return if (ar > la && ar >= 8) "ar" else "und"
    }
}
