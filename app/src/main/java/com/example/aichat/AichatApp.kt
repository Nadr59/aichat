package com.example.aichat

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    // ══════════════════════════════════════════════════════════════════
    // 🆕 سجل تشخيص دائم داخل التطبيق (بديل لـ Logcat غير المتاح في CI)
    // ══════════════════════════════════════════════════════════════════

    val debugLog: SnapshotStateList<String> = mutableStateListOf()

    fun logDebug(msg: String) {
        Log.d("AichatApp", msg)
        Handler(Looper.getMainLooper()).post {
            val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            debugLog.add(0, "$time  $msg")
            if (debugLog.size > 150) debugLog.removeAt(debugLog.lastIndex)
            Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
        }
    }

    fun clearDebugLog() {
        debugLog.clear()
    }

    // ══════════════════════════════════════════════════════════════════
    // Toast Helper (بدون تسجيل - للرسائل العادية غير التشخيصية)
    // ══════════════════════════════════════════════════════════════════

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
        logDebug("📌 captureFlag = true")
    }

    // ══════════════════════════════════════════════════════════════════
    // المسار 2 — سياق → منصة
    // ══════════════════════════════════════════════════════════════════

    /**
     * تعيين نص مباشرة للإرسال إلى المنصة
     * (يُستخدم من Dialog الجديد)
     *
     * ملاحظة: content.js سيلتقطه عبر GET_CONTEXT polling (كل ثانية)
     */
    fun setContextPending(text: String) {
        if (text.isBlank()) {
            logDebug("⚠️ Empty text, ignoring")
            return
        }

        contextPending = text
        logDebug("📤 Context pending set: ${text.length} chars")
    }

    /**
     * الدالة القديمة - للتوافق مع الكود القديم
     */
    fun sendContextToPage(
        memories:              List<MemoryItem>,
        customInstruction:     String  = "",
        isSystemPromptEnabled: Boolean = true
    ) {
        val memoryContext = MemoryContextBuilder().build(memories)

        if (memoryContext.isBlank()) {
            logDebug("⚠️ لا توجد ذكريات لإرسالها")
            return
        }

        // تنسيق مناسب لـ textarea
        val contextText = buildString {
            appendLine("السياق من محادثاتي السابقة:")
            appendLine()
            appendLine(memoryContext)
            appendLine()
            appendLine("───────────")
            appendLine()
            appendLine("السؤال:")
        }

        contextPending = contextText
        logDebug("📤 contextPending set: ${contextText.length} chars")
    }

    // ══════════════════════════════════════════════════════════════════
    // MessageDelegate — aicapture
    // ══════════════════════════════════════════════════════════════════

    private val messageDelegateAiCapture = object : WebExtension.MessageDelegate {

        override fun onMessage(
            nativeApp: String,
            message:   Any,
            sender:    WebExtension.MessageSender
        ): GeckoResult<Any>? {

            val json = parseMessage(message)
            if (json == null) {
                logDebug("❌ parseMessage=null raw=$message")
                return GeckoResult.fromValue(null)
            }

            val type = json.optString("type")
            logDebug("📩 [aicapture] $type")

            return when (type) {

                // ══════════════════════════════════════════════════════
                // المسار 1: التقاط يدوي
                // ══════════════════════════════════════════════════════

                "CHECK_CAPTURE" -> {
                    val flag    = captureFlag
                    captureFlag = false
                    if (flag) logDebug("📡 جاري الاستخراج...")
                    GeckoResult.fromValue(
                        JSONObject().put("capture", flag)
                    )
                }

                "AI_RESPONSE" -> {
                    handleAutoResponse(json)
                    GeckoResult.fromValue(null)
                }

                "CAPTURE_RESULT" -> {
                    handleCaptureResult(json)
                    GeckoResult.fromValue(null)
                }

                // ══════════════════════════════════════════════════════
                // المسار 2: إرسال السياق
                // ══════════════════════════════════════════════════════

                "GET_CONTEXT" -> {
                    val pending    = contextPending
                    contextPending = ""
                    val has        = pending.isNotBlank()
                    logDebug("📤 GET_CONTEXT has=$has len=${pending.length}")
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
                    val msg = when (stage) {
                        "clicked"       -> "✅ تم الإرسال للمنصة"
                        "enter_pressed" -> "✅ تم الضغط Enter"
                        "write_failed"  -> "❌ فشل الكتابة: $detail"
                        else            -> "⚠️ $stage: $detail"
                    }
                    logDebug("$msg (domain=$domain)")
                    Handler(Looper.getMainLooper()).post {
                        onContextWritten?.invoke(stage, detail)
                    }
                    GeckoResult.fromValue(null)
                }

                // ══════════════════════════════════════════════════════
                // تشخيص
                // ══════════════════════════════════════════════════════

                "DEBUG_INFO" -> {
                    val info = json.optString("info")
                    logDebug("🔍 JS: $info")
                    GeckoResult.fromValue(null)
                }

                // ══════════════════════════════════════════════════════
                // 🆕 أي نوع غير معروف/غير متوقع - لا يُسقط بصمت أبداً
                // ══════════════════════════════════════════════════════

                "UNKNOWN_TYPE" -> {
                    val original = json.optString("original")
                    val payload  = json.optString("payload")
                    logDebug("⚠️ UNKNOWN_TYPE from background.js: $original | $payload")
                    GeckoResult.fromValue(null)
                }

                else -> {
                    logDebug("⚠️ unknown type: $type")
                    GeckoResult.fromValue(null)
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
        logDebug("📨 Auto captured: ${text.take(60)}…")
        onAiResponseCaptured?.invoke(domain, text)
    }

    private fun handleCaptureResult(json: JSONObject) {
        val success = json.optBoolean("success", false)
        val text    = json.optString("text")
        val debug   = json.optJSONObject("debug")
        logDebug(
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
