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
    if (type === "DEBUG_BUTTONS") {
    browser.runtime.sendNativeMessage(NATIVE_APP, {
        type:    "DEBUG_BUTTONS",
        buttons: message.buttons || [],
        domain:  message.domain  || ""
    });
    return;
    }

    if (type === "CHECK_CAPTURE") {
        return browser.runtime.sendNativeMessage(NATIVE_APP, {
            type:   "CHECK_CAPTURE",
            domain: message.domain || ""
        }).then(function (response) {
            return response;
        }).catch(function (e) {
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
});
