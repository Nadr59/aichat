"use strict";

var NATIVE_APP = "browser";
var port       = null;

// ── اتصال Port مع Kotlin ──────────────────────────────────────────

function connect() {
    try {
        port = browser.runtime.connectNative(NATIVE_APP);
    } catch(e) {
        console.log("[bg] connectNative failed:", e);
        setTimeout(connect, 2000);
        return;
    }

    port.onMessage.addListener(function (msg) {
        console.log("[bg] from Kotlin port:", JSON.stringify(msg));
    });

    port.onDisconnect.addListener(function () {
        console.log("[bg] port disconnected");
        port = null;
        setTimeout(connect, 2000);
    });

    console.log("[bg] port connected ✅");
}

connect();

// ── استقبال من content.js ─────────────────────────────────────────

browser.runtime.onMessage.addListener(function (message, sender, sendResponse) {
    if (!message || !message.type) return;

    var type = message.type;
    console.log("[bg] from content:", type);

    // ── رسائل بدون رد — عبر Port ──────────────────────────────────

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

    // ── رسائل تحتاج رد — عبر Port أيضاً ─────────────────────────

    if (type === "CHECK_CAPTURE") {
        if (!port) {
            sendResponse({ capture: false });
            return;
        }

        // ✅ أرسل عبر Port وانتظر الرد
        var done = false;

        var listener = function (msg) {
            if (done) return;
            if (!msg || msg.type !== "CHECK_CAPTURE_RESULT") return;
            done = true;
            port.onMessage.removeListener(listener);
            sendResponse({ capture: msg.capture || false });
        };

        port.onMessage.addListener(listener);
        port.postMessage({
            type:   "CHECK_CAPTURE",
            domain: message.domain || ""
        });

        // timeout
        setTimeout(function () {
            if (done) return;
            done = true;
            port.onMessage.removeListener(listener);
            sendResponse({ capture: false });
        }, 3000);

        return true; // ← يبقي القناة مفتوحة
    }

    if (type === "GET_CONTEXT") {
        if (!port) {
            sendResponse({ hasContext: false, context: "" });
            return;
        }

        // ✅ أرسل عبر Port وانتظر الرد
        var done = false;

        var listener = function (msg) {
            if (done) return;
            if (!msg || msg.type !== "GET_CONTEXT_RESULT") return;
            done = true;
            port.onMessage.removeListener(listener);
            sendResponse({
                hasContext: msg.hasContext || false,
                context:    msg.context    || ""
            });
        };

        port.onMessage.addListener(listener);
        port.postMessage({
            type:   "GET_CONTEXT",
            domain: message.domain || ""
        });

        // timeout
        setTimeout(function () {
            if (done) return;
            done = true;
            port.onMessage.removeListener(listener);
            sendResponse({ hasContext: false, context: "" });
        }, 3000);

        return true; // ← يبقي القناة مفتوحة
    }
});
