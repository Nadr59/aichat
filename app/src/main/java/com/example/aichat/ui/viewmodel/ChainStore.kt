package com.example.aichat.ui.viewmodel

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** الحد الأقصى لعدد السلاسل المحفوظة. */
const val MAX_SAVED_CHAINS = 30

/** الحد الأقصى لطول اسم السلسلة المحفوظة. */
const val MAX_SAVED_CHAIN_NAME_CHARS = 40

/** سلسلة وكلاء محفوظة باسم. */
data class SavedChain(
    val id: String,
    val name: String,
    val steps: List<ChainStepSpec>,
    val updatedAt: Long
)

/**
 * تخزين السلاسل المسمّاة في ملف JSON داخل مجلد التطبيق.
 * لا يلمس قاعدة البيانات ولا الإعدادات.
 */
class ChainStore(context: Context) {

    private val file = File(context.applicationContext.filesDir, "agent_chains.json")
    private val corruptBackup = File(context.applicationContext.filesDir, "agent_chains.corrupt.json")
    private val lock = Any()

    suspend fun loadAll(): List<SavedChain> = withContext(Dispatchers.IO) {
        synchronized(lock) { readLocked() }
    }

    /**
     * يحفظ سلسلة باسم. الاسم المكرر (دون حساسية لحالة الأحرف) يستبدل القديم.
     * يعيد القائمة المحدّثة. يرمي IllegalArgumentException/IllegalStateException برسالة عربية.
     */
    suspend fun save(name: String, steps: List<ChainStepSpec>): List<SavedChain> =
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                val cleanName = name.trim().take(MAX_SAVED_CHAIN_NAME_CHARS)
                if (cleanName.isBlank()) throw IllegalArgumentException("اكتب اسمًا للسلسلة.")
                if (steps.isEmpty()) throw IllegalArgumentException("السلسلة فارغة.")

                val current = readLocked().toMutableList()
                val existingIndex = current.indexOfFirst { it.name.equals(cleanName, ignoreCase = true) }

                if (existingIndex < 0 && current.size >= MAX_SAVED_CHAINS) {
                    throw IllegalStateException("بلغت الحد الأقصى ($MAX_SAVED_CHAINS سلسلة). احذف سلسلة أولًا.")
                }

                val entry = SavedChain(
                    id = if (existingIndex >= 0) current[existingIndex].id else UUID.randomUUID().toString(),
                    name = cleanName,
                    steps = steps.take(MAX_AGENT_CHAIN_STEPS),
                    updatedAt = System.currentTimeMillis()
                )

                if (existingIndex >= 0) current[existingIndex] = entry else current.add(entry)

                val sorted = current.sortedByDescending { it.updatedAt }
                writeLocked(sorted)
                sorted
            }
        }

    suspend fun delete(id: String): List<SavedChain> = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val updated = readLocked().filterNot { it.id == id }
            writeLocked(updated)
            updated
        }
    }

    // ---------- داخلي (يُستدعى داخل synchronized فقط) ----------

    private fun readLocked(): List<SavedChain> {
        if (!file.exists()) return emptyList()
        return try {
            val root = JSONArray(file.readText(Charsets.UTF_8))
            val result = mutableListOf<SavedChain>()
            for (i in 0 until root.length()) {
                val obj = root.getJSONObject(i)
                val stepsJson = obj.getJSONArray("steps")
                val steps = mutableListOf<ChainStepSpec>()
                for (j in 0 until stepsJson.length()) {
                    val s = stepsJson.getJSONObject(j)
                    val mode = runCatching { ChainInputMode.valueOf(s.optString("mode")) }
                        .getOrDefault(ChainInputMode.PREVIOUS_ONLY)
                    steps.add(
                        ChainStepSpec(
                            agentId = s.getString("agentId"),
                            inputMode = mode,
                            extraInstruction = s.optString("extra", "")
                        )
                    )
                }
                result.add(
                    SavedChain(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        steps = steps.take(MAX_AGENT_CHAIN_STEPS),
                        updatedAt = obj.optLong("updatedAt", 0L)
                    )
                )
            }
            result.sortedByDescending { it.updatedAt }
        } catch (e: Exception) {
            // ملف تالف: نحتفظ بنسخة منه ولا نفقده، ونبدأ بقائمة فارغة.
            runCatching { file.copyTo(corruptBackup, overwrite = true) }
            emptyList()
        }
    }

    private fun writeLocked(chains: List<SavedChain>) {
        val root = JSONArray()
        chains.forEach { chain ->
            val stepsJson = JSONArray()
            chain.steps.forEach { step ->
                stepsJson.put(
                    JSONObject()
                        .put("agentId", step.agentId)
                        .put("mode", step.inputMode.name)
                        .put("extra", step.extraInstruction)
                )
            }
            root.put(
                JSONObject()
                    .put("id", chain.id)
                    .put("name", chain.name)
                    .put("updatedAt", chain.updatedAt)
                    .put("steps", stepsJson)
            )
        }

        val text = root.toString()
        val tmp = File(file.parentFile, "agent_chains.json.tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.writeText(text, Charsets.UTF_8)
            tmp.delete()
        }
    }
}
