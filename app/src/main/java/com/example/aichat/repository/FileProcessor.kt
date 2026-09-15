package com.example.aichat.repository

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * معالج الملفات (TXT, PDF)
 */
class FileProcessor(
    private val context: Context
) {

    init {
        // تهيئة PDFBox
        PDFBoxResourceLoader.init(context)
    }

    /**
     * قراءة ملف TXT
     */
    fun readTextFile(uri: Uri): String {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: return ""

            val reader = BufferedReader(InputStreamReader(inputStream))
            val content = reader.readText()
            reader.close()

            content.trim()
        } catch (e: Exception) {
            android.util.Log.e("FileProcessor", "Error reading TXT: ${e.message}")
            throw Exception("فشل قراءة ملف TXT: ${e.message}")
        }
    }

    /**
     * قراءة ملف PDF
     */
    fun readPdfFile(uri: Uri): String {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: return ""

            val document = PDDocument.load(inputStream)
            val stripper = PDFTextStripper()
            
            val content = stripper.getText(document)
            
            document.close()
            inputStream.close()

            content.trim()
        } catch (e: Exception) {
            android.util.Log.e("FileProcessor", "Error reading PDF: ${e.message}")
            throw Exception("فشل قراءة ملف PDF: ${e.message}")
        }
    }

    /**
     * قراءة ملف تلقائياً حسب النوع
     */
    fun readFile(uri: Uri): String {
        val mimeType = context.contentResolver.getType(uri) ?: ""
        val fileName = uri.lastPathSegment ?: ""

        return when {
            mimeType == "text/plain" || fileName.endsWith(".txt", ignoreCase = true) -> {
                android.util.Log.d("FileProcessor", "Reading TXT file")
                readTextFile(uri)
            }

            mimeType == "application/pdf" || fileName.endsWith(".pdf", ignoreCase = true) -> {
                android.util.Log.d("FileProcessor", "Reading PDF file")
                readPdfFile(uri)
            }

            else -> {
                throw Exception("نوع الملف غير مدعوم: $mimeType")
            }
        }
    }

    /**
     * تقسيم النص الطويل إلى أجزاء (chunks)
     */
    fun chunkText(
        text: String,
        maxChunkSize: Int = 1000,
        overlap: Int = 100
    ): List<String> {
        
        if (text.length <= maxChunkSize) {
            return listOf(text)
        }

        val chunks = mutableListOf<String>()
        var start = 0

        while (start < text.length) {
            val end = minOf(start + maxChunkSize, text.length)
            val chunk = text.substring(start, end)
            
            chunks.add(chunk.trim())
            
            start += (maxChunkSize - overlap)
        }

        return chunks
    }
}
