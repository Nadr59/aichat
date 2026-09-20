package com.example.aichat

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.aichat.data.local.ChatDatabase
import com.example.aichat.repository.WebPlatformRepository
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension

class AichatApp : Application() {

    @Volatile var geckoRuntime:    GeckoRuntime?  = null
        private set

    @Volatile var aiChatExtension: WebExtension?  = null
        private set

    @Volatile private var pendingCapture: GeckoResult<Any>? = null

    // ✅ قائمة الـ sessions التي تنتظر تسجيل الـ delegate
    private val pendingSessions = mutableListOf<GeckoSession>()

    var onAiResponseCaptured:  ((domain: String, text: String) -> Unit)? = null
    var onManualCaptureResult: ((success: Boolean, text: String, debug: JSONObject?) -> Unit)? = null

    lateinit var webPlatformRepository: WebPlatformRepository
        private set

    // ── Delegate مشترك ────────────────────────────────────────────────

    private val messageDelegate = object : WebExtension.MessageDelegate {

        override fun onMessage(
            nativeApp: String,
            message:   Any,
            sender:    WebExtension.MessageSender
        ): GeckoResult<Any>? {

            val json = parseMessage(message) ?: return null
            val type = json.optString("type")

            Log.d("AichatApp", "📩 onMessage: $type")

            return when (type) {

                "WAIT_CAPTURE" -> {
                    pendingCapture?.complete(
                        JSONObject().put("capture", false)
                    )
                    val result = GeckoResult<Any>()
                    pendingCapture = result
                    Log.d("AichatApp", "⏳ WAIT_CAPTURE registered")
                    result
                }

                "CAPTURE_RESULT" -> {
                    handleCaptureResult(json)
                    null
                }

                "AI_RESPONSE" -> {
                    handleAutoResponse(json)
                    null
                }

                else -> {
                    Log.d("AichatApp", "⏭ Unknown: $type")
                    null
                }
            }
        }
    }

    // ── onCreate ──────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        try {
            val db = ChatDatabase.getDatabase(this)
            webPlatformRepository = WebPlatformRepository(db.webPlatformDao())
            Log.d("AichatApp", "✅ Database ready")
        } catch (e: Exception) {
            Log.e("AichatApp", "❌ Database: ${e.message}", e)
            throw e
        }
    }

    // ── GeckoRuntime ──────────────────────────────────────────────────

    @Synchronized
    fun getOrCreateGeckoRuntime(): GeckoRuntime? {
        if (geckoRuntime != null) return geckoRuntime
        return try {
            val settings = GeckoRuntimeSettings.Builder()
                .aboutConfigEnabled(false)
                .build()
            GeckoRuntime.create(applicationContext, settings).also { rt ->
                geckoRuntime = rt
                loadAiCaptureExtension(rt)
                Log.d("AichatApp", "✅ GeckoRuntime created")
            }
        } catch (e: Exception) {
            Log.e("AichatApp", "❌ GeckoRuntime: ${e.message}")
            null
        }
    }

    // ── زر 🧠 ────────────────────────────────────────────────────────

    fun requestManualCapture() {
        val pending = pendingCapture
        if (pending == null) {
            Log.w("AichatApp", "⚠️ No page waiting")
            Handler(Looper.getMainLooper()).post {
                onManualCaptureResult?.invoke(false, "", null)
            }
            return
        }
        pendingCapture = null
        pending.complete(JSONObject().put("capture", true))
        Log.d("AichatApp", "✅ Capture triggered")
    }

    // ── ✅ registerSession — يتعامل مع التأخر ─────────────────────────

    fun registerSession(session: GeckoSession) {
        val ext = aiChatExtension

        if (ext != null) {
            // ✅ Extension جاهزة — سجّل فوراً
            doRegisterSession(session, ext)
        } else {
            // ✅ Extension لم تنته بعد — احفظ للتسجيل لاحقاً
            Log.w("AichatApp", "⏳ Extension not ready — queuing session")
            synchronized(pendingSessions) {
                pendingSessions.add(session)
            }
        }
    }

    private fun doRegisterSession(session: GeckoSession, ext: WebExtension) {
        session.webExtensionController
            .setMessageDelegate(ext, messageDelegate, "browser")
        Log.d("AichatApp", "✅ Session registered")
    }

    // ── Extension ─────────────────────────────────────────────────────

    private fun loadAiCaptureExtension(runtime: GeckoRuntime) {
        runtime.webExtensionController
            .ensureBuiltIn(
                "resource://android/assets/aicapture/",
                "aicapture@aichat.example.com"
            )
            .accept(
                { ext ->
                    if (ext != null) {
                        aiChatExtension = ext
                        ext.setMessageDelegate(messageDelegate, "browser")
                        Log.d("AichatApp", "✅ Extension: ${ext.id}")

                        // ✅ سجّل كل الـ sessions التي كانت تنتظر
                        synchronized(pendingSessions) {
                            pendingSessions.forEach { session ->
                                doRegisterSession(session, ext)
                            }
                            pendingSessions.clear()
                        }
                    }
                },
                { e -> Log.e("AichatApp", "❌ Extension: ${e?.message}") }
            )
    }

    // ── معالجة الرسائل ────────────────────────────────────────────────

    private fun handleAutoResponse(json: JSONObject) {
        val text   = json.optString("text")
        val domain = json.optString("domain", "unknown")
        if (text.length < 80) return
        Log.d("AichatApp", "📨 Auto from $domain: ${text.take(60)}…")
        onAiResponseCaptured?.invoke(domain, text)
    }

    private fun handleCaptureResult(json: JSONObject) {
        val success = json.optBoolean("success", false)
        val text    = json.optString("text")
        val domain  = json.optString("domain", "unknown")
        val debug   = json.optJSONObject("debug")

        Log.d("AichatApp",
            if (success) "🧠 OK from $domain: ${text.take(60)}…"
            else         "⚠️ Failed — debug: $debug"
        )

        Handler(Looper.getMainLooper()).post {
            onManualCaptureResult?.invoke(success, text, debug)
        }
    }

    private fun parseMessage(message: Any): JSONObject? = try {
        when (message) {
            is JSONObject -> message
            is Map<*, *>  -> JSONObject(message as Map<*, *>)
            else          -> null
        }
    } catch (e: Exception) { null }
}
