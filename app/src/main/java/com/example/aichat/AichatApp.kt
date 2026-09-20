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
import org.mozilla.geckoview.WebExtension

class AichatApp : Application() {

    @Volatile var geckoRuntime:    GeckoRuntime? = null
        private set

    @Volatile var aiChatExtension: WebExtension? = null
        private set

    @Volatile private var captureFlag: Boolean = false

    var onAiResponseCaptured:  ((domain: String, text: String) -> Unit)? = null
    var onManualCaptureResult: ((success: Boolean, text: String, debug: JSONObject?) -> Unit)? = null

    lateinit var webPlatformRepository: WebPlatformRepository
        private set

    // ── يُستدعى من زر 🧠 ─────────────────────────────────────────────

    fun triggerCapture() {
        captureFlag = true
        Log.d("AichatApp", "📌 Capture flag set")
    }

    // ── MessageDelegate ───────────────────────────────────────────────

    private val messageDelegate = object : WebExtension.MessageDelegate {

        override fun onMessage(
            nativeApp: String,
            message:   Any,
            sender:    WebExtension.MessageSender
        ): GeckoResult<Any>? {

            val json = parseMessage(message) ?: run {
                Log.e("AichatApp", "❌ parseMessage failed — raw: $message")
                return null
            }

            val type = json.optString("type")
            Log.d("AichatApp", "📩 onMessage type=$type sender=${sender.url}")

            return when (type) {

                "CHECK_CAPTURE" -> {
                    val flag    = captureFlag
                    captureFlag = false
                    Log.d("AichatApp", "✅ CHECK_CAPTURE → flag=$flag")
                    GeckoResult.fromValue(
                        JSONObject().put("capture", flag)
                    )
                }

                "AI_RESPONSE" -> {
                    Log.d("AichatApp", "📨 AI_RESPONSE received")
                    handleAutoResponse(json)
                    null
                }

                "CAPTURE_RESULT" -> {
                    Log.d("AichatApp", "🧠 CAPTURE_RESULT received")
                    handleCaptureResult(json)
                    null
                }

                else -> {
                    Log.w("AichatApp", "⚠️ unknown type: $type")
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
                        Log.d("AichatApp", "✅ Extension loaded: ${ext.id}")
                    } else {
                        Log.e("AichatApp", "❌ Extension is null")
                    }
                },
                { e ->
                    Log.e("AichatApp", "❌ Extension error: ${e?.message}")
                }
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
    } catch (e: Exception) {
        Log.e("AichatApp", "❌ parseMessage exception: ${e.message}")
        null
    }
}
