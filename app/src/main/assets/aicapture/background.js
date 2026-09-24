"use strict";

var NATIVE_APP = "browser";

browser.runtime.onMessage.addListener(function (message, sender) {
    if (!message || !message.type) return;

    var type = message.type;

    if (type === "AI_RESPONSE") {
        browser.runtime.sendNativeMessage(NATIVE_APP, {
            type:   "AI_RESPONSE",
            text:   message.text   || "",
            domain: message.domain || ""
        });
        return;
    }

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

    // ── جديد: GET_CONTEXT ─────────────────────────────────────────────
    if (type === "GET_CONTEXT") {
        return browser.runtime.sendNativeMessage(NATIVE_APP, {
            type:   "GET_CONTEXT",
            domain: message.domain || ""
        }).then(function (response) {
            return response;
        }).catch(function () {
            return { hasContext: false, context: "" };
        });
    }

    // ── جديد: CONTEXT_WRITTEN ─────────────────────────────────────────
    if (type === "CONTEXT_WRITTEN") {
        browser.runtime.sendNativeMessage(NATIVE_APP, {
            type:   "CONTEXT_WRITTEN",
            stage:  message.stage  || "",
            detail: message.detail || "",
            domain: message.domain || ""
        });
        return;
    }
});
