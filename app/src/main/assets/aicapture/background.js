"use strict";

var NATIVE_APP = "browser";

browser.runtime.onMessage.addListener(function (message, sender) {
    if (!message || !message.type) return;

    var type = message.type;

    // ── التقاط تلقائي ─────────────────────────────────────────────────

    if (type === "AI_RESPONSE") {
        browser.runtime.sendNativeMessage(NATIVE_APP, {
            type:   "AI_RESPONSE",
            text:   message.text   || "",
            domain: message.domain || ""
        });
        return;
    }

    // ── التقاط يدوي — CHECK_CAPTURE ───────────────────────────────────

    if (type === "CHECK_CAPTURE") {
        return browser.runtime.sendNativeMessage(NATIVE_APP, {
            type:   "CHECK_CAPTURE",
            domain: message.domain || ""
        }).then(function (response) {
            return response;
        }).catch(function () {
            return { capture: false };
        });
    }

    if (type === "CAPTURE_RESULT") {
        browser.runtime.sendNativeMessage(NATIVE_APP, {
            type:    "CAPTURE_RESULT",
            success: message.success || false,
            text:    message.text    || "",
            domain:  message.domain  || "",
            debug:   message.debug   || null
        });
        return;
    }

    // ── إرسال السياق — GET_CONTEXT ────────────────────────────────────

    if (type === "GET_CONTEXT") {
        return browser.runtime.sendNativeMessage(NATIVE_APP, {
            type: "GET_CONTEXT"
        }).then(function (response) {
            return response;
        }).catch(function () {
            return { hasContext: false, context: "" };
        });
    }

    // ── نتيجة الكتابة ─────────────────────────────────────────────────

    if (type === "CONTEXT_WRITTEN") {
        browser.runtime.sendNativeMessage(NATIVE_APP, {
            type:   "CONTEXT_WRITTEN",
            stage:  message.stage  || "",
            detail: message.detail || "",
            len:    message.len    || 0,
            domain: message.domain || ""
        });
        return;
    }

    // ── معلومات تشخيصية ───────────────────────────────────────────────

    if (type === "DEBUG_INFO") {
        browser.runtime.sendNativeMessage(NATIVE_APP, {
            type: "DEBUG_INFO",
            info: message.info || ""
        });
        return;
    }

    if (type === "DEBUG_BUTTONS") {
        browser.runtime.sendNativeMessage(NATIVE_APP, {
            type:    "DEBUG_BUTTONS",
            buttons: message.buttons || [],
            domain:  message.domain  || ""
        });
        return;
    }
});
