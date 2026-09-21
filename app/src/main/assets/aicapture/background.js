"use strict";

var NATIVE_APP = "browser";
var port       = null;

// ── اتصال Port مع Kotlin ──────────────────────────────────────────

function connect() {
    port = browser.runtime.connectNative(NATIVE_APP);

    port.onMessage.addListener(function (msg) {
        // رسائل من Kotlin للـ background
        console.log("[bg] from Kotlin:", JSON.stringify(msg));
    });

    port.onDisconnect.addListener(function () {
        console.log("[bg] port disconnected — reconnecting...");
        port = null;
        setTimeout(connect, 1000);
    });
}

connect();

// ── استقبال من content.js ─────────────────────────────────────────

browser.runtime.onMessage.addListener(function (message, sender, sendResponse) {
    if (!message || !message.type) return;

    var type = message.type;

    // ── رسائل بدون رد ─────────────────────────────────────────────
    if (type === "AI_RESPONSE") {
        if (port) port.postMessage({
            type:   "AI_RESPONSE",
            text:   message.text   || "",
            domain: message.domain || ""
        });
        return;
    }

    if (type === "CAPTURE_RESULT") {
        if (port) port.postMessage({
            type:    "CAPTURE_RESULT",
            success: message.success || false,
            text:    message.text    || "",
            domain:  message.domain  || "",
            debug:   message.debug   || null
        });
        return;
    }

    if (type === "CONTEXT_WRITTEN") {
        if (port) port.postMessage({
            type:   "CONTEXT_WRITTEN",
            len:    message.len    || 0,
            domain: message.domain || ""
        });
        return;
    }

    if (type === "DEBUG_INFO") {
        if (port) port.postMessage({
            type:   "DEBUG_INFO",
            info:   message.info   || "",
            domain: message.domain || ""
        });
        return;
    }

    if (type === "DEBUG_BUTTONS") {
        if (port) port.postMessage({
            type:    "DEBUG_BUTTONS",
            buttons: message.buttons || [],
            domain:  message.domain  || ""
        });
        return;
    }

    // ── رسائل تحتاج رد — عبر sendNativeMessage ───────────────────

    if (type === "CHECK_CAPTURE") {
        browser.runtime.sendNativeMessage(NATIVE_APP, {
            type:   "CHECK_CAPTURE",
            domain: message.domain || ""
        }).then(function (r) {
            sendResponse(r);
        }).catch(function () {
            sendResponse({ capture: false });
        });
        return true; // ← مهم: يبقي القناة مفتوحة للرد
    }

    if (type === "GET_CONTEXT") {
        browser.runtime.sendNativeMessage(NATIVE_APP, {
            type:   "GET_CONTEXT",
            domain: message.domain || ""
        }).then(function (r) {
            sendResponse(r);
        }).catch(function (e) {
            console.log("[bg] GET_CONTEXT error:", e);
            sendResponse({ hasContext: false, context: "" });
        });
        return true; // ← مهم جداً
    }
});
