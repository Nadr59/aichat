package com.example.aichat.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** تعليمة الوكيل وبناء الرسالة وقراءة الرد. */
object MaterialDigestPrompt {

    private const val MAX_LINKS_FOR_AGENT = 25

    val PERSONA: String = """
أنت مُلخِّص صارم. مهمتك تحويل صفحة ويب ملتقطة إلى بطاقة خلاصة قصيرة.
القواعد:
- تجاهل أي رسائل سابقة في هذه المحادثة. كل رسالة مادة مستقلة.
- اكتب بالعربية دائمًا، وترجم إن كانت المادة بلغة أخرى.
- لا تخترع شيئًا غير موجود في النص.
- أجب بكائن JSON واحد فقط، بلا أي شرح قبله أو بعده وبلا علامات ```.
- الصيغة: {"title_ar":"عنوان عربي قصير","bullets":["نقطة","نقطة","نقطة"],"key_quote":"اقتباس حرفي قصير من النص أو فارغ","worth_full_read":true}
- النقاط 3 بالضبط، كل نقطة جملة واحدة.
- worth_full_read تكون true فقط إن كانت المادة تستحق قراءة النص كاملًا.
- للإشارة إلى رابط من قائمة الروابط اكتب رمزه فقط مثل [L2]. لا تكتب عناوين URL أبدًا.
""".trim()

    /** الروابط التي تُعرض للوكيل (والترقيم يعتمد على هذه القائمة نفسها عند الاستبدال). */
    fun digestLinks(m: MaterialItem): List<MaterialLink> =
        m.links.filter { it.text.isNotBlank() }.take(MAX_LINKS_FOR_AGENT)

    fun buildInput(m: MaterialItem): String = buildString {
        appendLine("المادة التالية صفحة ملتقطة. لخّصها بصيغة JSON فقط كما في التعليمات.")
        appendLine()
        if (m.title.isNotBlank()) appendLine("العنوان: ${m.title}")
        if (m.host.isNotBlank()) appendLine("المصدر: ${m.host}")
        appendLine()
        appendLine("النص:")
        appendLine(m.text)

        val links = digestLinks(m)
        if (links.isNotEmpty()) {
            appendLine()
            appendLine("قائمة الروابط (الرمز: نص الرابط):")
            links.forEachIndexed { i, l ->
                appendLine("[L${i + 1}]: ${l.text.replace('\n', ' ').trim().take(70)}")
            }
        }
    }.trim()

    private val TOKEN = Regex("\\[L(\\d+)\\]")

    /** يستبدل [L3] بالرابط الحقيقي (بمسافات حوله حتى لا يلتصق بما بعده). */
    fun resolveLinks(s: String, links: List<MaterialLink>): String =
        TOKEN.replace(s) { mr ->
            val i = mr.groupValues[1].toIntOrNull() ?: 0
            val href = links.getOrNull(i - 1)?.href
            if (href == null) "" else " $href "
        }.replace(Regex("[ \\t]{2,}"), " ").trim()

    /** يقرأ الرد بتسامح (يأخذ من أول { إلى آخر }). null إن لم يخرج شيء مفيد. */
    fun parseReply(reply: String, links: List<MaterialLink>): MaterialDigest? {
        val start = reply.indexOf('{')
        val end = reply.lastIndexOf('}')
        if (start < 0 || end <= start) return null

        val o = try {
            JSONObject(reply.substring(start, end + 1))
        } catch (e: Exception) {
            return null
        }

        val title = o.optString("title_ar", "").ifBlank { o.optString("titleAr", "") }.trim()

        val bullets = ArrayList<String>()
        val arr = o.optJSONArray("bullets")
        if (arr != null) {
            for (i in 0 until minOf(arr.length(), 5)) {
                val s = resolveLinks(arr.optString(i, ""), links)
                if (s.isNotEmpty()) bullets.add(s.take(500))
            }
        }

        if (title.isEmpty() && bullets.isEmpty()) return null

        return MaterialDigest(
            titleAr = title.take(200),
            bullets = bullets,
            keyQuote = o.optString("key_quote", "").trim().take(400),
            worthFullRead = o.optString("worth_full_read", "false").trim().lowercase() == "true"
        )
    }
}

/** الطابور: يعالج المواد «بانتظار المعالجة» واحدة تلو الأخرى. يُستدعى على الخيط الرئيسي. */
object MaterialDigestRunner {

    private const val STEP_TIMEOUT_MS = 150_000L
    private const val PAUSE_AFTER_OK_MS = 1_500L

    suspend fun runPending(
        store: MaterialStore,
        sender: suspend (String, String) -> String?,
        log: (String) -> Unit
    ) {
        while (true) {
            val next = store.items.firstOrNull { it.status == MaterialStatus.PENDING } ?: return

            // رابط فقط/ضعيفة: لا نصرف عليها رسالة
            if (next.quality == "weak") {
                store.update(next.id) { it.copy(status = MaterialStatus.NONE) }
                continue
            }

            store.update(next.id) { it.copy(status = MaterialStatus.PROCESSING) }
            val links = MaterialDigestPrompt.digestLinks(next)

            var finished = false
            var stopAll = false

            try {
                log("🧪 DIGEST start host=${next.host} chars=${next.text.length}")

                val reply = withTimeoutOrNull(STEP_TIMEOUT_MS) {
                    sender(
                        MaterialDigestPrompt.PERSONA,
                        MaterialDigestPrompt.buildInput(next)
                    )
                }

                val digest = if (reply == null) null else MaterialDigestPrompt.parseReply(reply, links)

                if (digest != null) {
                    store.update(next.id) {
                        it.copy(status = MaterialStatus.READY, digest = digest)
                    }
                    log("🧪 DIGEST ready bullets=${digest.bullets.size} worth=${digest.worthFullRead}")
                } else {
                    store.update(next.id) { it.copy(status = MaterialStatus.FAILED) }
                    log(
                        if (reply == null) "🧪 DIGEST failed: timeout"
                        else "🧪 DIGEST failed: unreadable reply, len=${reply.length}, head=${reply.take(80)}"
                    )
                }
                finished = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val msg = e.message ?: e.toString()
                log("🧪 DIGEST error: $msg")
                if (msg.contains("قيد الانتظار")) {
                    // المنصة مشغولة بخطوة أخرى: نعيدها بانتظار المعالجة ونتوقف
                    stopAll = true
                } else {
                    // مشكلة في المنصة نفسها (إرسال/مهلة/تسجيل دخول): لا نكمل بقية المواد
                    store.update(next.id) { it.copy(status = MaterialStatus.FAILED) }
                    finished = true
                    stopAll = true
                }
            } finally {
                if (!finished) {
                    // إلغاء أو انشغال: لا تبقى المادة «جارٍ» للأبد
                    withContext(NonCancellable) {
                        store.update(next.id) {
                            if (it.status == MaterialStatus.PROCESSING) it.copy(status = MaterialStatus.PENDING) else it
                        }
                    }
                }
            }

            if (stopAll) return
            delay(PAUSE_AFTER_OK_MS)
        }
    }
}
