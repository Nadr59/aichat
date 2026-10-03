"use strict";

var NATIVE_APP = "browser";
var nativePort = null;

function logLocal(message) {
    var ts = new Date().toLocaleTimeString("en-US", { hour12: false });
    console.log("[Background] " + ts + "  " + message);
}

function connectToNative() {
    try {
        logLocal("🔌 Connecting to Native: " + NATIVE_APP);

        nativePort = browser.runtime.connectNative(NATIVE_APP);

        logLocal("✅ Native Port created");

        nativePort.onMessage.addListener(function (message) {
            logLocal("🚨 RECEIVED FROM KOTLIN: " + JSON.stringify(message));

            // في هذا الاختبار لا نرسل أي شيء إلى الصفحة.
            // نريد فقط إثبات وصول رسالة Kotlin إلى Background.
        });

        nativePort.onDisconnect.addListener(function () {
            logLocal("⚠️ Native Port disconnected");

            nativePort = null;

            setTimeout(function () {
                logLocal("🔄 Reconnecting...");
                connectToNative();
            }, 1000);
        });

        var readyMessage = {
            type: "REVERSE_TEST_READY",
            source: "background.js",
            timestamp: new Date().toISOString()
        };

        nativePort.postMessage(readyMessage);

        logLocal(
            "📤 Background → Kotlin: REVERSE_TEST_READY"
        );

    } catch (error) {
        logLocal(
            "❌ connectNative failed: " +
            (error && error.message ? error.message : String(error))
        );
    }
}

browser.runtime.onMessage.addListener(function (message, sender) {

    if (!message || !message.type) {
        return Promise.resolve({ ok: false });
    }

    // الحفاظ على المسار الأمامي Content → Background → Kotlin
    if (nativePort && message.type !== "PING") {
        logLocal(
            "↗️ Forwarding Content → Kotlin: " +
            JSON.stringify(message)
        );

        try {
            nativePort.postMessage(message);
        } catch (e) {
            logLocal(
                "❌ Content → Kotlin FAILED: " +
                (e && e.message ? e.message : String(e))
            );
        }
    }

    if (message.type === "PING") {
        return browser.runtime
            .sendNativeMessage(NATIVE_APP, {
                type: "PING"
            })
            .then(function (response) {
                return {
                    ok: true,
                    pong: true,
                    native: response
                };
            });
    }

    return Promise.resolve({ ok: true });
});

logLocal("✅ Background script ready");

connectToNative();
