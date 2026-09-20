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

    // ✅ Port من content.js
    @Volatile private var capturePort: WebExtension.Port? = null

    var onAiResponseCaptured:  ((domain: String, text: String) -> Unit)? = null
    var onManualCaptureResult: ((success: Boolean, text: String, debug: JSONObject?) -> Unit)? = null

    lateinit var webPlatformRepository: WebPlatformRepository
        private set

    // ── PortDelegate ──────────────────────────────────────────────────

    private val portDelegate = object : WebExtension.PortDelegate {
        override fun onPortMessage(message: Any, port: WebExtension.Port) {
            val json = parseMessage(message) ?: return
            val type = json.optString("type")
            Log.d("AichatApp", "📩 Port message: $type")
            when (type) {
                "CAPTURE_RESULT" -> handleCaptureResult(json)
                else             -> Log.d("AichatApp", "⏭ Unknown port msg: $type")
            }
        }

        override fun onDisconnect(port: WebExtension.Port) {
            Log.d("AichatApp", "⚠️ Port disconnected")
            if (capturePort === port) capturePort = null
        }
    }

    // ── MessageDelegate ───────────────────────────────────────────────

    private val messageDelegate = object : WebExtension.MessageDelegate {

        // ✅ content.js اتصل عبر connectNative('browser')
        override fun onConnect(port: WebExtension.Port) {
            Log.d("AichatApp", "✅ Port connected: ${port.name}")
            capturePort = port
            port.setDelegate(portDelegate)
        }

        // ✅ رسائل sendNativeMessage
        override fun onMessage(
            nativeApp: String,
            message:   Any,
            sender:    WebExtension.MessageSender
        ): GeckoResult<Any>? {
            val json = parseMessage(message) ?: return null
            val type = json.optString("type")
            Log.d("AichatApp", "📩 onMessage: $type")
            when (type) {
                "AI_RESPONSE" -> handleAutoResponse(json)
                else          -> Log.d("AichatApp", "⏭ Unknown msg: $type")
            }
            return null
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

    // ── ✅ الترتيب الصحيح: ensureBuiltIn → delegate → loadUri ─────────

    fun prepareSession(session: GeckoSession, url: String) {
        val runtime = geckoRuntime ?: return

        runtime.webExtensionController
            .ensureBuiltIn(
                "resource://android/assets/aicapture/",
                "aicapture@aichat.example.com"
            )
            .accept(
                { ext ->
                    if (ext != null) {
                        aiChatExtension = ext

                        // ✅ 1. سجّل delegate على الـ session
                        session.webExtensionController
                            .setMessageDelegate(ext, messageDelegate, "browser")
                        Log.d("AichatApp", "✅ Session delegate set")

                        // ✅ 2. افتح الجلسة وحمّل الصفحة بعد التسجيل
                        if (!session.isOpen) {
                            session.open(runtime)
                        }
                        session.loadUri(url)
                        Log.d("AichatApp", "✅ Session opened → $url")
                    }
                },
                { e -> Log.e("AichatApp", "❌ Extension: ${e?.message}") }
            )
    }

    // ── زر 🧠 ────────────────────────────────────────────────────────

    fun requestManualCapture() {
        val port = capturePort
        if (port == null) {
            Log.w("AichatApp", "⚠️ No port")
            Handler(Looper.getMainLooper()).post {
                onManualCaptureResult?.invoke(false, "", null)
            }
            return
        }
        try {
            // ✅ JSONObject فقط — لا Map
            port.postMessage(JSONObject().put("type", "CAPTURE"))
            Log.d("AichatApp", "📤 CAPTURE sent via port")
        } catch (e: Exception) {
            Log.e("AichatApp", "❌ postMessage: ${e.message}")
            Handler(Looper.getMainLooper()).post {
                onManualCaptureResult?.invoke(false, "", null)
            }
        }
    }

    // ── تحميل Extension عند بدء التشغيل ─────────────────────────────

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
                        Log.d("AichatApp", "✅ Extension ready: ${ext.id}")
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
            if (success) "🧠 OK: ${text.take(60)}…"
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
