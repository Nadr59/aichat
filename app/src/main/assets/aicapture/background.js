"use strict";

var NATIVE_APP = "browser";

// رسائل تُمرَّر كما هي إلى Kotlin دون انتظار رد مهم
var FORWARD_TYPES = {
    AI_RESPONSE:      true,
    CAPTURE_RESULT:   true,
    CONTEXT_WRITTEN:  true,
    CONTEXT_CONSUMED: true,
    DEBUG_INFO:       true
};

// أنواع لا تُسجَّل لتجنب إغراق اللوج
var QUIET_TYPES = { POLL: true, DEBUG_INFO: true };

var EMPTY_POLL = { capture: false, hasContext: false, context: "", id: 0 };

// ══════════════════════════════════════════════════════════════════
// Logging
// ══════════════════════════════════════════════════════════════════

function logLocal(message) {
    var timestamp = new Date().toLocaleTimeString('en-US', {
        hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit'
    });
    console.log('[Background] ' + timestamp + '  ' + message);
}

var lastPollError = '';
function logPollErrorOnce(msg) {
    if (msg !== lastPollError) {
        lastPollError = msg;
        logLocal('❌ POLL error: ' + msg);
    }
}

// ══════════════════════════════════════════════════════════════════
// Send to Native
// ══════════════════════════════════════════════════════════════════

function normalize(response) {
    if (typeof response === 'string') {
        try { return JSON.parse(response); }
        catch (e) { return null; }
    }
    return response;
}

function sendToNative(payload) {
    var quiet = !!QUIET_TYPES[payload.type];
    if (!quiet) logLocal('🔄 → native: ' + payload.type);

    return browser.runtime.sendNativeMessage(NATIVE_APP, payload)
        .then(function (response) {
            if (!quiet) logLocal('✅ native replied: ' + payload.type);
            return normalize(response);
        })
        .catch(function (error) {
            var m = (error && error.message) ? error.message : String(error);
            if (!quiet) logLocal('❌ native failed: ' + payload.type + ' — ' + m);
            throw error;
        });
}

// ══════════════════════════════════════════════════════════════════
// Message Listener (يرجع Promise بدل sendResponse)
// ══════════════════════════════════════════════════════════════════

browser.runtime.onMessage.addListener(function (message, sender) {
    if (!message || !message.type) {
        return Promise.resolve({ error: 'No message type' });
    }

    var type = message.type;

    // ── POLL: التقاط يدوي + سياق في طلب واحد ──────────────────────
    if (type === "POLL") {
        return sendToNative({
            type:    "POLL",
            domain:  message.domain  || "",
            visible: message.visible !== false
        }).then(function (r) {
            r = r || {};
            lastPollError = '';
            return {
                capture:    !!r.capture,
                hasContext: !!r.hasContext,
                context:    r.context || "",
                id:         r.id || 0
            };
        }).catch(function (e) {
            logPollErrorOnce((e && e.message) ? e.message : String(e));
            return EMPTY_POLL;
        });
    }

    // ── رسائل تُمرَّر فقط ─────────────────────────────────────────
    if (FORWARD_TYPES[type]) {
        sendToNative(message).catch(function () {});
        return Promise.resolve({ ok: true });
    }

    logLocal('⚠️ UNKNOWN_TYPE: ' + type);
    return Promise.resolve({ error: 'Unknown message type: ' + type });
});

// ══════════════════════════════════════════════════════════════════
// Extension Events
// ══════════════════════════════════════════════════════════════════

browser.runtime.onInstalled.addListener(function (details) {
    logLocal(details.reason === "install" ? '🎉 Extension installed'
           : details.reason === "update"  ? '🔄 Extension updated'
           : 'ℹ️ onInstalled: ' + details.reason);
});

// ══════════════════════════════════════════════════════════════════
// Startup + اختبار القناة
// ══════════════════════════════════════════════════════════════════

logLocal('✅ Background script ready, v=' + browser.runtime.getManifest().version);

sendToNative({ type: "PING" })
    .then(function (r) { logLocal('🏓 PING reply: ' + JSON.stringify(r)); })
    .catch(function (e) { logLocal('🏓 PING failed: ' + (e && e.message)); });
