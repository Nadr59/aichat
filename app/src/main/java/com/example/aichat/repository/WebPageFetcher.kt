package com.example.aichat.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

/**
 * جلب وتنظيف محتوى صفحات الويب
 * يستخدم Jsoup لاستخراج النص المفيد فقط
 *
 * أضف في build.gradle.kts:
 * implementation("org.jsoup:jsoup:1.17.2")
 */
class WebPageFetcher {

    // ============================================================
    // نتيجة الجلب
    // ============================================================

    data class FetchResult(
        val title: String,
        val content: String,
        val url: String,
        val wordCount: Int
    )

    // ============================================================
    // جلب وتنظيف الصفحة
    // ============================================================

    suspend fun fetchAndClean(url: String): FetchResult = withContext(Dispatchers.IO) {

        // التحقق من صحة URL
        require(url.startsWith("http://") || url.startsWith("https://")) {
            "URL يجب أن يبدأ بـ http:// أو https://"
        }

        android.util.Log.d("WebPageFetcher", "🌐 Fetching: $url")

        val doc = Jsoup.connect(url)
            .userAgent("Mozilla/5.0 (Android; Mobile; rv:109.0) Gecko/109.0 Firefox/109.0")
            .referrer("https://www.google.com")
            .timeout(15000)
            .maxBodySize(5 * 1024 * 1024) // 5 MB حد أقصى
            .get()

        val title = doc.title().trim()
        android.util.Log.d("WebPageFetcher", "📄 Title: $title")

        // إزالة العناصر غير المفيدة
        doc.select(
            "script, style, nav, header, footer, " +
            "aside, .ads, #ads, .advertisement, " +
            ".cookie-notice, .cookie-banner, " +
            ".popup, .modal, .overlay, " +
            ".sidebar, .menu, .navigation, " +
            "[class*='social'], [class*='share'], " +
            "[class*='comment'], [id*='comment'], " +
            "[class*='related'], [class*='recommended'], " +
            "[class*='subscribe'], [class*='newsletter']"
        ).remove()

        // استخراج المحتوى بالأولوية
        val contentElement = doc.selectFirst(
            "article, main, [role='main'], " +
            ".post-content, .article-content, " +
            ".entry-content, .page-content, " +
            "#content, .content, #main, .main"
        ) ?: doc.body()

        // تنظيف النص
        val rawText = contentElement?.text() ?: ""

        val cleanText = rawText
            .replace(Regex("\\s{3,}"), "\n\n")
            .replace(Regex("[\\t ]{2,}"), " ")
            .trim()

        // الاحتفاظ بالأسطر المفيدة فقط (أكثر من 20 حرف)
        val meaningfulText = cleanText
            .lines()
            .filter { line -> line.trim().length > 20 }
            .joinToString("\n")

        val wordCount = if (meaningfulText.isBlank()) 0
            else meaningfulText.split(Regex("\\s+")).size

        android.util.Log.d("WebPageFetcher", "✅ Extracted $wordCount words")

        if (meaningfulText.isBlank()) {
            throw Exception("لم يتم العثور على محتوى نصي مفيد في الصفحة")
        }

        FetchResult(
            title = title.ifBlank { url },
            content = meaningfulText,
            url = url,
            wordCount = wordCount
        )
    }

    // ============================================================
    // استخراج الروابط من صفحة
    // ============================================================

    suspend fun extractLinks(url: String): List<String> = withContext(Dispatchers.IO) {

        require(url.startsWith("http://") || url.startsWith("https://")) {
            "URL يجب أن يبدأ بـ http:// أو https://"
        }

        val doc = Jsoup.connect(url)
            .userAgent("Mozilla/5.0")
            .timeout(10000)
            .get()

        doc.select("a[href]")
            .map { it.attr("abs:href") }
            .filter { href ->
                href.startsWith("http") &&
                !href.contains("mailto:") &&
                !href.contains("javascript:") &&
                !href.contains(".pdf") &&
                !href.contains(".zip") &&
                !href.contains(".exe")
            }
            .distinct()
            .take(50)
    }
}
