package com.example.aichat

import org.mozilla.geckoview.GeckoSession
import java.util.concurrent.ConcurrentHashMap

/**
 * يحتفظ بجلسات GeckoSession خارج دورة حياة Compose.
 *
 * الهدف:
 * - كل منصة لها GeckoSession مستقلة.
 * - مغادرة شاشة المنصة لا تغلق الجلسة.
 * - العودة إلى المنصة تعيد استخدام نفس الجلسة.
 * - لا يتم تحميل url من جديد إذا كانت الجلسة مفتوحة.
 */
object GeckoSessionManager {

    private val sessions =
        ConcurrentHashMap<String, GeckoSession>()

    @Synchronized
    fun getOrCreate(key: String): GeckoSession {
        return sessions[key]
            ?: GeckoSession().also {
                sessions[key] = it
            }
    }

    fun get(key: String): GeckoSession? {
        return sessions[key]
    }

    /**
     * إغلاق جلسة منصة محددة عند الحاجة الصريحة فقط.
     */
    @Synchronized
    fun close(key: String) {
        sessions.remove(key)?.let { session ->
            runCatching {
                session.close()
            }
        }
    }

    /**
     * إغلاق جميع الجلسات.
     *
     * لا يتم استدعاؤها عند مغادرة GeckoTestScreen.
     */
    @Synchronized
    fun closeAll() {
        sessions.values.forEach { session ->
            runCatching {
                session.close()
            }
        }

        sessions.clear()
    }

    fun activeCount(): Int {
        return sessions.size
    }
}
