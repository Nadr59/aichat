package com.example.aichat.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * معالج الملفات (TXT, PDF)
 */
class FileProcessor(
    private val context: Context
) {

    init {
        // تهيئة PDFBox - مع معالجة الخطأ إذا فشلت التهيئة
        try {
            PDFBoxResourceLoader.init(context)
        } catch (e: Exception) {
            android.util.Log.e("FileProcessor", "⚠️ PDFBox init failed: ${e.message}")
        }
    }

    // ============================================================
    // الحصول على اسم الملف الحقيقي من URI
    // مُصحَّح: بدل uri.lastPathSegment الذي يُرجع "1234" مش "file.pdf"
    // ============================================================

    private fun getFileName(uri: Uri): String {
        // أولاً: نحاول من ContentResolver (الأصح دائماً)
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) {
                    val name = cursor.getString(nameIndex)
                    if (!name.isNullOrBlank()) {
                        android.util.Log.d("FileProcessor", "📄 اسم الملف: $name")
                        return name
                    }
                }
            }
        }

        // ثانياً: fallback إلى lastPathSegment إذا فشل الأول
        val fallback = uri.lastPathSegment ?: ""
        android.util.Log.w("FileProcessor", "⚠️ استخدام fallback لاسم الملف: $fallback")
        return fallback
    }

    // ============================================================
    // قراءة ملف TXT
    // مُصحَّح: إضافة suspend + withContext(Dispatchers.IO) + use صحيح
    // ============================================================

    suspend fun readTextFile(uri: Uri): String = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    reader.readText().trim()
                }
            } ?: run {
                android.util.Log.w("FileProcessor", "⚠️ InputStream فارغ لملف TXT")
                ""
            }
        } catch (e: Exception) {
            android.util.Log.e("FileProcessor", "❌ Error reading TXT: ${e.message}")
            throw Exception("فشل قراءة ملف TXT: ${e.message}")
        }
    }

    // ============================================================
    // قراءة ملف PDF
    // مُصحَّح: إضافة suspend + withContext(Dispatchers.IO) + use صحيح
    // الخطأ القديم: إذا حدث Exception قبل inputStream.close() لن يُغلق
    // ============================================================

    suspend fun readPdfFile(uri: Uri): String = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                PDDocument.load(inputStream).use { document ->
                    PDFTextStripper().getText(document).trim()
                }
            } ?: run {
                android.util.Log.w("FileProcessor", "⚠️ InputStream فارغ لملف PDF")
                ""
            }
        } catch (e: Exception) {
            android.util.Log.e("FileProcessor", "❌ Error reading PDF: ${e.message}")
            throw Exception("فشل قراءة ملف PDF: ${e.message}")
        }
    }

    // ============================================================
    // قراءة ملف تلقائياً حسب النوع
    // مُصحَّح: استخدام getFileName() بدل uri.lastPathSegment
    // مُصحَّح: إضافة suspend لأن readTextFile و readPdfFile أصبحتا suspend
    // ============================================================

    suspend fun readFile(uri: Uri): String {
        val mimeType = context.contentResolver.getType(uri) ?: ""
        val fileName = getFileName(uri)

        android.util.Log.d("FileProcessor", "📂 MIME Type: $mimeType")
        android.util.Log.d("FileProcessor", "📄 File Name: $fileName")

        return when {
            mimeType == "text/plain" || fileName.endsWith(".txt", ignoreCase = true) -> {
                android.util.Log.d("FileProcessor", "📖 قراءة ملف TXT")
                readTextFile(uri)
            }

            mimeType == "application/pdf" || fileName.endsWith(".pdf", ignoreCase = true) -> {
                android.util.Log.d("FileProcessor", "📖 قراءة ملف PDF")
                readPdfFile(uri)
            }

            else -> {
                android.util.Log.e("FileProcessor", "❌ نوع غير مدعوم: $mimeType | $fileName")
                throw Exception("نوع الملف غير مدعوم: $fileName ($mimeType)")
            }
        }
    }

    // ============================================================
    // تقسيم النص إلى أجزاء (Chunks)
    // مُصحَّح: إضافة require لمنع حلقة لا نهائية
    // مُحسَّن: التقسيم على حدود الجمل بدل القطع الحرفي
    // ============================================================

    fun chunkText(
        text: String,
        maxChunkSize: Int = 800,
        overlap: Int = 100
    ): List<String> {

        // مُصحَّح: منع حلقة لا نهائية
        // إذا overlap >= maxChunkSize → start لن يتقدم أبداً → حلقة لا نهائية
        require(overlap >= 0) {
            "overlap ($overlap) يجب أن يكون موجباً"
        }
        require(overlap < maxChunkSize) {
            "overlap ($overlap) يجب أن يكون أقل من maxChunkSize ($maxChunkSize)"
        }
        require(maxChunkSize > 0) {
            "maxChunkSize ($maxChunkSize) يجب أن يكون أكبر من صفر"
        }

        if (text.isBlank()) return emptyList()
        if (text.length <= maxChunkSize) return listOf(text.trim())

        // مُحسَّن: تقسيم على حدود الجمل
        val sentences = text
            .split(Regex("(?<=[.!?؟\n])\\s*"))
            .map { it.trim() }
            .filter { it.isNotBlank() }

        // إذا كان النص بدون نقاط واضحة: ارجع للتقسيم الحرفي
        if (sentences.size <= 1) {
            return chunkTextByCharacter(text, maxChunkSize, overlap)
        }

        val chunks = mutableListOf<String>()
        val currentChunk = StringBuilder()

        for (sentence in sentences) {
            val potentialLength = currentChunk.length +
                (if (currentChunk.isNotEmpty()) 1 else 0) +
                sentence.length

            if (potentialLength > maxChunkSize && currentChunk.isNotEmpty()) {
                // احفظ الـ chunk الحالي
                chunks.add(currentChunk.toString().trim())

                // Overlap: ابدأ الـ chunk الجديد بآخر جزء من السابق
                currentChunk.clear()
                if (overlap > 0 && chunks.last().length > overlap) {
                    val overlapText = chunks.last().takeLast(overlap)
                    currentChunk.append(overlapText)
                    currentChunk.append(" ")
                }
            }

            if (currentChunk.isNotEmpty()) currentChunk.append(" ")
            currentChunk.append(sentence)

            // إذا جملة واحدة أكبر من maxChunkSize: قسّمها حرفياً
            if (currentChunk.length > maxChunkSize) {
                val subChunks = chunkTextByCharacter(currentChunk.toString(), maxChunkSize, overlap)
                subChunks.dropLast(1).forEach { chunks.add(it) }
                currentChunk.clear()
                currentChunk.append(subChunks.last())
            }
        }

        // أضف ما تبقى
        if (currentChunk.isNotEmpty()) {
            chunks.add(currentChunk.toString().trim())
        }

        android.util.Log.d(
            "FileProcessor",
            "✂️ تم التقسيم إلى ${chunks.size} أجزاء (sentence-based)"
        )

        return chunks.filter { it.isNotBlank() }
    }

    // ============================================================
    // تقسيم حرفي (fallback للنصوص بدون علامات ترقيم)
    // ============================================================

    private fun chunkTextByCharacter(
        text: String,
        maxChunkSize: Int,
        overlap: Int
    ): List<String> {
        val chunks = mutableListOf<String>()
        var start = 0

        while (start < text.length) {
            val end = minOf(start + maxChunkSize, text.length)
            val chunk = text.substring(start, end).trim()

            if (chunk.isNotBlank()) {
                chunks.add(chunk)
            }

            start += (maxChunkSize - overlap)
        }

        android.util.Log.d(
            "FileProcessor",
            "✂️ تم التقسيم إلى ${chunks.size} أجزاء (character-based)"
        )

        return chunks
    }
}
