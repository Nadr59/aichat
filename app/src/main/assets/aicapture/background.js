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
 * Kotlin → Background → Content TEST
 * ============================================================
 */

function connectToNative() {

    try {

        logLocal(
            "🔌 Connecting to Kotlin Native Port..."
        );

        nativePort =
            browser.runtime.connectNative(
                NATIVE_APP
            );

        logLocal(
            "✅ Native Port connected"
        );

        nativePort.onMessage.addListener(
            function (message) {

                logLocal(
                    "📩 Kotlin → Background: " +
                    JSON.stringify(message)
                );

                /*
                 * الآن نرسل الرسالة إلى الصفحة الحالية
                 */
                browser.tabs.query({
                    active: true,
                    currentWindow: true
                })
                .then(function (tabs) {

                    if (
                        !tabs ||
                        tabs.length === 0
                    ) {

                        logLocal(
                            "❌ No active tab"
                        );

                        return;
                    }

                    var tabId =
                        tabs[0].id;

                    logLocal(
                        "📤 Background → Content, tab=" +
                        tabId
                    );

                    return browser.tabs.sendMessage(
                        tabId,
                        message
                    );

                })
                .then(function () {

                    logLocal(
                        "✅ Message delivered to content.js"
                    );

                })
                .catch(function (error) {

                    logLocal(
                        "❌ Background → Content failed: " +
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

                /*
                 * لا نعيد الاتصال تلقائيًا في هذا الاختبار.
                 */
            }
        );

        /*
         * نرسل رسالة من Background إلى Kotlin
         * حتى نتأكد أن Port يعمل في الاتجاهين.
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
 * Existing one-way Native Messaging test
 * ============================================================
 */

browser.runtime.onMessage.addListener(
    function (message, sender) {

        if (
            !message ||
            !message.type
        ) {

            return Promise.resolve({
                ok: false,
                error: "No message type"
            });
        }

        var type =
            message.type;

        logLocal(
            "📩 ← content: " +
            type
        );

        /*
         * نُبقي الاختبار القديم حتى لا نحذف
         * آلية الاتصال الحالية.
         */
        if (type === "PING") {

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
            error: "Unknown test message: " + type
        });
    }
);


/*
 * بدء الاتصال
 */
logLocal(
    "✅ Background script ready"
);

connectToNative();
