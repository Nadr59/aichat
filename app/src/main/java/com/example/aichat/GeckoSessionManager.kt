package com.example.aichat

import org.mozilla.geckoview.GeckoSession

/**
 * يحتفظ بجلسة GeckoSession مستقلة لكل منصة.
 *
 * مهم:
 * - لا يتم إغلاق الجلسة عند مغادرة شاشة المنصة.
 * - GeckoView يتم فصله فقط بواسطة releaseSession().
 * - عند العودة للمنصة يتم إرفاق نفس الجلسة مرة أخرى.
 *
 * النتيجة:
 * نفس الصفحة + سجل التصفح + حالة تسجيل الدخول + موضع الصفحة
 * تبقى محفوظة أثناء التنقل داخل التطبيق.
 */
object GeckoSessionManager {

    private val sessions = mutableMapOf<String, GeckoSession>()

    @Synchronized
    fun getOrCreate(platformId: String): GeckoSession {
        return sessions.getOrPut(platformId) {
            GeckoSession()
        }
    }

    @Synchronized
    fun hasSession(platformId: String): Boolean {
        return sessions.containsKey(platformId)
    }

    /**
     * يستخدم فقط إذا أردنا حذف منصة نهائيًا.
     * لا تستدعَ عند مجرد مغادرة الشاشة.
     */
    @Synchronized
    fun close(platformId: String) {
        sessions.remove(platformId)?.let { session ->
            runCatching {
                if (session.isOpen) {
                    session.close()
                }
            }
        }
    }

    /**
     * يستخدم عند إغلاق التطبيق/تنظيف جميع الجلسات إذا احتجنا ذلك لاحقًا.
     */
    @Synchronized
    fun closeAll() {
        sessions.values.forEach { session ->
            runCatching {
                if (session.isOpen) {
                    session.close()
                }
            }
        }
        sessions.clear()
    }
}
