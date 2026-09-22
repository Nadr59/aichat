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

    @Volatile private var port:   WebExtension.Port? = null
    @Volatile private var closed = false
    private var ready   = CompletableDeferred<Unit>()
    private var pending: Pair<String, CompletableDeferred<JSONObject>>? = null

    // ── PortDelegate ─────────────────────────────────────────────────

    private val portDelegate = object : WebExtension.PortDelegate {

        override fun onPortMessage(message: Any, source: WebExtension.Port) {
            if (source !== port) return
            val json = parseJson(message) ?: return
            Log.d(TAG, "📩 ${json.optString("type")}")

            when (json.optString("type")) {
                "READY" -> {
                    Log.d(TAG, "✅ READY: ${json.optString("url")}")
                    if (!ready.isCompleted) ready.complete(Unit)
                    app.showToast("✅ Bridge متصل")
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
            ready = CompletableDeferred()
        }
    }

    // ── acceptPort — يُستدعى من AichatApp ────────────────────────────

    fun acceptPort(newPort: WebExtension.Port, host: String) {
        if (closed) {
            Log.w(TAG, "Bridge closed — rejecting port")
            newPort.disconnect()
            return
        }

        if (host !in ALLOWED_HOSTS) {
            Log.w(TAG, "Host not allowed: $host")
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
        } catch (e: Exception) {
            Log.e(TAG, "HELLO failed: ${e.message}")
        }
    }

    // ── init ──────────────────────────────────────────────────────────

    init {
        // تسجيل نفسه كـ bridge نشط
        app.activeBridge = this
        Log.d(TAG, "✅ Bridge initialized — registered as active")
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

        if (closed)       return@withContext DeliveryResult("", "bridge_closed")
        if (pending != null) return@withContext DeliveryResult("", "busy")
        if (port == null) return@withContext DeliveryResult("", "no_port")

        val requestId = UUID.randomUUID().toString()

        if (!ready.isCompleted) {
            Log.d(TAG, "⏳ Waiting for READY...")
            try {
                withTimeout(timeoutMs) { ready.await() }
            } catch (e: TimeoutCancellationException) {
                return@withContext DeliveryResult(requestId, "page_not_ready")
            }
        }

        val currentPort = port ?: return@withContext DeliveryResult(requestId, "no_port")

        val deferred = CompletableDeferred<JSONObject>()
        pending = Pair(requestId, deferred)

        try {
            currentPort.postMessage(
                JSONObject()
                    .put("type",           "DELIVER")
                    .put("requestId",      requestId)
                    .put("systemDocument", systemDocument)
                    .put("memoryContext",  memoryContext)
                    .put("submit",         submit)
            )
            Log.d(TAG, "📤 DELIVER sent")
        } catch (e: Exception) {
            pending = null
            return@withContext DeliveryResult(requestId, "send_failed", e.message ?: "")
        }

        return@withContext try {
            val result = withTimeout(timeoutMs) { deferred.await() }
            DeliveryResult(
                requestId = requestId,
                stage     = result.optString("stage", "unknown"),
                detail    = result.optString("detail", "")
            )
        } catch (e: TimeoutCancellationException) {
            pending = null
            DeliveryResult(requestId, "timeout")
        } catch (e: CancellationException) {
            pending = null
            DeliveryResult(requestId, "cancelled")
        }
    }

    // ── close ─────────────────────────────────────────────────────────

    fun close() {
        closed = true
        if (app.activeBridge === this) app.activeBridge = null
        try { port?.disconnect() } catch (_: Exception) {}
        port = null
        pending?.second?.cancel()
        pending = null
        Log.d(TAG, "Bridge closed")
    }

    private fun parseJson(message: Any): JSONObject? = try {
        when (message) {
            is JSONObject -> message
            is Map<*, *>  -> JSONObject(message as Map<*, *>)
            else          -> null
        }
    } catch (e: Exception) { null }
}
