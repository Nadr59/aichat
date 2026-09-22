package com.example.aichat

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.example.aichat.data.local.ChatDatabase
import com.example.aichat.data.local.SystemPrompt
import com.example.aichat.data.model.MemoryItem
import com.example.aichat.repository.MemoryContextBuilder
import com.example.aichat.repository.WebPlatformRepository
import com.example.aichat.web.SessionContextBridge
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.WebExtension

class AichatApp : Application() {

    // ── المتغيرات ─────────────────────────────────────────────────────

    @Volatile var geckoRuntime: GeckoRuntime? = null
        private set

    // المسار 1 — aicapture (التقاط → ذاكرة)
    @Volatile var aiChatExtension: WebExtension? = null
        private set

    // المسار 2 — contextbridge (سياق → منصة)
    @Volatile var contextBridgeExtension: WebExtension? = null
        private set

    @Volatile private var captureFlag = false
    @Volatile private var pendingBridgeDelegate: WebExtension.MessageDelegate? = null

    var onAiResponseCaptured:  ((domain: String, text: String) -> Unit)? = null
    var onManualCaptureResult: ((success: Boolean, text: String, debug: JSONObject?) -> Unit)? = null

    lateinit var webPlatformRepository: WebPlatformRepository
        private set

    // ── Toast ─────────────────────────────────────────────────────────

    fun showToast(msg: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // المسار 1 — aicapture
    // نفس النسخة القديمة تماماً — onMessage + GeckoResult
    // ══════════════════════════════════════════════════════════════════

    fun triggerCapture() {
        captureFlag = true
        Log.d("AichatApp", "📌 captureFlag = true")
        showToast("🔍 جاري البحث في الصفحة...")
    }

    private val messageDelegateAiCapture = object : WebExtension.MessageDelegate {

        override fun onMessage(
            nativeApp: String,
            message:   Any,
            sender:    WebExtension.MessageSender
        ): GeckoResult<Any>? {

            val json = parseMessage(message) ?: return null
            val type = json.optString("type")
            Log.d("AichatApp", "📩 [aicapture] type=$type")

            return when (type) {

                "CHECK_CAPTURE" -> {
                    val flag    = captureFlag
                    captureFlag = false
                    Log.d("AichatApp", "✅ CHECK_CAPTURE flag=$flag")
                    if (flag) showToast("📡 جاري الاستخراج...")
                    GeckoResult.fromValue(
                        JSONObject().put("capture", flag)
                    )
                }

                "AI_RESPONSE" -> {
                    handleAutoResponse(json)
                    null
                }

                "CAPTURE_RESULT" -> {
                    handleCaptureResult(json)
                    null
                }

                else -> {
                    Log.w("AichatApp", "⚠️ [aicapture] unknown: $type")
                    null
                }
            }
        }
    }

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
                        // ✅ نفس النسخة القديمة — onMessage فقط
                        ext.setMessageDelegate(messageDelegateAiCapture, "browser")
                        Log.d("AichatApp", "✅ aicapture loaded")
                        showToast("✅ aicapture جاهزة")
                    } else {
                        Log.e("AichatApp", "❌ aicapture = null")
                    }
                },
                { e ->
                    Log.e("AichatApp", "❌ aicapture: ${e?.message}")
                    showToast("❌ aicapture: ${e?.message}")
                }
            )
    }

    // ══════════════════════════════════════════════════════════════════
    // المسار 2 — contextbridge
    // Port مستقل — لا علاقة له بـ aicapture
    // ══════════════════════════════════════════════════════════════════

    fun registerBridgeDelegate(delegate: WebExtension.MessageDelegate) {
        pendingBridgeDelegate = delegate
        val ext = contextBridgeExtension
        if (ext != null) {
            Handler(Looper.getMainLooper()).post {
                try {
                    ext.setMessageDelegate(delegate, SessionContextBridge.NATIVE_APP)
                    Log.d("AichatApp", "✅ BridgeDelegate registered")
                } catch (e: Exception) {
                    Log.e("AichatApp", "❌ registerBridgeDelegate: ${e.message}")
                }
            }
        } else {
            Log.w("AichatApp", "⚠️ contextBridgeExtension null — queued")
        }
    }

    private fun loadContextBridgeExtension(runtime: GeckoRuntime) {
        runtime.webExtensionController
            .ensureBuiltIn(
                "resource://android/assets/contextbridge/",
                "session-memory-bridge@example.local"
            )
            .accept(
                { ext ->
                    if (ext != null) {
                        contextBridgeExtension = ext
                        Log.d("AichatApp", "✅ contextbridge loaded")
                        showToast("✅ Bridge جاهز")

                        // سجّل delegate إذا كان منتظراً
                        val delegate = pendingBridgeDelegate
                        if (delegate != null) {
                            Handler(Looper.getMainLooper()).post {
                                try {
                                    ext.setMessageDelegate(
                                        delegate,
                                        SessionContextBridge.NATIVE_APP
                                    )
                                    Log.d("AichatApp", "✅ Queued delegate registered")
                                } catch (e: Exception) {
                                    Log.e("AichatApp", "❌ queued delegate: ${e.message}")
                                }
                            }
                        }
                    } else {
                        Log.e("AichatApp", "❌ contextbridge = null")
                    }
                },
                { e ->
                    Log.e("AichatApp", "❌ contextbridge: ${e?.message}")
                    showToast("❌ Bridge: ${e?.message}")
                }
            )
    }

    // ══════════════════════════════════════════════════════════════════
    // مشترك
    // ══════════════════════════════════════════════════════════════════

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

    @Synchronized
    fun getOrCreateGeckoRuntime(): GeckoRuntime? {
        if (geckoRuntime != null) return geckoRuntime
        return try {
            val settings = GeckoRuntimeSettings.Builder()
                .aboutConfigEnabled(false)
                .build()
            GeckoRuntime.create(applicationContext, settings).also { rt ->
                geckoRuntime = rt
                loadAiCaptureExtension(rt)      // المسار 1
                loadContextBridgeExtension(rt)  // المسار 2
                Log.d("AichatApp", "✅ GeckoRuntime created")
            }
        } catch (e: Exception) {
            Log.e("AichatApp", "❌ GeckoRuntime: ${e.message}")
            showToast("❌ فشل تهيئة GeckoRuntime")
            null
        }
    }

    private fun handleAutoResponse(json: JSONObject) {
        val text   = json.optString("text")
        val domain = json.optString("domain", "unknown")
        if (text.length < 80) return
        Log.d("AichatApp", "📨 Auto: ${text.take(60)}…")
        onAiResponseCaptured?.invoke(domain, text)
    }

    private fun handleCaptureResult(json: JSONObject) {
        val success = json.optBoolean("success", false)
        val text    = json.optString("text")
        val debug   = json.optJSONObject("debug")
        Log.d("AichatApp",
            if (success) "🧠 OK: ${text.take(60)}…"
            else "⚠️ Failed: $debug"
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
        Log.e("AichatApp", "❌ parseMessage: ${e.message}")
        null
    }
}
