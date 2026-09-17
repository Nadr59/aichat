package com.example.aichat

import android.app.Application
import com.example.aichat.data.local.ChatDatabase
import com.example.aichat.repository.WebPlatformRepository
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings

class AichatApp : Application() {

    /**
     * GeckoRuntime — يُنشأ مرة واحدة.
     * lateinit (ليس nullable) لأن GeckoTestScreen يعتمد عليه.
     */
    lateinit var geckoRuntime: GeckoRuntime
        private set

    lateinit var webPlatformRepository: WebPlatformRepository
        private set

    override fun onCreate() {
        super.onCreate()

        // ── GeckoRuntime ──────────────────────────────────────────────────
        geckoRuntime = GeckoRuntime.create(
            this,
            GeckoRuntimeSettings.Builder()
                .javaScriptEnabled(true)
                .build()
        )

        // ── Database + Repository ─────────────────────────────────────────
        val db = ChatDatabase.getDatabase(this)
        webPlatformRepository = WebPlatformRepository(db.webPlatformDao())
    }
}
