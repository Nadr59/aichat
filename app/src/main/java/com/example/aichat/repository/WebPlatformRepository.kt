package com.example.aichat.repository

import android.util.Patterns
import com.example.aichat.data.local.DefaultWebPlatforms
import com.example.aichat.data.local.WebPlatformDao
import com.example.aichat.data.model.WebEngine
import com.example.aichat.data.model.WebPlatform
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class WebPlatformRepository(private val dao: WebPlatformDao) {

    /** كل المنصات (مفعّلة + معطّلة) */
    val allPlatforms: Flow<List<WebPlatform>> = dao.observeAll()

    /** المنصات المفعّلة فقط — للعرض في ConversationsScreen */
    val enabledPlatforms: Flow<List<WebPlatform>> = dao.observeEnabled()

    /**
     * يُستدعى مرة عند بدء التطبيق.
     * IGNORE في الـ DAO يضمن عدم الكتابة فوق البيانات الموجودة.
     */
    suspend fun seedDefaults() {
        dao.insertAll(DefaultWebPlatforms.all)
    }

    suspend fun getById(id: String): WebPlatform? = dao.getById(id)

    /**
     * إضافة منصة مخصصة مع التحقق من المدخلات.
     * @return Result<WebPlatform> — نجاح أو فشل مع رسالة واضحة
     */
    suspend fun add(
        name: String,
        url: String,
        requiresAccount: Boolean,
        engine: WebEngine,
        emoji: String = "🌐"
    ): Result<WebPlatform> = runCatching {

        val cleanName = name.trim()
        val cleanUrl  = normalizeUrl(url)

        require(cleanName.isNotBlank()) { "اسم المنصة مطلوب" }
        require(Patterns.WEB_URL.matcher(cleanUrl).matches()) { "الرابط غير صالح: $cleanUrl" }

        WebPlatform(
            id              = UUID.randomUUID().toString(),
            name            = cleanName,
            url             = cleanUrl,
            iconEmoji       = emoji.ifBlank { "🌐" },
            requiresAccount = requiresAccount,
            preferredEngine = engine.name,
            isBuiltIn       = false,
            sortOrder       = 100
        ).also { dao.upsert(it) }
    }

    /** تحديث أي حقل في منصة موجودة */
    suspend fun update(platform: WebPlatform) = dao.upsert(platform)

    /** تفعيل / تعطيل منصة */
    suspend fun setEnabled(platform: WebPlatform, enabled: Boolean) =
        dao.upsert(platform.copy(isEnabled = enabled))

    /**
     * حذف منصة مخصصة فقط.
     * @return false إذا كانت افتراضية (يجب تعطيلها بدل الحذف)
     */
    suspend fun delete(id: String): Boolean = dao.deleteCustom(id) > 0

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun normalizeUrl(raw: String): String {
        val t = raw.trim()
        return if (t.startsWith("http://") || t.startsWith("https://")) t
        else "https://$t"
    }
}
