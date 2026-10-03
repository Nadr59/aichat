"use strict";

var NATIVE_APP = "browser";
var nativePort = null;

function logLocal(message) {
    var ts = new Date().toLocaleTimeString("en-US", {
        hour12: false
    });
    console.log("[Background] " + ts + "  " + message);
}

function connectToNative() {
    try {
        logLocal("🔌 Connecting to Native: " + NATIVE_APP);

        nativePort = browser.runtime.connectNative(NATIVE_APP);

        logLocal("✅ Native Port created");

        nativePort.onMessage.addListener(function (message) {
            logLocal(
                "🚨 RECEIVED FROM KOTLIN: " +
                JSON.stringify(message)
            );

            if (message && message.type === "REVERSE_TEST") {
                browser.tabs.query({
                    active: true,
                    currentWindow: true
                }).then(function (tabs) {

                    logLocal(
                        "🔎 Active tabs: " +
                        tabs.length
                    );

                    if (!tabs || tabs.length === 0) {
                        throw new Error("NO_ACTIVE_TAB");
                    }

                    var tabId = tabs[0].id;

                    logLocal(
                        "📤 Background → Content: tab=" +
                        tabId
                    );

                    return browser.tabs.sendMessage(
                        tabId,
                        {
                            type: "REVERSE_TEST",
                            text: message.text ||
                                "HELLO_FROM_KOTLIN",
                            original: message
                        }
                    );

                }).then(function (response) {

                    logLocal(
                        "✅ Content replied: " +
                        JSON.stringify(response)
                    );

                }).catch(function (error) {

                    logLocal(
                        "❌ Background → Content FAILED: " +
                        (
                            error && error.message
                                ? error.message
                                : String(error)
                        )
                    );
                });
            }
        });

        nativePort.onDisconnect.addListener(function () {
            logLocal("⚠️ Native Port disconnected");
            nativePort = null;

            setTimeout(function () {
                logLocal("🔄 Reconnecting...");
                connectToNative();
            }, 1000);
        });

        nativePort.postMessage({
            type: "REVERSE_TEST_READY",
            source: "background.js",
            timestamp: new Date().toISOString()
        });

        logLocal(
            "📤 Background → Kotlin: REVERSE_TEST_READY"
        );

    } catch (error) {
        logLocal(
            "❌ connectNative failed: " +
            (
                error && error.message
                    ? error.message
                    : String(error)
            )
        );
    }
}

browser.runtime.onMessage.addListener(function (
    message,
    sender
) {
    if (!message || !message.type) {
        return Promise.resolve({
            ok: false
        });
    }

    if (message.type === "PING") {
        return browser.runtime.sendNativeMessage(
            NATIVE_APP,
            {
                type: "PING"
            }
        ).then(function (response) {
            return {
                ok: true,
                pong: true,
                native: response
            };
        }).catch(function (error) {
            logLocal(
                "❌ PING failed: " +
                (
                    error && error.message
                        ? error.message
                        : String(error)
                )
            );

            return {
                ok: false,
                pong: false
            };
        });
    }

    if (nativePort) {
        logLocal(
            "↗️ Content → Kotlin: " +
            JSON.stringify(message)
        );

        try {
            nativePort.postMessage(message);
        } catch (error) {
            logLocal(
                "❌ Content → Kotlin FAILED: " +
                (
                    error && error.message
                        ? error.message
                        : String(error)
                )
            );
        }
    } else {
        logLocal(
            "❌ Content → Kotlin FAILED: " +
            "Native Port unavailable"
        );
    }

    return Promise.resolve({
        ok: true
    });
});

logLocal("✅ Background script ready");
connectToNative();
