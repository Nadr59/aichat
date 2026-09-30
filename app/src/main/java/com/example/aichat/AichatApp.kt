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
    @Volatile private var lastContextId  = 0L    // 🆕 تتبع معرف الـ context الحالي

    var onAiResponseCaptured:  ((domain: String, text: String) -> Unit)? = null
    var onManualCaptureResult: ((success: Boolean, text: String, debug: JSONObject?) -> Unit)? = null
    var onContextWritten:      ((success: Boolean, stage: String, detail: String) -> Unit)? = null

    lateinit var webPlatformRepository: WebPlatformRepository
        private set

    // ══════════════════════════════════════════════════════════════════
    // 🆕 سجل تشخيص دائم داخل التطبيق (بديل لـ Logcat غير المتاح في CI)
    // ══════════════════════════════════════════════════════════════════

    val debugLog: SnapshotStateList<String> = mutableStateListOf()

    /**
     * تسجيل شامل: Log + Toast + السجل الدائم
     * (للرسائل التشخيصية المهمة من Extension أو داخل التطبيق)
     */
    fun logDebug(msg: String) {
        Log.d("AichatApp", msg)
        
        Handler(Looper.getMainLooper()).post {
            val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            val fullMsg = "$time  $msg"
            debugLog.add(0, fullMsg)
            
            // احتفظ بـ 150 سطر فقط
            if (debugLog.size > 150) {
                debugLog.removeAt(debugLog.lastIndex)
            }
            
            // عرض Toast
            Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * تنظيف السجل التشخيصي
     */
    fun clearDebugLog() {
        debugLog.clear()
    }

    // ══════════════════════════════════════════════════════════════════
    // Toast Helper (بدون تسجيل - للرسائل العادية)
    // ══════════════════════════════════════════════════════════════════

    fun showToast(msg: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // المسار 1 — التقاط يدوي (زر 🧠)
    // ══════════════════════════════════════════════════════════════════

    /**
     * تشغيل العلم لالتقاط استجابة الـ AI الحالية
     * (يُستدعى من UI عند الضغط على زر 🧠)
     */
    fun triggerCapture() {
        captureFlag = true
        logDebug("📌 captureFlag = true (manual capture requested)")
    }

    // ══════════════════════════════════════════════════════════════════
    // المسار 2 — إرسال السياق إلى المنصة
    // ══════════════════════════════════════════════════════════════════

    /**
     * تعيين نص مباشرة للإرسال إلى المنصة
     * (يُستخدم من Dialog أو قائمة)
     *
     * آلية العمل:
     * 1. يُستدعى من UI مع نص
     * 2. يُخزّن في contextPending
     * 3. content.js يلتقطه عبر GET_CONTEXT polling (كل ثانية)
     * 4. يُكتب في textarea ويُضغط الزر
     * 5. يُرسل CONTEXT_WRITTEN للتأكيد
     */
    fun setContextPending(text: String) {
        if (text.isBlank()) {
            logDebug("⚠️ setContextPending: Empty text, ignoring")
            return
        }

        contextPending = text
        lastContextId = System.currentTimeMillis()  // 🆕 معرف فريد للـ context
        logDebug("📤 Context pending set: ${text.length} chars, id=$lastContextId")
    }

    /**
     * الدالة القديمة - للتوافق مع الكود القديم
     * (تبني السياق من الذكريات ثم تستدعي setContextPending)
     */
    fun sendContextToPage(
        memories:              List<MemoryItem>,
        customInstruction:     String  = "",
        isSystemPromptEnabled: Boolean = true
    ) {
        val memoryContext = MemoryContextBuilder().build(memories)

        if (memoryContext.isBlank()) {
            logDebug("⚠️ sendContextToPage: لا توجد ذكريات لإرسالها")
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
            if (customInstruction.isNotBlank()) {
                appendLine("تعليمات مخصصة:")
                appendLine(customInstruction)
                appendLine()
            }
            appendLine("السؤال:")
        }

        setContextPending(contextText)
    }

    // ══════════════════════════════════════════════════════════════════
    // WebExtension MessageDelegate — aicapture
    // ══════════════════════════════════════════════════════════════════

    private val messageDelegateAiCapture = object : WebExtension.MessageDelegate {

        override fun onMessage(
            nativeApp: String,
            message:   Any,
            sender:    WebExtension.MessageSender
        ): GeckoResult<Any>? {

            val json = parseMessage(message)
            if (json == null) {
                logDebug("❌ onMessage: parseMessage=null, raw=$message")
                return GeckoResult.fromValue(null)
            }

            val type = json.optString("type")
            if (type.isBlank()) {
                logDebug("❌ onMessage: empty type")
                return GeckoResult.fromValue(null)
            }

            // تسجيل الاستقبال (تجاهل GET_CONTEXT لأنه متكرر جداً)
            if (type != "GET_CONTEXT") {
                logDebug("📩 [aicapture] $type")
            }

            return when (type) {

                // ══════════════════════════════════════════════════════
                // المسار 1: التقاط يدوي (زر 🧠)
                // ══════════════════════════════════════════════════════

                "CHECK_CAPTURE" -> handleCheckCapture(json)

                "CAPTURE_RESULT" -> {
                    handleCaptureResult(json)
                    GeckoResult.fromValue(null)
                }

                // ══════════════════════════════════════════════════════
                // المسار 2: استجابة تلقائية
                // ══════════════════════════════════════════════════════

                "AI_RESPONSE" -> {
                    handleAutoResponse(json)
                    GeckoResult.fromValue(null)
                }

                // ══════════════════════════════════════════════════════
                // المسار 3: إرسال السياق
                // ══════════════════════════════════════════════════════

                "GET_CONTEXT" -> handleGetContext()

                "CONTEXT_WRITTEN" -> {
                    handleContextWritten(json)
                    GeckoResult.fromValue(null)
                }

                // ══════════════════════════════════════════════════════
                // التشخيص
                // ══════════════════════════════════════════════════════

                "DEBUG_INFO" -> {
                    val info = json.optString("info")
                    logDebug("🔍 JS: $info")
                    GeckoResult.fromValue(null)
                }

                // ══════════════════════════════════════════════════════
                // 🆕 أي نوع غير معروف - لا يُسقط بصمت أبداً
                // ══════════════════════════════════════════════════════

                "UNKNOWN_TYPE" -> {
                    val original = json.optString("original")
                    val payload  = json.optString("payload")
                    logDebug("⚠️ UNKNOWN_TYPE: $original | $payload")
                    GeckoResult.fromValue(null)
                }

                else -> {
                    logDebug("⚠️ unknown message type: $type")
                    GeckoResult.fromValue(null)
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // معالجات الرسائل التفصيلية
    // ══════════════════════════════════════════════════════════════════

    /**
     * CHECK_CAPTURE — طلب التقاط يدوي
     * يُرسل flag يشير إلى وجود طلب التقاط
     */
    private fun handleCheckCapture(json: JSONObject): GeckoResult<Any> {
        val flag = captureFlag
        captureFlag = false
        
        if (flag) {
            logDebug("📡 captureFlag TRUE → content.js سيستخرج الاستجابة الآن")
        }
        
        return GeckoResult.fromValue(
            JSONObject().put("capture", flag)
        )
    }

    /**
     * CAPTURE_RESULT — نتيجة التقاط يدوي
     * يُرسل من content.js بعد استخراج النص بنجاح
     */
    private fun handleCaptureResult(json: JSONObject) {
        val success = json.optBoolean("success", false)
        val text    = json.optString("text")
        val debug   = json.optJSONObject("debug")
        
        val msg = if (success) {
            "🧠 Capture OK: ${text.take(60)}…"
        } else {
            "⚠️ Capture Failed: $debug"
        }
        logDebug(msg)
        
        Handler(Looper.getMainLooper()).post {
            onManualCaptureResult?.invoke(success, text, debug)
        }
    }

    /**
     * AI_RESPONSE — استجابة تلقائية من Polling
     * يُرسل من content.js تلقائياً عند اكتشاف استجابة جديدة
     */
    private fun handleAutoResponse(json: JSONObject) {
        val text   = json.optString("text")
        val domain = json.optString("domain", "unknown")
        
        if (text.length < 80) {
            return  // تجاهل الرسائل القصيرة
        }
        
        logDebug("📨 Auto Response: ${text.take(60)}…")
        
        Handler(Looper.getMainLooper()).post {
            onAiResponseCaptured?.invoke(domain, text)
        }
    }

    /**
     * GET_CONTEXT — طلب السياق (polling من content.js كل ثانية)
     *
     * 🔑 تحسينات:
     * - لا نمسح contextPending هنا بعد الآن ❌ (كان يسبب race condition)
     * - نرسل معرف فريد (id) مع السياق للتتبع
     * - نترك المسح للـ CONTEXT_WRITTEN عند التأكيد الفعلي ✅
     */
    private fun handleGetContext(): GeckoResult<Any> {
        val pending = contextPending
        val has     = pending.isNotBlank()
        
        // 🆕 لا تمسح: contextPending = ""
        
        if (has) {
            logDebug("📤 GET_CONTEXT: has=true, len=${pending.length}, id=$lastContextId")
        }
        
        return GeckoResult.fromValue(
            JSONObject().apply {
                put("hasContext", has)
                put("context",    pending)
                put("id",         lastContextId)  // 🆕 معرف فريد للتتبع
            }
        )
    }

    /**
     * CONTEXT_WRITTEN — تأكيد كتابة السياق
     *
     * 🔑 تحسينات:
     * - نتحقق من معرف الـ context (contextId) قبل المسح
     * - نمسح فقط عند النجاح المؤكد
     * - نرسل رسالة مرئية (Toast) للمستخدم
     */
    private fun handleContextWritten(json: JSONObject) {
        val success    = json.optBoolean("success", false)
        val contextId  = json.optLong("contextId", 0L)
        val stage      = json.optString("stage", "")
        val detail     = json.optString("detail", "")
        val domain     = json.optString("domain", "unknown")
        
        // تتبع
        logDebug("✏️ CONTEXT_WRITTEN: success=$success, id=$contextId (lastId=$lastContextId), stage=$stage, domain=$domain")
        
        // تحديث UI/Callback
        Handler(Looper.getMainLooper()).post {
            onContextWritten?.invoke(success, stage, detail)
        }
        
        // مسح السياق فقط عند النجاح والمطابقة
        if (success && contextId == lastContextId && contextId != 0L) {
            contextPending = ""
            lastContextId = 0L
            logDebug("🗑️ contextPending cleared (matched id)")
        } else if (success) {
            logDebug("⚠️ CONTEXT_WRITTEN success but id mismatch: $contextId != $lastContextId")
        } else {
            logDebug("❌ CONTEXT_WRITTEN failed: $detail")
        }
        
        // عرض رسالة للمستخدم
        val userMsg = when {
            !success -> "❌ فشل الإرسال: $detail"
            stage == "button_clicked" -> "✅ تم الإرسال للمنصة بنجاح"
            stage == "enter_pressed" -> "✅ تم الضغط Enter"
            else -> "✅ تمت الكتابة: $stage"
        }
        showToast(userMsg)
    }

    // ══════════════════════════════════════════════════════════════════
    // onCreate — تهيئة التطبيق
    // ══════════════════════════════════════════════════════════════════

    override fun onCreate() {
        super.onCreate()
        try {
            val db = ChatDatabase.getDatabase(this)
            webPlatformRepository = WebPlatformRepository(db.webPlatformDao())
            Log.d("AichatApp", "✅ Database ready")
        } catch (e: Exception) {
            Log.e("AichatApp", "❌ Database initialization failed: ${e.message}", e)
            showToast("❌ فشل تهيئة قاعدة البيانات")
            throw e
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // GeckoRuntime — تحميل Extension
    // ══════════════════════════════════════════════════════════════════

    /**
     * إنشاء أو إرجاع GeckoRuntime (بدون تزامن دقيق للأداء)
     */
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
            Log.e("AichatApp", "❌ GeckoRuntime error: ${e.message}", e)
            showToast("❌ فشل إنشاء GeckoRuntime")
            null
        }
    }

    /**
     * تحميل Extension اليتيمة (aicapture)
     */
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
                        Log.d("AichatApp", "✅ aicapture extension loaded: ${ext.id}")
                        logDebug("✅ Extension 'aicapture' loaded successfully")
                    } else {
                        Log.e("AichatApp", "❌ Extension load returned null")
                        logDebug("❌ Extension load returned null")
                    }
                },
                { error ->
                    val msg = error?.message ?: "Unknown error"
                    val cause = error?.cause?.message ?: ""
                    Log.e("AichatApp", "❌ aicapture error: $msg | $cause")
                    logDebug("❌ Extension error: $msg")
                }
            )
    }

    // ══════════════════════════════════════════════════════════════════
    // معالج تحويل الرسائل
    // ══════════════════════════════════════════════════════════════════

    /**
     * تحويل أي صيغة رسالة إلى JSONObject
     */
    private fun parseMessage(message: Any): JSONObject? = try {
        when (message) {
            is JSONObject -> message
            is Map<*, *>  -> JSONObject(message as Map<*, *>)
            else -> {
                Log.w("AichatApp", "⚠️ parseMessage: unsupported type ${message.javaClass.simpleName}")
                null
            }
        }
    } catch (e: Exception) {
        Log.e("AichatApp", "❌ parseMessage exception: ${e.message}", e)
        null
    }
}
