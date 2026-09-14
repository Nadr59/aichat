package com.example.aichat.repository

import com.example.aichat.data.model.MemoryItem
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * محلل دلالي للذاكرة باستخدام Qwen2.5:1.5b المحلي عبر Ollama
 */
object SemanticMemoryAnalyzer {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * تحليل الذكريات دلالياً باستخدام Qwen
     * 
     * @param query سؤال المستخدم
     * @param candidates الذكريات المرشحة من البحث المحلي
     * @param ollamaUrl عنوان Ollama (افتراضي: localhost)
     * @return قائمة الذكريات مُعاد ترتيبها حسب الصلة الدلالية
     */
    suspend fun analyzeMemories(
    query: String,
    candidates: List<MemoryItem>,
    ollamaUrl: String = "http://127.0.0.1:11434"
): List<MemoryItem> {

    if (candidates.isEmpty()) return emptyList()
    if (query.isBlank()) return candidates

    return try {
        withTimeout(2000L) { // ✅ 2 ثانية فقط
            performSemanticAnalysis(query, candidates, ollamaUrl)
        }
    } catch (e: Exception) {
        candidates
    }
    }
    

    private suspend fun performSemanticAnalysis(
        query: String,
        candidates: List<MemoryItem>,
        ollamaUrl: String
    ): List<MemoryItem> {

        // بناء الـ prompt
        val prompt = buildAnalysisPrompt(query, candidates)

        // إرسال الطلب لـ Qwen
        val response = callQwen(prompt, ollamaUrl)

        // تحليل الرد
        val rankedIds = parseQwenResponse(response, candidates.size)

        // إعادة ترتيب الذكريات
        return reorderMemories(candidates, rankedIds)
    }

    /**
     * بناء الـ prompt لـ Qwen
     */
    private fun buildAnalysisPrompt(
        query: String,
        candidates: List<MemoryItem>
    ): String {
        val memoriesText = candidates.mapIndexed { index, memory ->
            val preview = memory.content.take(200)
            "${index + 1}. [${memory.category}] $preview..."
        }.joinToString("\n")

        return """
أنت محلل ذكي للذاكرة. مهمتك تحديد أي الذكريات أكثر صلة بسؤال المستخدم.

السؤال: "$query"

الذكريات المتاحة:
$memoriesText

قيّم كل ذاكرة من 1 إلى ${candidates.size} حسب مدى صلتها بالسؤال.

أجب فقط بقائمة الأرقام مرتبة من الأكثر صلة إلى الأقل، مفصولة بفواصل.
مثال: 3,1,5,2,4

الإجابة:
        """.trimIndent()
    }

    /**
     * استدعاء Qwen عبر Ollama API
     */
    private fun callQwen(
        prompt: String,
        ollamaUrl: String
    ): String {
        val json = JSONObject().apply {
            put("model", "qwen2.5:1.5b")
            put("prompt", prompt)
            put("stream", false)
            put("options", JSONObject().apply {
                put("temperature", 0.1) // منخفضة للدقة
                put("num_predict", 50)  // نحتاج إجابة قصيرة فقط
            })
        }

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = json.toString().toRequestBody(mediaType)

        val request = Request.Builder()
            .url("$ollamaUrl/api/generate")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("Ollama request failed: ${response.code}")
            }

            val responseBody = response.body?.string()
                ?: throw Exception("Empty response from Ollama")

            val jsonResponse = JSONObject(responseBody)
            return jsonResponse.optString("response", "")
        }
    }

    /**
     * تحليل رد Qwen واستخراج ترتيب الأرقام
     */
    private fun parseQwenResponse(
        response: String,
        maxCount: Int
    ): List<Int> {
        // استخراج الأرقام من النص
        val numbers = response
            .replace(Regex("[^0-9,]"), "") // إبقاء الأرقام والفواصل فقط
            .split(",")
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it in 1..maxCount } // أرقام صالحة فقط
            .distinct() // إزالة المكررات

        // إذا كان الرد غير كامل، نضيف الباقي بالترتيب
        val remaining = (1..maxCount).filter { it !in numbers }
        
        return numbers + remaining
    }

    /**
     * إعادة ترتيب الذكريات حسب تقييم Qwen
     */
    private fun reorderMemories(
        candidates: List<MemoryItem>,
        rankedIds: List<Int>
    ): List<MemoryItem> {
        val result = mutableListOf<MemoryItem>()
        
        rankedIds.forEach { id ->
            val index = id - 1 // الأرقام تبدأ من 1
            if (index in candidates.indices) {
                result.add(candidates[index])
            }
        }
        
        return result
    }
}
