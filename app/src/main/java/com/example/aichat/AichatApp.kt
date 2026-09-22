package com.example.aichat

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.example.aichat.data.local.ChatDatabase
import com.example.aichat.repository.WebPlatformRepository
import com.example.aichat.web.SessionContextBridge
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.WebExtension

class AichatApp : Application() {

    @Volatile var geckoRuntime: GeckoRuntime? = null
        private set

    @Volatile var aiChatExtension: WebExtension? = null
        private set

    @Volatile var contextBridgeExtension: WebExtension? = null
        private set

    // Bridge النشط حالياً
    @Volatile var activeBridge: Any? = null

    @Volatile private var captureFlag = false

    var onAiResponseCaptured:  ((domain: String, text: String) -> Unit)? = null
    var onManualCaptureResult: ((success: Boolean, text: String, debug: JSONObject?) -> Unit)? = null

    lateinit var webPlatformRepository: WebPlatformRepository
        private set

    fun showToast(msg: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
        }
    }

    // ══════════════════════════════════════════════════════
    // المسار 1 — aicapture — onMessage + GeckoResult
    // ══════════════════════════════════════════════════════

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
            Log.d("AichatApp", "📩 [aicapture] $type")

            return when (type) {
                "CHECK_CAPTURE" -> {
                    val flag    = captureFlag
                    captureFlag = false
                    if (flag) showToast("📡 جاري الاستخراج...")
                    GeckoResult.fromValue(JSONObject().put("capture", flag))
                }
                "AI_RESPONSE" -> {
                    handleAutoResponse(json)
                    null
                }
                "CAPTURE_RESULT" -> {
                    handleCaptureResult(json)
                    null
                }
                else -> null
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
                        ext.setMessageDelegate(messageDelegateAiCapture, "browser")
                        Log.d("AichatApp", "✅ aicapture loaded")
                        showToast("✅ aicapture جاهزة")
                    }
                },
                { e -> Log.e("AichatApp", "❌ aicapture: ${e?.message}") }
            )
    }

    // ══════════════════════════════════════════════════════
    // المسار 2 — contextbridge — Port مباشر
    // ══════════════════════════════════════════════════════

    // delegate ثابت — مسجل مبكراً — يوجّه للـ bridge النشط
    private val bridgeMessageDelegate = object : WebExtension.MessageDelegate {

        override fun onConnect(newPort: WebExtension.Port) {
            Log.d("AichatApp", "🔌 Bridge onConnect: ${newPort.name} — ${newPort.sender?.url}")

            if (newPort.name != SessionContextBridge.NATIVE_APP) {
                newPort.disconnect()
                return
            }

            val host = runCatching {
                java.net.URI(newPort.sender?.url ?: "").host ?: ""
            }.getOrDefault("")

            Log.d("AichatApp", "🔌 host=$host")

            val bridge = activeBridge as? SessionContextBridge
            if (bridge != null) {
                bridge.acceptPort(newPort, host)
            } else {
                Log.w("AichatApp", "⚠️ No active bridge")
                newPort.disconnect()
            }
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
                        // ✅ تسجيل فوري قبل أي شيء
                        Handler(Looper.getMainLooper()).post {
                            ext.setMessageDelegate(
                                bridgeMessageDelegate,
                                SessionContextBridge.NATIVE_APP
                            )
                            Log.d("AichatApp", "✅ bridgeMessageDelegate registered")
                            showToast("✅ Bridge جاهز")
                        }
                    }
                },
                { e ->
                    Log.e("AichatApp", "❌ contextbridge: ${e?.message}")
                    showToast("❌ Bridge: ${e?.message}")
                }
            )
    }

    // ══════════════════════════════════════════════════════
    // مشترك
    // ══════════════════════════════════════════════════════

    override fun onCreate() {
        super.onCreate()
        try {
            val db = ChatDatabase.getDatabase(this)
            webPlatformRepository = WebPlatformRepository(db.webPlatformDao())
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
                loadAiCaptureExtension(rt)
                loadContextBridgeExtension(rt)
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
        onAiResponseCaptured?.invoke(domain, text)
    }

    private fun handleCaptureResult(json: JSONObject) {
        val success = json.optBoolean("success", false)
        val text    = json.optString("text")
        val debug   = json.optJSONObject("debug")
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
