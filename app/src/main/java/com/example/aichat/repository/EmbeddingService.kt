package com.example.aichat.repository

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/**
 * خدمة Embeddings باستخدام Gemini (مجاني 100%)
 */
class EmbeddingService(
    private val geminiApiKey: String
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * تحويل النص إلى Vector باستخدام Gemini
     */
    suspend fun getEmbedding(text: String): List<Double> {
        if (text.isBlank()) return emptyList()
        if (geminiApiKey.isBlank()) {
            throw Exception("Gemini API key is required")
        }

        return try {
            getGeminiEmbedding(text)
        } catch (e: Exception) {
            // في حالة الفشل، نرجع قائمة فارغة
            emptyList()
        }
    }

    /**
     * Gemini Embeddings API
     */
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

    /**
     * حساب التشابه بين vectorين (Cosine Similarity)
     */
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

    /**
     * تحويل Vector إلى String للحفظ في قاعدة البيانات
     */
    fun vectorToString(vector: List<Double>): String {
        return JSONArray(vector).toString()
    }

    /**
     * تحويل String إلى Vector
     */
    fun stringToVector(str: String): List<Double> {
        if (str.isBlank()) return emptyList()
        
        return try {
            val array = JSONArray(str)
            (0 until array.length()).map { array.getDouble(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
