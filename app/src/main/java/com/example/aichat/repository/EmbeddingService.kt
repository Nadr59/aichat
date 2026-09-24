package com.example.aichat.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/**
 * apiKeyProvider: دالة بدل قيمة مجمَّدة — تضمن قراءة المفتاح الفعلي
 * من الإعدادات في كل نداء شبكة، حتى لو عدّله المستخدم بعد إنشاء
 * هذا الكائن، بلا حاجة لإعادة تشغيل التطبيق.
 */
class EmbeddingService(
    private val apiKeyProvider: () -> String
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    companion object {
        const val CURRENT_MODEL = "text-embedding-004"
        const val CURRENT_DIMENSIONS = 768

        // ============================================================
        // دوال حسابية خالصة — لا تحتاج مفتاح API ولا اتصال شبكة
        // ✅ في companion: لا حاجة لإنشاء instance (مع OkHttpClient)
        //    فقط لتحويل/ضغط/مقارنة متجهات محلياً
        // ============================================================

        fun compressToFloat(vector: List<Double>): List<Float> =
            vector.map { it.toFloat() }

        fun decompressFromFloat(compressed: List<Float>): List<Double> =
            compressed.map { it.toDouble() }

        fun quantizeToInt16(vector: List<Double>): List<Short> =
            vector.map { value ->
                (value * 32767.0).toInt().coerceIn(-32768, 32767).toShort()
            }

        fun dequantizeFromInt16(quantized: List<Short>): List<Double> =
            quantized.map { it.toDouble() / 32767.0 }

        fun cosineSimilarity(vec1: List<Double>, vec2: List<Double>): Double {
            if (vec1.isEmpty() || vec2.isEmpty()) return 0.0
            if (vec1.size != vec2.size) {
                android.util.Log.w(
                    "EmbeddingService",
                    "⚠️ Dimension mismatch: vec1=${vec1.size}, vec2=${vec2.size}"
                )
                return 0.0
            }

            var dotProduct = 0.0
            var norm1 = 0.0
            var norm2 = 0.0

            for (i in vec1.indices) {
                dotProduct += vec1[i] * vec2[i]
                norm1 += vec1[i] * vec1[i]
                norm2 += vec2[i] * vec2[i]
            }

            return if (norm1 > 0 && norm2 > 0) {
                dotProduct / (sqrt(norm1) * sqrt(norm2))
            } else 0.0
        }

        suspend fun cosineSimilaritySuspend(vec1: List<Double>, vec2: List<Double>): Double =
            withContext(Dispatchers.Default) { cosineSimilarity(vec1, vec2) }

        fun vectorToString(vector: List<Double>, compress: Boolean = true): String =
            if (compress) JSONArray(compressToFloat(vector)).toString()
            else JSONArray(vector).toString()

        fun vectorToStringQuantized(vector: List<Double>): String =
            JSONArray(quantizeToInt16(vector)).toString()

        fun stringToVector(str: String, compressed: Boolean = true): List<Double> {
            if (str.isBlank()) return emptyList()
            return try {
                val array = JSONArray(str)
                if (compressed) {
                    val floats = (0 until array.length()).map { array.getDouble(it).toFloat() }
                    decompressFromFloat(floats)
                } else {
                    (0 until array.length()).map { array.getDouble(it) }
                }
            } catch (e: Exception) {
                android.util.Log.e("EmbeddingService", "❌ فشل تحويل String إلى Vector: ${e.message}")
                emptyList()
            }
        }

        fun stringToVectorQuantized(str: String): List<Double> {
            if (str.isBlank()) return emptyList()
            return try {
                val array = JSONArray(str)
                val shorts = (0 until array.length()).map { array.getInt(it).toShort() }
                dequantizeFromInt16(shorts)
            } catch (e: Exception) {
                android.util.Log.e("EmbeddingService", "❌ فشل تحويل Quantized إلى Vector: ${e.message}")
                emptyList()
            }
        }
    }

    // ============================================================
    // النداءات الشبكية — الوحيدة التي تحتاج instance + مفتاح فعلي
    // ============================================================

    suspend fun getEmbedding(text: String): List<Double> = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext emptyList()
        val apiKey = apiKeyProvider()
        if (apiKey.isBlank()) throw Exception("Gemini API key is required")

        return@withContext try {
            getGeminiEmbedding(text, apiKey)
        } catch (e: Exception) {
            android.util.Log.e("EmbeddingService", "❌ فشل الحصول على Embedding: ${e.message}")
            emptyList()
        }
    }

    suspend fun getBatchEmbeddings(texts: List<String>): List<List<Double>> = withContext(Dispatchers.IO) {
        if (texts.isEmpty()) return@withContext emptyList()
        val apiKey = apiKeyProvider()
        if (apiKey.isBlank()) throw Exception("Gemini API key is required")

        return@withContext try {
            getGeminiBatchEmbeddings(texts, apiKey)
        } catch (e: Exception) {
            android.util.Log.e("EmbeddingService", "❌ فشل Batch Embedding: ${e.message}")
            texts.map { emptyList() }
        }
    }

    private fun getGeminiEmbedding(text: String, apiKey: String): List<Double> {
        val json = JSONObject().apply {
            put("model", "models/$CURRENT_MODEL")
            put("content", JSONObject().apply {
                put("parts", JSONArray().apply {
                    put(JSONObject().apply { put("text", text) })
                })
            })
        }

        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$CURRENT_MODEL:embedContent")
            .addHeader("x-goog-api-key", apiKey)
            .addHeader("Content-Type", "application/json")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("Gemini Embedding failed: ${response.code} - ${response.body?.string()}")
            }
            val body = response.body?.string() ?: throw Exception("Empty response from Gemini")
            val valuesArray = JSONObject(body).getJSONObject("embedding").getJSONArray("values")
            return (0 until valuesArray.length()).map { valuesArray.getDouble(it) }
        }
    }

    private fun getGeminiBatchEmbeddings(texts: List<String>, apiKey: String): List<List<Double>> {
        val requestsArray = JSONArray().apply {
            texts.forEach { text ->
                put(JSONObject().apply {
                    put("model", "models/$CURRENT_MODEL")
                    put("content", JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply { put("text", text) })
                        })
                    })
                })
            }
        }

        val json = JSONObject().apply { put("requests", requestsArray) }

        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$CURRENT_MODEL:batchEmbedContents")
            .addHeader("x-goog-api-key", apiKey)
            .addHeader("Content-Type", "application/json")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("Gemini Batch Embedding failed: ${response.code} - ${response.body?.string()}")
            }
            val body = response.body?.string() ?: throw Exception("Empty response from Gemini Batch")
            val embeddingsArray = JSONObject(body).getJSONArray("embeddings")
            return (0 until embeddingsArray.length()).map { i ->
                val valuesArray = embeddingsArray.getJSONObject(i).getJSONArray("values")
                (0 until valuesArray.length()).map { valuesArray.getDouble(it) }
            }
        }
    }
}
