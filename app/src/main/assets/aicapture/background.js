"use strict";

var NATIVE_APP = "browser";

// ══════════════════════════════════════════════════════════════════
// Helper: تسجيل محلي للتشخيص
// ══════════════════════════════════════════════════════════════════

function logLocal(message) {
    var timestamp = new Date().toLocaleTimeString('en-US', {
        hour12: false,
        hour: '2-digit',
        minute: '2-digit',
        second: '2-digit'
    });
    console.log('[Background] ' + timestamp + '  ' + message);
}

// ══════════════════════════════════════════════════════════════════
// Helper: إرسال رسالة آمن إلى Kotlin
// ══════════════════════════════════════════════════════════════════

function sendToNative(payload) {
    return new Promise(function (resolve, reject) {
        try {
            browser.runtime.sendNativeMessage(NATIVE_APP, payload, function (response) {
                if (browser.runtime.lastError) {
                    logLocal('❌ Native error: ' + browser.runtime.lastError.message);
                    reject(new Error(browser.runtime.lastError.message));
                } else {
                    resolve(response);
                }
            });
        } catch (e) {
            logLocal('❌ sendToNative exception: ' + e.message);
            reject(e);
        }
    });
}

// ══════════════════════════════════════════════════════════════════
// Main Message Listener
// ══════════════════════════════════════════════════════════════════

browser.runtime.onMessage.addListener(function (message, sender, sendResponse) {
    if (!message || !message.type) {
        logLocal('⚠️ Empty message received');
        sendResponse({ error: 'No message type' });
        return false;
    }

    var type = message.type;
    logLocal('📨 Received: ' + type + ' from ' + (sender.url || 'unknown'));

    // ────────────────────────────────────────────────────────────────
    // AI_RESPONSE — استجابة الـ AI التلقائية
    // ────────────────────────────────────────────────────────────────
    if (type === "AI_RESPONSE") {
        logLocal('📤 AI_RESPONSE: ' + (message.text ? message.text.substring(0, 50) + '...' : 'empty'));
        
        sendToNative({
            type:   "AI_RESPONSE",
            text:   message.text   || "",
            domain: message.domain || ""
        }).catch(function (e) {
            logLocal('❌ AI_RESPONSE failed: ' + e.message);
        });
        
        sendResponse({ ok: true });
        return false;
    }

    // ────────────────────────────────────────────────────────────────
    // CHECK_CAPTURE — طلب التقاط (من زر 🧠 في Kotlin)
    // ────────────────────────────────────────────────────────────────
    if (type === "CHECK_CAPTURE") {
        logLocal('🧠 CHECK_CAPTURE requested');
        
        sendToNative({
            type:   "CHECK_CAPTURE",
            domain: message.domain || ""
        }).then(function (response) {
            logLocal('✅ CHECK_CAPTURE response: capture=' + (response && response.capture));
            sendResponse(response || { capture: false });
        }).catch(function (e) {
            logLocal('❌ CHECK_CAPTURE error: ' + e.message);
            sendResponse({ capture: false });
        });
        
        return true; // يشير إلى أننا سنرد بشكل async
    }

    // ────────────────────────────────────────────────────────────────
    // CAPTURE_RESULT — نتيجة التقاط
    // ────────────────────────────────────────────────────────────────
    if (type === "CAPTURE_RESULT") {
        logLocal('📸 CAPTURE_RESULT: success=' + message.success + ' text=' + 
            (message.text ? message.text.substring(0, 50) + '...' : 'empty'));
        
        sendToNative({
            type:    "CAPTURE_RESULT",
            success: message.success || false,
            text:    message.text    || "",
            domain:  message.domain  || "",
            debug:   message.debug   || null
        }).catch(function (e) {
            logLocal('❌ CAPTURE_RESULT failed: ' + e.message);
        });
        
        sendResponse({ ok: true });
        return false;
    }

    // ────────────────────────────────────────────────────────────────
    // GET_CONTEXT — طلب السياق (polling من content.js)
    // ────────────────────────────────────────────────────────────────
    if (type === "GET_CONTEXT") {
        // logLocal('🔄 GET_CONTEXT polled'); // كثير جداً - نتجاهل السجل
        
        sendToNative({
            type:   "GET_CONTEXT",
            domain: message.domain || ""
        }).then(function (response) {
            if (response && response.hasContext) {
                logLocal('✅ GET_CONTEXT: hasContext=true, len=' + 
                    (response.context ? response.context.length : 0) +
                    ', id=' + (response.id || '?'));
            }
            sendResponse(response || { hasContext: false, context: "" });
        }).catch(function (e) {
            logLocal('❌ GET_CONTEXT error: ' + e.message);
            sendResponse({ hasContext: false, context: "" });
        });
        
        return true; // async response
    }

    // ────────────────────────────────────────────────────────────────
    // CONTEXT_WRITTEN — تأكيد كتابة السياق
    // ────────────────────────────────────────────────────────────────
    if (type === "CONTEXT_WRITTEN") {
        logLocal('✏️ CONTEXT_WRITTEN: success=' + message.success + 
            ', contextId=' + (message.contextId || '?') +
            ', stage=' + (message.stage || '?'));
        
        sendToNative({
            type:      "CONTEXT_WRITTEN",
            success:   message.success   || false,
            contextId: message.contextId || 0,
            stage:     message.stage     || "",
            detail:    message.detail    || "",
            domain:    message.domain    || ""
        }).catch(function (e) {
            logLocal('❌ CONTEXT_WRITTEN failed: ' + e.message);
        });
        
        sendResponse({ ok: true });
        return false;
    }

    // ────────────────────────────────────────────────────────────────
    // DEBUG_INFO — رسائل التشخيص من content.js
    // ────────────────────────────────────────────────────────────────
    // 🆕 هذا الفرع كان مفقوداً بالكامل — سبب اختفاء كل رسائل التشخيص
    // القادمة من content.js (DEBUG_INFO) بصمت دون وصولها إلى Kotlin.
    if (type === "DEBUG_INFO") {
        logLocal('🔍 DEBUG_INFO: ' + (message.info || ''));
        
        sendToNative({
            type: "DEBUG_INFO",
            info: message.info || ""
        }).catch(function (e) {
            logLocal('❌ DEBUG_INFO failed: ' + e.message);
        });
        
        sendResponse({ ok: true });
        return false;
    }

    // ────────────────────────────────────────────────────────────────
    // Fallback: أي نوع غير معروف
    // ────────────────────────────────────────────────────────────────
    // 🆕 لمنع إسقاط أي رسالة مستقبلية بصمت دون أي أثر أو استجابة،
    // نمرّرها كما هي إلى الجهة الأصلية (Kotlin) لأغراض التشخيص.
    logLocal('⚠️ UNKNOWN_TYPE: ' + type);
    
    sendToNative({
        type:    "UNKNOWN_TYPE",
        original: type,
        payload: JSON.stringify(message)
    }).catch(function (e) {
        logLocal('❌ UNKNOWN_TYPE handler failed: ' + e.message);
    });
    
    sendResponse({ error: 'Unknown message type: ' + type });
    return false;
});

// ══════════════════════════════════════════════════════════════════
// Event: تحديث الامتداد
// ══════════════════════════════════════════════════════════════════

browser.runtime.onInstalled.addListener(function (details) {
    if (details.reason === "install") {
        logLocal('🎉 Extension installed');
    } else if (details.reason === "update") {
        logLocal('🔄 Extension updated from ' + details.previousVersion + ' to current');
    }
});

// ══════════════════════════════════════════════════════════════════
// رسالة البداية
// ══════════════════════════════════════════════════════════════════

logLocal('✅ Background script loaded successfully');
