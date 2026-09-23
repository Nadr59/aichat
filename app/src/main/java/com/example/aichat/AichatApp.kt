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

    @Volatile private var captureFlag    = false
    @Volatile private var contextPending = ""

    var onAiResponseCaptured:  ((domain: String, text: String) -> Unit)? = null
    var onManualCaptureResult: ((success: Boolean, text: String, debug: JSONObject?) -> Unit)? = null
    var onContextWritten:      ((stage: String, detail: String) -> Unit)? = null

    lateinit var webPlatformRepository: WebPlatformRepository
        private set

    // ── Toast ─────────────────────────────────────────────────────────

    fun showToast(msg: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // المسار 1 — التقاط → ذاكرة
    // ══════════════════════════════════════════════════════════════════

    fun triggerCapture() {
        captureFlag = true
        Log.d("AichatApp", "📌 captureFlag = true")
        showToast("🔍 جاري البحث في الصفحة...")
    }

    // ══════════════════════════════════════════════════════════════════
    // المسار 2 — سياق → منصة
    // ══════════════════════════════════════════════════════════════════

    fun sendContextToPage(
        memories:          List<MemoryItem>,
        customInstruction: String = ""
    ) {
        val memoryContext = MemoryContextBuilder().build(memories)
        val systemPrompt  = SystemPrompt.build(
            memoryContext     = memoryContext,
            customInstruction = customInstruction,
            includeAccuracy   = true
        )
        contextPending = systemPrompt
        Log.d("AichatApp", "📤 contextPending set: ${systemPrompt.length} chars")
        showToast("📤 السياق جاهز: ${systemPrompt.length} حرف")
    }

    // ══════════════════════════════════════════════════════════════════
    // MessageDelegate — aicapture
    // كلا المسارين يمران من هنا عبر onMessage + GeckoResult
    // ══════════════════════════════════════════════════════════════════

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

                // ── المسار 1: التقاط يدوي ────────────────────────────

                "CHECK_CAPTURE" -> {
                    val flag    = captureFlag
                    captureFlag = false
                    if (flag) showToast("📡 جاري الاستخراج...")
                    Log.d("AichatApp", "✅ CHECK_CAPTURE flag=$flag")
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

                // ── المسار 2: إرسال السياق ───────────────────────────

                "GET_CONTEXT" -> {
                    val pending    = contextPending
                    contextPending = ""
                    val has        = pending.isNotBlank()
                    Log.d("AichatApp", "📤 GET_CONTEXT has=$has len=${pending.length}")
                    GeckoResult.fromValue(
                        JSONObject()
                            .put("hasContext", has)
                            .put("context",    pending)
                    )
                }

                "CONTEXT_WRITTEN" -> {
                    val stage  = json.optString("stage")
                    val detail = json.optString("detail")
                    val domain = json.optString("domain")
                    Log.d("AichatApp", "✅ CONTEXT_WRITTEN stage=$stage domain=$domain")
                    val msg = when (stage) {
                        "clicked"       -> "✅ تم الإرسال للمنصة"
                        "enter_pressed" -> "✅ تم الضغط Enter"
                        "write_failed"  -> "❌ فشل الكتابة: $detail"
                        else            -> "⚠️ $stage: $detail"
                    }
                    showToast(msg)
                    Handler(Looper.getMainLooper()).post {
                        onContextWritten?.invoke(stage, detail)
                    }
                    null
                }

                // ── تشخيص ────────────────────────────────────────────

                "DEBUG_INFO" -> {
                    val info = json.optString("info")
                    Log.d("AichatApp", "🔍 $info")
                    showToast("🔍 $info")
                    null
                }

                else -> {
                    Log.w("AichatApp", "⚠️ unknown: $type")
                    null
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // onCreate
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

    // ══════════════════════════════════════════════════════════════════
    // GeckoRuntime — extension واحدة فقط (aicapture)
    // ══════════════════════════════════════════════════════════════════

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
            showToast("❌ فشل تهيئة GeckoRuntime")
            null
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
                        Log.d("AichatApp", "✅ aicapture loaded: ${ext.id}")
                        showToast("✅ Extension جاهزة")
                    } else {
                        Log.e("AichatApp", "❌ aicapture = null")
                        showToast("❌ Extension = null")
                    }
                },
                { e ->
                    Log.e("AichatApp", "❌ aicapture: ${e?.message} | ${e?.cause?.message}")
                    showToast("❌ aicapture: ${e?.message}")
                }
            )
    }

    // ══════════════════════════════════════════════════════════════════
    // معالجة الرسائل
    // ══════════════════════════════════════════════════════════════════

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
            else         "⚠️ Failed: $debug"
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
