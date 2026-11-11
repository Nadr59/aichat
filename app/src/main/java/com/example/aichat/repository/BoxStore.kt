package com.example.aichat.repository

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

private const val BOXES_FILE = "boxes.json"
private const val BOXES_SAVE_DELAY_MS = 600L
private const val BOX_MAX_IDS = 100

const val BOX_NAME_MAX = 40
const val BOX_QUESTION_MAX = 300
const val MAX_BOXES = 30

/**
 * علبة صغيرة: اسم + سؤال اختياري + أرقام مواد (لا نسخ منها).
 * المعرّفات الميتة (مواد حُذفت) تُرشَح عند العرض، فلا يفسد شيء لو قُتل التطبيق بين الحذف والتنظيف.
 */
data class MaterialBox(
    val id: String,
    val name: String,
    val question: String,
    val materialIds: List<String>,
    val createdAt: Long
) {
    fun toJson(): JSONObject {
        val arr = JSONArray()
        for (m in materialIds) arr.put(m)
        return JSONObject()
            .put("id", id)
            .put("name", name)
            .put("question", question)
            .put("materialIds", arr)
            .put("createdAt", createdAt)
    }

    companion object {
        fun fromJson(o: JSONObject): MaterialBox? {
            val id = o.optString("id", "").trim()
            val name = o.optString("name", "").trim()
            if (id.isEmpty() || name.isEmpty()) return null

            val ids = ArrayList<String>()
            val arr = o.optJSONArray("materialIds")
            if (arr != null) {
                for (i in 0 until minOf(arr.length(), BOX_MAX_IDS)) {
                    val s = arr.optString(i, "").trim()
                    if (s.isNotEmpty()) ids.add(s)
                }
            }

            return MaterialBox(
                id = id,
                name = name.take(BOX_NAME_MAX),
                question = o.optString("question", "").trim().take(BOX_QUESTION_MAX),
                materialIds = ids,
                createdAt = o.optLong("createdAt", System.currentTimeMillis())
            )
        }
    }
}

/**
 * مخزن العلب (boxes.json). نفس نمط MaterialStore:
 * كل الدوال العامة على الخيط الرئيسي، والقرص في خيط خلفي واحد، والحفظ مؤجَّل.
 */
class BoxStore(
    private val dir: File,
    private val log: (String) -> Unit = {}
) {

    val boxes: SnapshotStateList<MaterialBox> = mutableStateListOf()

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val file = File(dir, BOXES_FILE)

    private var loaded = false
    private var dirty = false

    private val saveRunnable = Runnable { saveNow() }

    fun load() {
        io.execute {
            val list = readFile()
            main.post { mergeLoaded(list) }
        }
    }

    /** يعيد العلبة الجديدة، أو null إن كان الاسم فارغًا أو بلغت العلب الحد الأقصى. */
    fun add(name: String, question: String, materialIds: List<String>): MaterialBox? {
        val n = name.trim().take(BOX_NAME_MAX)
        if (n.isEmpty() || boxes.size >= MAX_BOXES) return null

        val box = MaterialBox(
            id = UUID.randomUUID().toString(),
            name = n,
            question = question.trim().take(BOX_QUESTION_MAX),
            materialIds = materialIds.distinct().take(BOX_MAX_IDS),
            createdAt = System.currentTimeMillis()
        )
        boxes.add(0, box)
        scheduleSave()
        return box
    }

    /** تعديل الاسم والسؤال. يعيد false إن لم توجد العلبة أو كان الاسم فارغًا. */
    fun update(id: String, name: String, question: String): Boolean {
        val n = name.trim().take(BOX_NAME_MAX)
        if (n.isEmpty()) return false
        val idx = boxes.indexOfFirst { it.id == id }
        if (idx < 0) return false
        boxes[idx] = boxes[idx].copy(
            name = n,
            question = question.trim().take(BOX_QUESTION_MAX)
        )
        scheduleSave()
        return true
    }

    /** حذف العلبة فقط، لا مواد. */
    fun remove(id: String) {
        val idx = boxes.indexOfFirst { it.id == id }
        if (idx < 0) return
        boxes.removeAt(idx)
        scheduleSave()
    }

    /** يزيل من كل العلب أي معرّف مادة ليس في alive (تحسين للحجم؛ الضمان الفعلي الرشط عند العرض). */
    fun pruneDead(alive: Set<String>) {
        var changed = false
        for (i in boxes.indices) {
            val b = boxes[i]
            val kept = b.materialIds.filter { it in alive }
            if (kept.size != b.materialIds.size) {
                boxes[i] = b.copy(materialIds = kept)
                changed = true
            }
        }
        if (changed) scheduleSave()
    }

    // ------------------------------------------------------------

    private fun mergeLoaded(list: List<MaterialBox>) {
        val have = HashSet<String>()
        for (b in boxes) have.add(b.id)
        for (b in list) {
            if (b.id !in have) boxes.add(b)
        }
        while (boxes.size > MAX_BOXES) boxes.removeAt(boxes.lastIndex)
        loaded = true
        if (dirty) scheduleSave()
    }

    private fun scheduleSave() {
        dirty = true
        main.removeCallbacks(saveRunnable)
        main.postDelayed(saveRunnable, BOXES_SAVE_DELAY_MS)
    }

    private fun saveNow() {
        // قبل اكتمال التحميل لا نكتب أبدًا
        if (!loaded) return
        dirty = false
        val snapshot = boxes.toList()
        io.execute { writeFile(snapshot) }
    }

    private fun readFile(): List<MaterialBox> {
        if (!file.exists()) {
            log("🗂️ boxes: no file yet")
            return emptyList()
        }
        return try {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            val arr = root.optJSONArray("boxes") ?: JSONArray()
            val out = ArrayList<MaterialBox>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val b = MaterialBox.fromJson(o) ?: continue
                out.add(b)
            }
            log("🗂️ boxes loaded: ${out.size}")
            out
        } catch (e: Exception) {
            val bad = File(dir, "$BOXES_FILE.corrupt")
            runCatching { file.renameTo(bad) }
            log("❌ boxes.json unreadable, moved to ${bad.name}: ${e.message ?: e}")
            emptyList()
        }
    }

    private fun writeFile(list: List<MaterialBox>) {
        try {
            val arr = JSONArray()
            for (b in list) arr.put(b.toJson())
            val root = JSONObject().put("version", 1).put("boxes", arr)

            val tmp = File(dir, "$BOXES_FILE.tmp")
            tmp.writeText(root.toString(), Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                file.writeText(root.toString(), Charsets.UTF_8)
                tmp.delete()
            }
            log("💾 boxes saved: ${list.size} boxes")
        } catch (e: Exception) {
            log("❌ boxes save failed: ${e.message ?: e}")
        }
    }
}
