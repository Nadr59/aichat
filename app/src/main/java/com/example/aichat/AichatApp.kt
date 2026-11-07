package com.example.aichat

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.example.aichat.data.local.ChatDatabase
import com.example.aichat.data.model.MemoryItem
import com.example.aichat.repository.MemoryContextBuilder
import com.example.aichat.repository.WebPlatformRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.WebExtension
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * فشل خطوة ويب (إرسال فاشل أو مهلة). استثناء عادي برسالة عربية جاهزة للعرض.
 * عمدًا ليس CancellationException حتى يُعامَل كفشل لا كإلغاء.
 */
class WebStepException(message: String) : Exception(message)

class AichatApp : Application() {

    companion object {
        private const val TAG = "AichatApp"

        // صلاحية النص المُرسل للصفحة (حتى لو تأخر تحميل الصفحة/المنصة)
        private const val CONTEXT_TTL_MS = 120_000L

        // مهلة انتظار نتيجة الالتقاط اليدوي
        private const val MANUAL_CAPTURE_TTL_MS = 15_000L

        // عدد معرّفات الخطوات المنتهية المحتفَظ بها لإسقاط الردود المتأخرة
        private const val MAX_RETIRED_STEP_IDS = 20

        // اسم الإضافة (assets/aicapture/)
        private const val EXT_BASE_URL = "resource://android/assets/aicapture/"
        private const val EXT_ID = "aicapture@aichat.example.com"
        private const val EXT_NAMESPACE = "browser"
    }

    // =========================
    // Gecko runtime + Extension
    // =========================

    @Volatile
    var geckoRuntime: GeckoRuntime? = null
        private set

    @Volatile
    var aiChatExtension: WebExtension? = null
        private set

    @Volatile
    private var port: WebExtension.Port? = null

    // =========================
    // Web platform repo (اختياري/مفيد)
    // =========================

    lateinit var webPlatformRepository: WebPlatformRepository
        private set

    // =========================
    // Debug log
    // =========================

    val debugLog: SnapshotStateList<String> = mutableStateListOf()

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    fun logDebug(msg: String, toast: Boolean = false) {
        Log.d(TAG, msg)
        mainHandler.post {
            val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            debugLog.add(0, "$time  $msg")
            if (debugLog.size > 150) debugLog.removeAt(debugLog.lastIndex)
            if (toast) {
                Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun clearDebugLog() {
        debugLog.clear()
    }

    fun showToast(msg: String) {
        mainHandler.post {
            Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
        }
    }

    // =========================
    // Public callbacks (يستخدمها UI)
    // =========================

    /**
     * ردود المساعد الملتقطة تلقائياً من منصات الويب
     */
    var onAiResponseCaptured: ((domain: String, text: String) -> Unit)? = null

    /**
     * نتيجة الالتقاط اليدوي (زر 🧠)
     */
    var onManualCaptureResult: ((success: Boolean, text: String, debug: JSONObject?) -> Unit)? = null

    /**
     * نتيجة الكتابة/الإرسال داخل المنصة (من الإضافة)
     */
    var onContextWritten: ((success: Boolean, stage: String, detail: String) -> Unit)? = null

    // =========================
    // Manual capture state
    // =========================

    @Volatile
    private var captureFlag = false

    @Volatile
    private var manualCaptureStartedAt = 0L

    fun triggerCapture() {
        captureFlag = true
        manualCaptureStartedAt = System.currentTimeMillis()
        logDebug("📌 MANUAL_CAPTURE start")

        val p = port
        if (p == null) {
            logDebug("❌ MANUAL_CAPTURE: port unavailable")
            captureFlag = false
            manualCaptureStartedAt = 0L
            mainHandler.post {
                onManualCaptureResult?.invoke(false, "", JSONObject().put("reason", "port_unavailable"))
            }
            return
        }

        runCatching {
            val msg = JSONObject()
                .put("type", "MANUAL_CAPTURE")
                .put("timestamp", System.currentTimeMillis())
            p.postMessage(msg)
            logDebug("📤 MANUAL_CAPTURE sent to extension")
        }.onFailure { e ->
            captureFlag = false
            manualCaptureStartedAt = 0L
            logDebug("❌ MANUAL_CAPTURE failed: ${e.message ?: e}")
            mainHandler.post {
                onManualCaptureResult?.invoke(
                    false,
                    "",
                    JSONObject()
                        .put("reason", "port_send_failed")
                        .put("error", e.message ?: e.toString())
                )
            }
        }
    }

    // =========================
    // Context-to-page (Auto Send)
    // =========================

    private val ctxLock = Any()

    // النص المراد إرساله للمنصة
    private var contextPending: String = ""
    private var lastContextId: Long = 0L
    private var contextSetAt: Long = 0L

    // الهدف (اختياري): host domain مثل chatgpt.com
    private var contextTargetDomain: String = ""

    // selectors للإرسال التلقائي
    private var pendingInputSelector: String = ""
    private var pendingSendSelector: String = ""
    private var pendingSendWithEnter: Boolean = true

    /**
     * ✅ هذا هو المسار المعتمد للإرسال الآن (بدون evaluateJS).
     * GeckoTestScreen يستدعيه عند الضغط على "إرسال للمنصة".
     */
    fun setContextPending(
        text: String,
        targetDomain: String = "",
        inputSelector: String = "",
        sendSelector: String = "",
        sendWithEnter: Boolean = true
    ) {
        setContextPendingWithId(
            id = System.currentTimeMillis(),
            text = text,
            targetDomain = targetDomain,
            inputSelector = inputSelector,
            sendSelector = sendSelector,
            sendWithEnter = sendWithEnter
        )
    }

    /**
     * الجسم الفعلي لـ setContextPending، مع معرّف يحدده المستدعي
     * (تحتاجه sendContextAndAwaitResponse لتسجيل المنتظِر قبل الإرسال).
     * يعيد false إن كان النص فارغًا.
     */
    private fun setContextPendingWithId(
        id: Long,
        text: String,
        targetDomain: String,
        inputSelector: String,
        sendSelector: String,
        sendWithEnter: Boolean
    ): Boolean {
        val t = text.trim()
        if (t.isBlank()) {
            logDebug("⚠️ setContextPending: empty text")
            return false
        }

        synchronized(ctxLock) {
            contextPending = t
            lastContextId = id
            contextSetAt = id
            contextTargetDomain = targetDomain.trim().lowercase(Locale.ROOT)

            pendingInputSelector = inputSelector
            pendingSendSelector = sendSelector
            pendingSendWithEnter = sendWithEnter
        }

        logDebug(
            "📤 Context pending: len=${t.length}, id=$id" +
                (if (contextTargetDomain.isNotBlank()) ", target=$contextTargetDomain" else ""),
            toast = true
        )

        sendPendingContextToPort()
        return true
    }

    /**
     * إرسال سياق ذاكرة للمنصة (قديم/اختياري).
     * يبني النص ثم يمرره لـ setContextPending.
     */
    fun sendContextToPage(
        memories: List<MemoryItem>,
        customInstruction: String = "",
        isSystemPromptEnabled: Boolean = true,
        targetDomain: String = "",
        inputSelector: String = "",
        sendSelector: String = "",
        sendWithEnter: Boolean = true
    ) {
        if (!isSystemPromptEnabled) {
            // المستخدم عطّل ذاكرة الويب
            return
        }

        val memoryContext = MemoryContextBuilder().build(memories)
        if (memoryContext.isBlank()) {
            logDebug("⚠️ sendContextToPage: no memories", toast = true)
            return
        }

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

        setContextPending(
            text = contextText,
            targetDomain = targetDomain,
            inputSelector = inputSelector,
            sendSelector = sendSelector,
            sendWithEnter = sendWithEnter
        )
    }

    private fun sendPendingContextToPort(forced: WebExtension.Port? = null) {
        val p = forced ?: port
        if (p == null) {
            logDebug("⚠️ CONTEXT_TO_PAGE: port unavailable")
            return
        }

        val snapshot: JSONObject = synchronized(ctxLock) {
            if (contextPending.isBlank()) {
                logDebug("⚠️ CONTEXT_TO_PAGE: no pending context")
                return
            }

            val age = System.currentTimeMillis() - contextSetAt
            if (age > CONTEXT_TTL_MS) {
                contextPending = ""
                lastContextId = 0L
                contextSetAt = 0L
                logDebug("⌛ CONTEXT_TO_PAGE: expired")
                return
            }

            JSONObject()
                .put("type", "CONTEXT_TO_PAGE")
                .put("text", contextPending)
                .put("contextId", lastContextId)
                .put("domain", contextTargetDomain)
                .put("inputSelector", pendingInputSelector)
                .put("sendSelector", pendingSendSelector)
                .put("sendWithEnter", pendingSendWithEnter)
                .put("timestamp", System.currentTimeMillis())
        }

        runCatching {
            p.postMessage(snapshot)
            logDebug(
                "📤 CONTEXT_TO_PAGE sent: len=${snapshot.optString("text").length}, id=${snapshot.optLong("contextId")}" +
                    (snapshot.optString("domain").takeIf { it.isNotBlank() }?.let { ", target=$it" } ?: "")
            )
        }.onFailure { e ->
            logDebug("❌ CONTEXT_TO_PAGE failed: ${e.message ?: e}")
        }
    }

    private fun clearContextIfMatches(id: Long): Boolean {
        synchronized(ctxLock) {
            if (id != 0L && id == lastContextId) {
                contextPending = ""
                lastContextId = 0L
                contextSetAt = 0L
                return true
            }
        }
        return false
    }

    // =========================
    // Send-and-await (خطوات سلسلة الوكلاء على الويب)
    // =========================
    //
    // منتظِر واحد فقط في أي وقت (خانة السياق واحدة). يُسجَّل معرّفه قبل الإرسال،
    // فيُوجَّه ردّه (ASSISTANT_RESPONSE بنفس contextId) إليه مباشرة، قبل أي
    // فحص للالتقاط اليدوي، ولا يصل إلى onAiResponseCaptured فلا يُحفظ مرتين.

    private sealed class StepOutcome {
        class Reply(val text: String) : StepOutcome()
        class Failure(val stage: String, val detail: String) : StepOutcome()
    }

    private val stepLock = Any()
    private var stepWaiterId: Long = 0L
    private var stepWaiter: CompletableDeferred<StepOutcome>? = null

    // معرّفات خطوات منتهية (رد/فشل/مهلة/إلغاء): أي رد لاحق بها يُسقَط.
    private val retiredStepIds = LinkedHashSet<Long>()

    private fun retireStepId(id: Long) {
        synchronized(stepLock) {
            retiredStepIds.add(id)
            while (retiredStepIds.size > MAX_RETIRED_STEP_IDS) {
                val oldest = retiredStepIds.iterator()
                oldest.next()
                oldest.remove()
            }
        }
    }

    /**
     * يرسل نص الخطوة إلى المنصة وينتظر ردها الكامل.
     *
     * يعيد نص الرد. يرمي WebStepException عند فشل الإرسال داخل الصفحة أو عند المهلة.
     * عند الإلغاء يمرّر CancellationException بعد تنظيف المنتظِر والسياق المعلّق.
     */
    suspend fun sendContextAndAwaitResponse(
        text: String,
        targetDomain: String,
        inputSelector: String,
        sendSelector: String,
        sendWithEnter: Boolean,
        timeoutMs: Long
    ): String {
        if (text.isBlank()) throw WebStepException("نص الخطوة فارغ.")

        val deferred = CompletableDeferred<StepOutcome>()
        val id = synchronized(ctxLock) { maxOf(System.currentTimeMillis(), lastContextId + 1) }

        synchronized(stepLock) {
            if (stepWaiter != null) {
                throw WebStepException("هناك خطوة ويب قيد الانتظار بالفعل.")
            }
            stepWaiterId = id
            stepWaiter = deferred
        }

        var gotReply = false
        try {
            logDebug("🔗 STEP_WAIT start id=$id timeout=${timeoutMs / 1000}s")

            val sent = setContextPendingWithId(
                id = id,
                text = text,
                targetDomain = targetDomain,
                inputSelector = inputSelector,
                sendSelector = sendSelector,
                sendWithEnter = sendWithEnter
            )
            if (!sent) throw WebStepException("نص الخطوة فارغ.")

            val outcome = withTimeoutOrNull(timeoutMs) { deferred.await() }

            when (outcome) {
                is StepOutcome.Reply -> {
                    gotReply = true
                    logDebug("🔗 STEP_WAIT reply id=$id len=${outcome.text.length}")
                    return outcome.text
                }

                is StepOutcome.Failure -> {
                    logDebug("🔗 STEP_WAIT failed id=$id stage=${outcome.stage} detail=${outcome.detail}")
                    throw WebStepException(
                        "فشل الإرسال إلى المنصة: ${outcome.stage}" +
                            (if (outcome.detail.isNotBlank()) " (${outcome.detail})" else "")
                    )
                }

                null -> {
                    logDebug("🔗 STEP_WAIT timeout id=$id after=${timeoutMs / 1000}s")
                    throw WebStepException(
                        "انتهت مهلة انتظار رد المنصة (${timeoutMs / 1000} ثانية)."
                    )
                }
            }
        } finally {
            if (!gotReply && !deferred.isCompleted) {
                logDebug("🔗 STEP_WAIT cancelled id=$id")
            }
            synchronized(stepLock) {
                if (stepWaiterId == id) {
                    stepWaiterId = 0L
                    stepWaiter = null
                }
            }
            // أي رد يصل لاحقًا بهذا المعرّف يُسقَط (لا يُحفظ كرسالة مستقلة).
            retireStepId(id)
            // لا يُعاد حقن سياق لم تستهلكه الصفحة عند إعادة اتصال المنفذ.
            clearContextIfMatches(id)
        }
    }

    /** إن كان المعرّف لمنتظِر حالي: يسلّمه الرد ويعيد true (حتى لو سبق إكماله). */
    private fun deliverReplyToStepWaiter(contextId: Long, text: String): Boolean {
        if (contextId == 0L) return false
        val waiter = synchronized(stepLock) {
            if (contextId == stepWaiterId) stepWaiter else null
        } ?: return false
        waiter.complete(StepOutcome.Reply(text))
        return true
    }

    private fun failStepWaiterIfMatches(contextId: Long, stage: String, detail: String) {
        if (contextId == 0L) return
        val waiter = synchronized(stepLock) {
            if (contextId == stepWaiterId) stepWaiter else null
        } ?: return
        waiter.complete(StepOutcome.Failure(stage, detail))
    }

    private fun isRetiredStepId(contextId: Long): Boolean {
        if (contextId == 0L) return false
        return synchronized(stepLock) { retiredStepIds.contains(contextId) }
    }

    // =========================
    // Port message handling
    // =========================

    private fun handleManualAssistantResponseIfWaiting(source: String, text: String): Boolean {
        if (!captureFlag) return false

        val age = System.currentTimeMillis() - manualCaptureStartedAt
        if (age > MANUAL_CAPTURE_TTL_MS) {
            logDebug("⌛ Manual capture response too late: ${age}ms")
            captureFlag = false
            manualCaptureStartedAt = 0L
            return false
        }

        captureFlag = false
        manualCaptureStartedAt = 0L
        logDebug("🧠 Manual capture fulfilled via ASSISTANT_RESPONSE")

        mainHandler.post {
            onManualCaptureResult?.invoke(
                true,
                text,
                JSONObject()
                    .put("assistant", 1)
                    .put("bodyLen", text.length)
                    .put("source", source)
            )
        }
        return true
    }

    private fun handleIncoming(messageAny: Any) {
        val json = parseMessage(messageAny)
        if (json == null) {
            logDebug("❌ PORT parseMessage=null")
            return
        }

        val type = json.optString("type")
        if (type.isBlank()) {
            logDebug("❌ PORT message has empty type")
            return
        }

        when (type) {
            "ASSISTANT_RESPONSE" -> {
                val text = json.optString("text", "")
                val source = json.optString("source", "web")
                val contextId = json.optLong("contextId", 0L)

                // 1) منتظِر خطوة سلسلة بهذا المعرّف: يستلم الرد قبل أي مسار آخر.
                if (deliverReplyToStepWaiter(contextId, text)) {
                    logDebug("📨 ASSISTANT_RESPONSE → step waiter: source=$source id=$contextId len=${text.length}")
                    return
                }

                // 2) معرّف خطوة منتهية (مهلة/إلغاء/فشل/مكرر): يُسقَط ولا يُحفظ.
                if (isRetiredStepId(contextId)) {
                    logDebug(
                        "🗑️ ASSISTANT_RESPONSE dropped (retired step id=$contextId): " +
                            "source=$source len=${text.length}"
                    )
                    return
                }

                // 3) المسار العادي كما كان.
                if (text.isBlank()) return

                logDebug("📨 ASSISTANT_RESPONSE: source=$source len=${text.length}")

                if (handleManualAssistantResponseIfWaiting(source, text)) return

                mainHandler.post { onAiResponseCaptured?.invoke(source, text) }
            }

            "CAPTURE_RESULT" -> {
                val success = json.optBoolean("success", false)
                val text = json.optString("text", "")
                val debug = json.optJSONObject("debug")

                captureFlag = false
                manualCaptureStartedAt = 0L

                logDebug(if (success) "🧠 CAPTURE_RESULT OK: ${text.take(60)}…" else "⚠️ CAPTURE_RESULT failed")

                mainHandler.post { onManualCaptureResult?.invoke(success, text, debug) }
            }

            "CONTEXT_WRITTEN" -> {
                val success = json.optBoolean("success", false)
                val contextId = json.optLong("contextId", 0L)
                val stage = json.optString("stage", "")
                val detail = json.optString("detail", "")

                logDebug("✏️ CONTEXT_WRITTEN success=$success id=$contextId stage=$stage detail=$detail")

                // الفشل وحده ينهي انتظار الخطوة؛ النجاحات المتعددة تُتجاهل.
                if (!success) failStepWaiterIfMatches(contextId, stage, detail)

                mainHandler.post { onContextWritten?.invoke(success, stage, detail) }

                // Toast خفيف مفيد للمستخدم
                showToast(
                    when {
                        !success -> "❌ فشل الإرسال: $stage ($detail)"
                        stage == "button_clicked" -> "✅ تم الإرسال"
                        stage == "enter_sent" -> "✅ أُرسل بالضغط على Enter"
                        else -> "✅ تمّت الكتابة"
                    }
                )
            }

            "CONTEXT_CONSUMED" -> {
                val contextId = json.optLong("contextId", 0L)
                val cleared = clearContextIfMatches(contextId)
                logDebug("🗑️ CONTEXT_CONSUMED id=$contextId cleared=$cleared")
            }

            "DEBUG_INFO" -> {
                logDebug("🔍 JS: ${json.optString("info")}")
            }

            else -> {
                logDebug("ℹ️ PORT msg: $type")
            }
        }
    }

    private val messageDelegate = object : WebExtension.MessageDelegate {

        override fun onConnect(port: WebExtension.Port) {
            logDebug("🔌 Native Port connected")
            this@AichatApp.port = port

            port.setDelegate(object : WebExtension.PortDelegate {
                override fun onPortMessage(message: Any, port: WebExtension.Port) {
                    handleIncoming(message)
                }

                override fun onDisconnect(port: WebExtension.Port) {
                    logDebug("⚠️ Native Port disconnected")
                    if (this@AichatApp.port === port) this@AichatApp.port = null
                }
            })

            // إذا كان هناك نص pending قبل اتصال المنفذ (تحميل بطيء للمنصة)
            mainHandler.postDelayed({ sendPendingContextToPort(port) }, 500)
        }

        /**
         * بعض رسائل الاختبار/الـ background قد تصل هنا.
         * نرد دائماً ok حتى لا تتعطل الإضافة.
         */
        override fun onMessage(
            nativeApp: String,
            message: Any,
            sender: WebExtension.MessageSender
        ): GeckoResult<Any>? {
            // تعامل معها مثل رسائل المنفذ إن كانت بنفس الصيغة
            handleIncoming(message)
            return GeckoResult.fromValue(JSONObject().put("ok", true) as Any)
        }
    }

    // =========================
    // Lifecycle
    // =========================

    override fun onCreate() {
        super.onCreate()

        // DB + repo
        val db = ChatDatabase.getDatabase(this)
        webPlatformRepository = WebPlatformRepository(db.webPlatformDao())
        Log.d(TAG, "✅ Database ready")
    }

    @Synchronized
    fun getOrCreateGeckoRuntime(): GeckoRuntime? {
        geckoRuntime?.let { return it }

        return runCatching {
            val settings = GeckoRuntimeSettings.Builder()
                .aboutConfigEnabled(false)
                .consoleOutput(true)
                .remoteDebuggingEnabled(true)
                .build()

            GeckoRuntime.create(applicationContext, settings).also { rt ->
                geckoRuntime = rt
                loadExtension(rt)
                Log.d(TAG, "✅ GeckoRuntime created")
            }
        }.onFailure { e ->
            Log.e(TAG, "❌ GeckoRuntime error: ${e.message}", e)
            showToast("❌ GeckoRuntime error")
        }.getOrNull()
    }

    private fun loadExtension(runtime: GeckoRuntime) {
        runtime.webExtensionController
            .ensureBuiltIn(EXT_BASE_URL, EXT_ID)
            .accept(
                { ext ->
                    if (ext == null) {
                        Log.e(TAG, "❌ Extension is null")
                        logDebug("❌ Extension load failed")
                        return@accept
                    }

                    aiChatExtension = ext
                    ext.setMessageDelegate(messageDelegate, EXT_NAMESPACE)

                    val ver = ext.metaData?.version ?: "?"
                    val baseUrl = ext.metaData?.baseUrl ?: "?"
                    val temporary = ext.metaData?.temporary ?: false

                    Log.d(TAG, "✅ Extension loaded: id=${ext.id} v=$ver baseUrl=$baseUrl temporary=$temporary")
                    logDebug("✅ Extension loaded: v=$ver baseUrl=$baseUrl")
                },
                { error ->
                    val msg = error?.message ?: "unknown"
                    Log.e(TAG, "❌ Extension error: $msg")
                    logDebug("❌ Extension error: $msg")
                }
            )
    }

    // =========================
    // Helpers
    // =========================

    private fun parseMessage(message: Any): JSONObject? = try {
        when (message) {
            is JSONObject -> message
            is Map<*, *> -> JSONObject(message as Map<*, *>)
            is String -> JSONObject(message)
            else -> null
        }
    } catch (e: Exception) {
        Log.e(TAG, "❌ parseMessage: ${e.message}")
        null
    }
}
