package com.example.aichat

import android.app.Application
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings

class AichatApp : Application() {

    // ✅ GeckoRuntime واحد لكل التطبيق
    // السبب: GeckoRuntime مورد ثقيل ولا يسمح بأكثر من نسخة واحدة
    // إذا أنشأنا نسختين → crash فوري
    lateinit var geckoRuntime: GeckoRuntime
        private set

    override fun onCreate() {
        super.onCreate()

        // تهيئة GeckoRuntime مرة واحدة عند بدء التطبيق
        val runtimeSettings = GeckoRuntimeSettings.Builder()
            .aboutConfigEnabled(false)
            .build()

        geckoRuntime = GeckoRuntime.create(
            applicationContext,
            runtimeSettings
        )
    }
}
