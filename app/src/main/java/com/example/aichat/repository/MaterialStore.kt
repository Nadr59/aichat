package com.example.aichat.repository

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

private const val FILE_NAME = "materials.json"
private const val MAX_MATERIALS = 100
private const val MAX_TEXT = 8000

// capture.js يقتطع إلى 8000 ثم يضيف " …" (حتى 8002 حرفًا)، فلا نقتطع ثانية قبل هذا الهامش
private const val TEXT_SLACK = 8
private const val MAX_LINKS = 40
private const val SAVE_DELAY_MS = 600L

data class MaterialLink(val text: String, val href: String)

data class MaterialItem(
    val id: String,
    val url: String,
    val host: String,
    val title: String,
    val author: String,
    val date: String,
    val lang: String,
    val text: String,
    val links: List<MaterialLink>,
    val method: String,
    val quality: String,
    val reason: String,
    val truncated: Boolean,
    val capturedAt: Long
) {
    fun toJson(): JSONObject {
        val arr = JSONArray()
        for (l in links) {
            arr.put(JSONObject().put("text", l.text).put("href", l.href))
        }
        return JSONObject()
            .put("id", id)
            .put("url", url)
            .put("host", host)
            .put("title", title)
            .put("author", author)
            .put("date", date)
            .put("lang", lang)
            .put("text", text)
            .put("links", arr)
            .put("method", method)
            .put("quality", quality)
            .put("reason", reason)
            .put("truncated", truncated)
            .put("capturedAt", capturedAt)
    }

    companion object {
        /** يقرأ رسالة MATERIAL_CAPTURE أو عنصرًا محفوظًا (الحقول نفسها). null إن غاب id أو text. */
        fun fromJson(o: JSONObject): MaterialItem? {
            val id = o.optString("id", "").trim()
            var text = o.optString("text", "").trim()
            if (id.isEmpty() || text.isEmpty()) return null

            var truncated = o.optBoolean("truncated", false)
            if (text.length > MAX_TEXT + TEXT_SLACK) {
                text = text.take(MAX_TEXT).trim() + " …"
                truncated = true
            }

            val links = ArrayList<MaterialLink>()
            val arr = o.optJSONArray("links")
            if (arr != null) {
                for (i in 0 until minOf(arr.length(), MAX_LINKS)) {
                    val l = arr.optJSONObject(i) ?: continue
                    val href = l.optString("href", "").trim()
                    if (href.isEmpty()) continue
                    links.add(MaterialLink(l.optString("text", ""), href))
                }
            }

            return MaterialItem(
                id = id,
                url = o.optString("url", ""),
                host = o.optString("host", ""),
                title = o.optString("title", ""),
                author = o.optString("author", ""),
                date = o.optString("date", ""),
                lang = o.optString("lang", ""),
                text = text,
                links = links,
                method = o.optString("method", ""),
                quality = o.optString("quality", ""),
                reason = o.optString("reason", ""),
                truncated = truncated,
                capturedAt = o.optLong("capturedAt", System.currentTimeMillis())
            )
        }
    }
}

/**
 * علبة المواد: قائمة في الذاكرة (الأحدث أولًا) تُحفظ في materials.json.
 *
 * قاعدة الخيوط: كل الدوال العامة تُستدعى من الخيط الرئيسي فقط.
 * القراءة والكتابة على القرص تجريان في خيط خلفي واحد.
 * الحفظ مؤجَّل (debounce) حتى لا نكتب الملف عند كل مادة في دفعة كبيرة.
 */
class MaterialStore(
    private val dir: File,
    private val log: (String) -> Unit = {}
) {

    val items: SnapshotStateList<MaterialItem> = mutableStateListOf()

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val file = File(dir, FILE_NAME)

    private var loaded = false
    private var dirty = false

    // لحفظ ترتيب الصفحة داخل الدفعة الواحدة (أول عنصر في الصفحة يظهر أولًا)
    private var batchId = ""
    private var batchPos = 0

    private val saveRunnable = Runnable { saveNow() }

    fun load() {
        io.execute {
            val list = readFile()
            main.post { mergeLoaded(list) }
        }
    }

    /** يعيد true إن كانت المادة جديدة، وfalse إن حدّثت مادة بالمعرّف نفسه. */
    fun upsert(item: MaterialItem, batch: String): Boolean {
        if (batch.isEmpty() || batch != batchId) {
            batchId = batch
            batchPos = 0
        }

        val idx = items.indexOfFirst { it.id == item.id }
        if (idx >= 0) {
            items.removeAt(idx)
            if (idx < batchPos) batchPos--
        }

        items.add(minOf(batchPos, items.size), item)
        batchPos++

        trimToLimit()
        scheduleSave()
        return idx < 0
    }

    fun remove(id: String) {
        val idx = items.indexOfFirst { it.id == id }
        if (idx < 0) return
        items.removeAt(idx)
        batchId = ""
        batchPos = 0
        scheduleSave()
    }

    fun clear() {
        items.clear()
        batchId = ""
        batchPos = 0
        scheduleSave()
    }

    // ------------------------------------------------------------

    private fun mergeLoaded(list: List<MaterialItem>) {
        // ما التُقط قبل اكتمال التحميل أحدث، فيبقى في المقدمة وتُلحق المحفوظات بعده
        val have = HashSet<String>()
        for (m in items) have.add(m.id)
        for (m in list) {
            if (m.id !in have) items.add(m)
        }
        trimToLimit()
        loaded = true
        if (dirty) scheduleSave()
    }

    private fun trimToLimit() {
        while (items.size > MAX_MATERIALS) items.removeAt(items.lastIndex)
    }

    private fun scheduleSave() {
        dirty = true
        main.removeCallbacks(saveRunnable)
        main.postDelayed(saveRunnable, SAVE_DELAY_MS)
    }

    private fun saveNow() {
        // قبل اكتمال التحميل لا نكتب أبدًا (لئلا نستبدل الملف القديم بقائمة ناقصة)
        if (!loaded) return
        dirty = false
        val snapshot = items.toList()
        io.execute { writeFile(snapshot) }
    }

    private fun readFile(): List<MaterialItem> {
        if (!file.exists()) {
            log("📚 materials: no file yet")
            return emptyList()
        }
        return try {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            val arr = root.optJSONArray("materials") ?: JSONArray()
            val out = ArrayList<MaterialItem>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                MaterialItem.fromJson(o)?.let { out.add(it) }
            }
            log("📚 materials loaded: ${out.size}")
            out
        } catch (e: Exception) {
            // نحفظ الملف التالف جانبًا بدل أن تستبدله الكتابة التالية
            val bad = File(dir, "$FILE_NAME.corrupt")
            runCatching { file.renameTo(bad) }
            log("❌ materials.json unreadable, moved to ${bad.name}: ${e.message ?: e}")
            emptyList()
        }
    }

    private fun writeFile(list: List<MaterialItem>) {
        try {
            val arr = JSONArray()
            for (m in list) arr.put(m.toJson())
            val root = JSONObject().put("version", 1).put("materials", arr)

            val tmp = File(dir, "$FILE_NAME.tmp")
            tmp.writeText(root.toString(), Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                file.writeText(root.toString(), Charsets.UTF_8)
                tmp.delete()
            }
            log("💾 materials saved: ${list.size} items, ${file.length() / 1024} KB")
        } catch (e: Exception) {
            log("❌ materials save failed: ${e.message ?: e}")
        }
    }
}
