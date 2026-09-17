package com.example.aichat

import android.app.Application
import com.example.aichat.data.local.ChatDatabase
import com.example.aichat.repository.WebPlatformRepository
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings

class AichatApp : Application() {

    /** GeckoRuntime — يُنشأ مرة واحدة طوال عمر التطبيق */
    lateinit var geckoRuntime: GeckoRuntime
        private set

    /** مصدر واحد للحقيقة لمنصات الويب */
    lateinit var webPlatformRepository: WebPlatformRepository
        private set

    override fun onCreate() {
        super.onCreate()

        // ── GeckoRuntime ──────────────────────────────────────────────────────
        val settings = GeckoRuntimeSettings.Builder()
            .javaScriptEnabled(true)
            .build()
        geckoRuntime = GeckoRuntime.create(this, settings)

        // ── Database + Repository ─────────────────────────────────────────────
        val db = ChatDatabase.getDatabase(this)
        webPlatformRepository = WebPlatformRepository(db.webPlatformDao())
    }
}
