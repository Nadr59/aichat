package com.example.aichat.repository

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
    // استخراج Embedding
    // ============================================================

    suspend fun getEmbedding(text: String): List<Double> {
        if (text.isBlank()) return emptyList()
        if (geminiApiKey.isBlank()) {
            throw Exception("Gemini API key is required")
        }

        return try {
            getGeminiEmbedding(text)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun getGeminiEmbedding(text: String): List<Double> {
        val json = JSONObject().apply {
            put("model", "models/text-embedding-004")
            put("content", JSONObject().apply {
                put("parts", JSONArray().apply {
                    put(JSONObject().apply {
                        put("text", text)
                    })
                })
            })
        }

        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/text-embedding-004:embedContent?key=$geminiApiKey")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .addHeader("Content-Type", "application/json")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("Gemini Embedding failed: ${response.code}")
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

    // ============================================================
    // ضغط Vector (Float)
    // ============================================================

    /**
     * تحويل Double → Float لتوفير 50% من المساحة
     */
    fun compressToFloat(vector: List<Double>): List<Float> {
        return vector.map { it.toFloat() }
    }

    /**
     * استرجاع من Float → Double
     */
    fun decompressFromFloat(compressed: List<Float>): List<Double> {
        return compressed.map { it.toDouble() }
    }

    // ============================================================
    // ضغط Vector (Quantization إلى Int16)
    // ============================================================

    /**
     * تحويل Double → Int16 (Short)
     * توفير 75% من المساحة
     */
    fun quantizeToInt16(vector: List<Double>): List<Short> {
        return vector.map { value ->
            // تحويل من [-1, 1] إلى [-32768, 32767]
            val scaled = value * 32767.0
            scaled.toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    /**
     * استرجاع من Int16 → Double
     */
    fun dequantizeFromInt16(quantized: List<Short>): List<Double> {
        return quantized.map { value ->
            value.toDouble() / 32767.0
        }
    }

    // ============================================================
    // حساب التشابه
    // ============================================================

    fun cosineSimilarity(vec1: List<Double>, vec2: List<Double>): Double {
        if (vec1.isEmpty() || vec2.isEmpty()) {
            return 0.0
        }

        if (vec1.size != vec2.size) {
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

    /**
     * Vector → String (مضغوط بـ Float)
     */
    fun vectorToString(vector: List<Double>, compress: Boolean = true): String {
        if (compress) {
            val floats = compressToFloat(vector)
            return JSONArray(floats).toString()
        } else {
            return JSONArray(vector).toString()
        }
    }

    /**
     * Vector → String (مضغوط بـ Int16)
     */
    fun vectorToStringQuantized(vector: List<Double>): String {
        val quantized = quantizeToInt16(vector)
        return JSONArray(quantized).toString()
    }

    /**
     * String → Vector
     */
    fun stringToVector(str: String, compressed: Boolean = true): List<Double> {
        if (str.isBlank()) return emptyList()
        
        return try {
            val array = JSONArray(str)
            
            if (compressed) {
                // Float format
                val floats = (0 until array.length()).map { 
                    array.getDouble(it).toFloat() 
                }
                decompressFromFloat(floats)
            } else {
                // Double format (قديم)
                (0 until array.length()).map { array.getDouble(it) }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * String → Vector (من Int16)
     */
    fun stringToVectorQuantized(str: String): List<Double> {
        if (str.isBlank()) return emptyList()
        
        return try {
            val array = JSONArray(str)
            val shorts = (0 until array.length()).map { 
                array.getInt(it).toShort() 
            }
            dequantizeFromInt16(shorts)
        } catch (e: Exception) {
            emptyList()
        }
    }
}
