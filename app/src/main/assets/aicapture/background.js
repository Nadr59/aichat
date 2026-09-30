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
    
    // تجاهل تسجيل GET_CONTEXT — كثير جداً
    if (type !== "GET_CONTEXT") {
        logLocal('📨 Received: ' + type);
    }

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
    // CAPTURE_RESULT — نتيجة التقاط يدوي
    // ────────────────────────────────────────────────────────────────
    if (type === "CAPTURE_RESULT") {
        logLocal('📸 CAPTURE_RESULT: success=' + message.success + 
            ', text=' + (message.text ? message.text.substring(0, 50) + '...' : 'empty'));
        
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
    // 
    // 🆕 إصلاح حاسم:
    // 1. return true — للحفاظ على القناة مفتوحة للرد الغير متزامن
    // 2. JSON.parse — في حالة الـ Native ترسل string بدل object
    // 3. requestId passthrough — للتحقق من مطابقة الطلب والرد
    // ────────────────────────────────────────────────────────────────
    if (type === "GET_CONTEXT") {
        var requestId = message.requestId || 0;
        
        sendToNative({
            type:   "GET_CONTEXT",
            domain: message.domain || ""
        }).then(function (response) {
            // تأكد أن response هو object وليس string
            var data = response;
            if (typeof response === 'string') {
                logLocal('⚠️ GET_CONTEXT (id=' + requestId + '): response is string, parsing...');
                try {
                    data = JSON.parse(response);
                } catch (e) {
                    logLocal('❌ GET_CONTEXT (id=' + requestId + '): JSON.parse error: ' + e.message);
                    data = { hasContext: false, context: "", requestId: requestId };
                }
            }
            
            if (data && data.hasContext) {
                logLocal('✅ GET_CONTEXT (id=' + requestId + '): has=true, len=' + 
                    (data.context ? data.context.length : 0) +
                    ', contextId=' + (data.id || '?'));
            }
            
            // 🆕 أضف requestId إلى الرد
            if (data && !data.hasOwnProperty('requestId')) {
                data.requestId = requestId;
            }
            
            sendResponse(data || { hasContext: false, context: "", requestId: requestId });
        }).catch(function (e) {
            logLocal('❌ GET_CONTEXT (id=' + requestId + ') error: ' + e.message);
            sendResponse({ 
                hasContext: false, 
                context: "", 
                requestId: requestId,
                error: e.message
            });
        });
        
        return true; // 🔴 حاسم جداً — الحفاظ على القناة مفتوحة
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
    // CONTEXT_CONSUMED — إخبار الـ Kotlin بأن السياق تم استهلاكه
    // 🆕 منع التكرار اللانهائي
    // ────────────────────────────────────────────────────────────────
    if (type === "CONTEXT_CONSUMED") {
        logLocal('🗑️ CONTEXT_CONSUMED: contextId=' + (message.contextId || '?'));
        
        sendToNative({
            type:      "CONTEXT_CONSUMED",
            contextId: message.contextId || 0
        }).catch(function (e) {
            logLocal('❌ CONTEXT_CONSUMED failed: ' + e.message);
        });
        
        sendResponse({ ok: true });
        return false;
    }

    // ────────────────────────────────────────────────────────────────
    // DEBUG_INFO — رسائل التشخيص من content.js
    // ────────────────────────────────────────────────────────────────
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
    logLocal('⚠️ UNKNOWN_TYPE: ' + type);
    
    sendToNative({
        type:     "UNKNOWN_TYPE",
        original: type,
        payload:  JSON.stringify(message)
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
        logLocal('🎉 Extension installed successfully');
    } else if (details.reason === "update") {
        logLocal('🔄 Extension updated to current version');
    }
});

// ══════════════════════════════════════════════════════════════════
// رسالة البداية
// ══════════════════════════════════════════════════════════════════

logLocal('✅ Background script loaded successfully');
