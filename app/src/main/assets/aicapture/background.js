"use strict";

var NATIVE_APP = "browser";
var nativePort = null;

function logLocal(message) {
    var ts = new Date().toLocaleTimeString("en-US", {
        hour12: false
    });

    console.log(
        "[Background] " + ts + "  " + message
    );
}

/*
 * ============================================================
 * Kotlin → Background → Content
 * ============================================================
 */

function connectToNative() {

    try {

        nativePort =
            browser.runtime.connectNative(
                NATIVE_APP
            );

        /*
         * استقبال الرسائل القادمة من Kotlin
         */
        nativePort.onMessage.addListener(
            function (message) {

                logLocal(
                    "🚨 RECEIVED FROM KOTLIN: " +
                    JSON.stringify(message)
                );

                /*
                 * إرسال الرسالة إلى التبويب النشط
                 */
                browser.tabs.query({
                    active: true,
                    currentWindow: true
                })
                .then(function (tabs) {

                    logLocal(
                        "🔎 Active tabs count: " +
                        tabs.length
                    );

                    if (
                        !tabs ||
                        tabs.length === 0
                    ) {
                        throw new Error(
                            "NO_ACTIVE_TAB"
                        );
                    }

                    var tabId =
                        tabs[0].id;

                    logLocal(
                        "📤 Sending to content, tab=" +
                        tabId
                    );

                    return browser.tabs.sendMessage(
                        tabId,
                        {
                            type: "KOTLIN_REVERSE_TEST",
                            text: "HELLO_FROM_KOTLIN",
                            original: message
                        }
                    );
                })
                .then(function (response) {

                    logLocal(
                        "✅ Content replied: " +
                        JSON.stringify(response)
                    );

                })
                .catch(function (error) {

                    logLocal(
                        "❌ Background → Content FAILED: " +
                        (
                            error &&
                            error.message
                                ? error.message
                                : String(error)
                        )
                    );
                });
            }
        );

        nativePort.onDisconnect.addListener(
            function () {

                logLocal(
                    "⚠️ Native Port disconnected"
                );

                nativePort = null;
            }
        );

        /*
         * أول رسالة من Background إلى Kotlin
         */
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
                error &&
                error.message
                    ? error.message
                    : String(error)
            )
        );
    }
}


/*
 * ============================================================
 * Content → Background → Kotlin
 * الاختبار القديم يبقى فقط للـ PING
 * ============================================================
 */

browser.runtime.onMessage.addListener(
    function (message) {

        if (
            !message ||
            !message.type
        ) {
            return Promise.resolve({
                ok: false,
                error: "No message type"
            });
        }

        if (
            message.type === "PING"
        ) {

            return browser.runtime
                .sendNativeMessage(
                    NATIVE_APP,
                    {
                        type: "PING"
                    }
                )
                .then(function (response) {

                    return {
                        ok: true,
                        pong: true,
                        native: response
                    };

                });
        }

        return Promise.resolve({
            ok: false,
            error:
                "Unknown message type: " +
                message.type
        });
    }
);


/*
 * ============================================================
 * Start
 * ============================================================
 */

logLocal(
    "✅ Background script ready"
);

connectToNative();
