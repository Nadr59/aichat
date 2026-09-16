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

class EmbeddingService(
    private val geminiApiKey: String
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // ============================================================
    // ثوابت النموذج
    // ============================================================

    companion object {
        const val CURRENT_MODEL = "text-embedding-004"
        const val CURRENT_DIMENSIONS = 768
    }

    // ============================================================
    // استخراج Embedding - مُصحَّح: إضافة withContext(Dispatchers.IO)
    // ============================================================

    suspend fun getEmbedding(text: String): List<Double> = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext emptyList()
        if (geminiApiKey.isBlank()) {
            throw Exception("Gemini API key is required")
        }

        return@withContext try {
            getGeminiEmbedding(text)
        } catch (e: Exception) {
            android.util.Log.e("EmbeddingService", "❌ فشل الحصول على Embedding: ${e.message}")
            emptyList()
        }
    }

    // ============================================================
    // Batch Embeddings - طلب واحد لعدة نصوص (توفير 90% من الوقت)
    // ============================================================

    suspend fun getBatchEmbeddings(texts: List<String>): List<List<Double>> = withContext(Dispatchers.IO) {
        if (texts.isEmpty()) return@withContext emptyList()
        if (geminiApiKey.isBlank()) {
            throw Exception("Gemini API key is required")
        }

        return@withContext try {
            getGeminiBatchEmbeddings(texts)
        } catch (e: Exception) {
            android.util.Log.e("EmbeddingService", "❌ فشل Batch Embedding: ${e.message}")
            // في حالة الفشل: ارجع قائمة فارغة بنفس العدد
            texts.map { emptyList() }
        }
    }

    // ============================================================
    // الاستدعاءات الخاصة بـ Gemini API
    // ============================================================

    // مُصحَّح: نقل API key إلى Header بدل URL
    private fun getGeminiEmbedding(text: String): List<Double> {
        val json = JSONObject().apply {
            put("model", "models/$CURRENT_MODEL")
            put("content", JSONObject().apply {
                put("parts", JSONArray().apply {
                    put(JSONObject().apply {
                        put("text", text)
                    })
                })
            })
        }

        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$CURRENT_MODEL:embedContent")
            .addHeader("x-goog-api-key", geminiApiKey)
            .addHeader("Content-Type", "application/json")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("Gemini Embedding failed: ${response.code} - ${response.body?.string()}")
            }

            val body = response.body?.string()
                ?: throw Exception("Empty response from Gemini")

            val jsonResponse = JSONObject(body)

            val valuesArray = jsonResponse
                .getJSONObject("embedding")
                .getJSONArray("values")

            return (0 until valuesArray.length()).map {
                valuesArray.getDouble(it)
            }
        }
    }

    // Batch: طلب واحد لكل النصوص
    private fun getGeminiBatchEmbeddings(texts: List<String>): List<List<Double>> {
        val requestsArray = JSONArray().apply {
            texts.forEach { text ->
                put(JSONObject().apply {
                    put("model", "models/$CURRENT_MODEL")
                    put("content", JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", text)
                            })
                        })
                    })
                })
            }
        }

        val json = JSONObject().apply {
            put("requests", requestsArray)
        }

        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$CURRENT_MODEL:batchEmbedContents")
            .addHeader("x-goog-api-key", geminiApiKey)
            .addHeader("Content-Type", "application/json")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("Gemini Batch Embedding failed: ${response.code} - ${response.body?.string()}")
            }

            val body = response.body?.string()
                ?: throw Exception("Empty response from Gemini Batch")

            val jsonResponse = JSONObject(body)
            val embeddingsArray = jsonResponse.getJSONArray("embeddings")

            return (0 until embeddingsArray.length()).map { i ->
                val valuesArray = embeddingsArray
                    .getJSONObject(i)
                    .getJSONArray("values")

                (0 until valuesArray.length()).map {
                    valuesArray.getDouble(it)
                }
            }
        }
    }

    // ============================================================
    // ضغط Vector (Float) - توفير 50% من المساحة
    // ============================================================

    fun compressToFloat(vector: List<Double>): List<Float> {
        return vector.map { it.toFloat() }
    }

    fun decompressFromFloat(compressed: List<Float>): List<Double> {
        return compressed.map { it.toDouble() }
    }

    // ============================================================
    // ضغط Vector (Quantization إلى Int16) - توفير 75% من المساحة
    // ============================================================

    fun quantizeToInt16(vector: List<Double>): List<Short> {
        return vector.map { value ->
            val scaled = value * 32767.0
            scaled.toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    fun dequantizeFromInt16(quantized: List<Short>): List<Double> {
        return quantized.map { value ->
            value.toDouble() / 32767.0
        }
    }

    // ============================================================
    // حساب التشابه - مُحسَّن: إضافة withContext(Dispatchers.Default)
    // ============================================================

    // للاستدعاء من coroutine (أفضل)
    suspend fun cosineSimilaritySuspend(vec1: List<Double>, vec2: List<Double>): Double =
        withContext(Dispatchers.Default) {
            cosineSimilarity(vec1, vec2)
        }

    // للاستدعاء المباشر (متوافق مع الكود القديم)
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
        } else {
            0.0
        }
    }

    // ============================================================
    // تحويل إلى/من String
    // ============================================================

    fun vectorToString(vector: List<Double>, compress: Boolean = true): String {
        return if (compress) {
            val floats = compressToFloat(vector)
            JSONArray(floats).toString()
        } else {
            JSONArray(vector).toString()
        }
    }

    fun vectorToStringQuantized(vector: List<Double>): String {
        val quantized = quantizeToInt16(vector)
        return JSONArray(quantized).toString()
    }

    fun stringToVector(str: String, compressed: Boolean = true): List<Double> {
        if (str.isBlank()) return emptyList()

        return try {
            val array = JSONArray(str)

            if (compressed) {
                val floats = (0 until array.length()).map {
                    array.getDouble(it).toFloat()
                }
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
            val shorts = (0 until array.length()).map {
                array.getInt(it).toShort()
            }
            dequantizeFromInt16(shorts)
        } catch (e: Exception) {
            android.util.Log.e("EmbeddingService", "❌ فشل تحويل Quantized إلى Vector: ${e.message}")
            emptyList()
        }
    }
}
