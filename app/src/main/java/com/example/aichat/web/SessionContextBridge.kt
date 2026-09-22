package com.example.aichat.web

import android.util.Log
import com.example.aichat.AichatApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.mozilla.geckoview.WebExtension
import java.util.UUID

class SessionContextBridge(
    private val app: AichatApp
) {
    companion object {
        const val NATIVE_APP = "memory_context"
        private const val TAG = "ContextBridge"

        private val ALLOWED_HOSTS = setOf(
            "chatgpt.com", "chat.openai.com",
            "claude.ai",
            "gemini.google.com",
            "www.perplexity.ai", "perplexity.ai",
            "copilot.microsoft.com",
            "grok.com",
            "chat.mistral.ai",
            "you.com", "www.you.com"
        )
    }

    data class DeliveryResult(
        val requestId: String,
        val stage:     String,
        val detail:    String = ""
    )

    // ── الحالة ───────────────────────────────────────────────────────

    @Volatile private var port:    WebExtension.Port?                             = null
    @Volatile private var closed = false
    private var ready  = CompletableDeferred<Unit>()
    private var pending: Pair<String, CompletableDeferred<JSONObject>>? = null

    // ── PortDelegate ─────────────────────────────────────────────────

    private val portDelegate = object : WebExtension.PortDelegate {

        override fun onPortMessage(message: Any, source: WebExtension.Port) {
            if (source !== port) return
            val json = parseJson(message) ?: return

            Log.d(TAG, "📩 from content.js: ${json.optString("type")}")

            when (json.optString("type")) {
                "READY" -> {
                    Log.d(TAG, "✅ Page ready: ${json.optString("url")}")
                    if (!ready.isCompleted) ready.complete(Unit)
                }
                "RESULT" -> {
                    val p = pending ?: return
                    if (json.optString("requestId") == p.first) {
                        p.second.complete(json)
                        pending = null
                    }
                }
            }
        }

        override fun onDisconnect(port: WebExtension.Port) {
            Log.w(TAG, "⚠️ Port disconnected")
            this@SessionContextBridge.port = null
            // إعادة تعيين ready للصفحة التالية
            ready = CompletableDeferred()
        }
    }

    // ── MessageDelegate ───────────────────────────────────────────────

    val messageDelegate = object : WebExtension.MessageDelegate {

        override fun onConnect(newPort: WebExtension.Port) {
            Log.d(TAG, "🔌 onConnect: name=${newPort.name} url=${newPort.sender?.url}")

            // تحقق من الاسم
            if (newPort.name != NATIVE_APP) {
                Log.w(TAG, "Wrong port name: ${newPort.name}")
                newPort.disconnect()
                return
            }

            // تحقق من الـ host
            val senderUrl = newPort.sender?.url ?: ""
            val host = runCatching {
                java.net.URI(senderUrl).host ?: ""
            }.getOrDefault("")

            Log.d(TAG, "🔌 host=$host allowed=${host in ALLOWED_HOSTS}")

            if (closed || host !in ALLOWED_HOSTS) {
                Log.w(TAG, "Port rejected — closed=$closed host=$host")
                newPort.disconnect()
                return
            }

            // إبطال Port قديم
            port?.disconnect()
            port  = newPort
            ready = CompletableDeferred()
            newPort.setDelegate(portDelegate)

            // مصافحة
            try {
                newPort.postMessage(JSONObject().put("type", "HELLO"))
                Log.d(TAG, "✅ Port accepted from $host — HELLO sent")
                app.showToast("✅ Bridge: $host")
            } catch (e: Exception) {
                Log.e(TAG, "HELLO failed: ${e.message}")
            }
        }
    }

    // ── init ──────────────────────────────────────────────────────────

    init {
        // تسجيل عبر AichatApp (يضمن التزامن الصحيح)
        app.registerBridgeDelegate(messageDelegate)
        Log.d(TAG, "✅ Bridge initialized")
    }

    // ── isConnected ───────────────────────────────────────────────────

    val isConnected: Boolean get() = port != null && !closed

    val isPageReady: Boolean get() = ready.isCompleted

    // ── deliver ───────────────────────────────────────────────────────

    suspend fun deliver(
        systemDocument: String,
        memoryContext:  String,
        submit:         Boolean = false,
        timeoutMs:      Long    = 10_000L
    ): DeliveryResult = withContext(Dispatchers.Main) {

        Log.d(TAG, "deliver: closed=$closed port=${port != null} ready=${ready.isCompleted}")

        if (closed) {
            return@withContext DeliveryResult("", "bridge_closed")
        }
        if (pending != null) {
            return@withContext DeliveryResult("", "busy")
        }
        if (port == null) {
            return@withContext DeliveryResult("", "no_port")
        }

        val requestId = UUID.randomUUID().toString()

        // انتظار جاهزية الصفحة
        if (!ready.isCompleted) {
            Log.d(TAG, "⏳ Waiting for page ready...")
            try {
                withTimeout(timeoutMs) { ready.await() }
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "❌ Page not ready after ${timeoutMs}ms")
                return@withContext DeliveryResult(requestId, "page_not_ready")
            }
        }

        val currentPort = port
        if (currentPort == null) {
            return@withContext DeliveryResult(requestId, "no_port")
        }

        // إعداد الانتظار
        val deferred = CompletableDeferred<JSONObject>()
        pending = Pair(requestId, deferred)

        // إرسال DELIVER
        try {
            currentPort.postMessage(
                JSONObject()
                    .put("type",           "DELIVER")
                    .put("requestId",      requestId)
                    .put("systemDocument", systemDocument)
                    .put("memoryContext",  memoryContext)
                    .put("submit",         submit)
            )
            Log.d(TAG, "📤 DELIVER sent: $requestId")
        } catch (e: Exception) {
            pending = null
            Log.e(TAG, "postMessage failed: ${e.message}")
            return@withContext DeliveryResult(requestId, "send_failed", e.message ?: "")
        }

        // انتظار النتيجة
        return@withContext try {
            val result = withTimeout(timeoutMs) { deferred.await() }
            Log.d(TAG, "✅ RESULT: ${result.optString("stage")}")
            DeliveryResult(
                requestId = requestId,
                stage     = result.optString("stage", "unknown"),
                detail    = result.optString("detail", "")
            )
        } catch (e: TimeoutCancellationException) {
            pending = null
            Log.e(TAG, "❌ Timeout waiting for RESULT")
            DeliveryResult(requestId, "timeout")
        } catch (e: CancellationException) {
            pending = null
            DeliveryResult(requestId, "cancelled")
        }
    }

    // ── close ─────────────────────────────────────────────────────────

    fun close() {
        closed = true
        try { port?.disconnect() } catch (_: Exception) {}
        port = null
        pending?.second?.cancel()
        pending = null
        Log.d(TAG, "Bridge closed")
    }

    // ── مساعد ────────────────────────────────────────────────────────

    private fun parseJson(message: Any): JSONObject? = try {
        when (message) {
            is JSONObject -> message
            is Map<*, *>  -> JSONObject(message as Map<*, *>)
            else          -> null
        }
    } catch (e: Exception) {
        Log.e(TAG, "parseJson: ${e.message}")
        null
    }
}
