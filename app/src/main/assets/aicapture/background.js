"use strict";

var NATIVE_APP = "browser";
var nativePort = null;

function logLocal(message) {
    var ts = new Date().toLocaleTimeString("en-US", {
        hour12: false
    });

    console.log(
        "[Background] " +
        ts +
        "  " +
        message
    );
}

function connectToNative() {

    try {

        logLocal(
            "🔌 Connecting to Native: " +
            NATIVE_APP
        );

        nativePort =
            browser.runtime.connectNative(
                NATIVE_APP
            );

        logLocal(
            "✅ Native Port created"
        );

        /*
         * ========================================================
         * Kotlin → Background
         * ========================================================
         *
         * هذه هي القناة المقابلة لـ:
         *
         * port.postMessage(...)
         *
         * في AichatApp.kt
         */
        nativePort.onMessage.addListener(
            function (message) {

                logLocal(
                    "🚨 RECEIVED FROM KOTLIN: " +
                    JSON.stringify(message)
                );

                logLocal(
                    "🚨 TYPE: " +
                    typeof message +
                    " | KEYS: " +
                    (
                        message
                            ? Object.keys(message).join(",")
                            : "null"
                    )
                );

                /*
                 * Echo:
                 *
                 * إذا وصلت رسالة Kotlin هنا،
                 * نعيد رسالة إلى Kotlin عبر نفس Port.
                 *
                 * هذا يثبت أن القناة تعمل
                 * في الاتجاهين.
                 */
                try {

                    nativePort.postMessage({
                        type: "BACKGROUND_ECHO",
                        source: "background.js",
                        text: "HELLO_FROM_BACKGROUND",
                        original: message,
                        timestamp:
                            new Date().toISOString()
                    });

                    logLocal(
                        "📤 Background → Kotlin: " +
                        "ECHO SENT"
                    );

                } catch (e) {

                    logLocal(
                        "❌ Background ECHO FAILED: " +
                        (
                            e &&
                            e.message
                                ? e.message
                                : String(e)
                        )
                    );
                }
            }
        );

        /*
         * ========================================================
         * Native Port disconnect
         * ========================================================
         */
        nativePort.onDisconnect.addListener(
            function () {

                logLocal(
                    "⚠️ Native Port disconnected"
                );

                nativePort = null;

                setTimeout(
                    function () {

                        logLocal(
                            "🔄 Reconnecting..."
                        );

                        connectToNative();

                    },
                    1000
                );
            }
        );

        /*
         * ========================================================
         * Background → Kotlin
         * ========================================================
         *
         * رسالة أولية للتأكد أن الاتصال يعمل.
         */
        var readyMessage = {
            type: "REVERSE_TEST_READY",
            source: "background.js",
            timestamp:
                new Date().toISOString()
        };

        nativePort.postMessage(
            readyMessage
        );

        logLocal(
            "📤 Background → Kotlin: " +
            "REVERSE_TEST_READY"
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
 * ================================================================
 * Content → Background
 * ================================================================
 *
 * نحافظ على المسار الأمامي الموجود في المشروع:
 *
 * content.js
 *      ↓
 * browser.runtime.sendMessage()
 *      ↓
 * background.js
 *      ↓
 * nativePort.postMessage()
 *      ↓
 * Kotlin
 */
browser.runtime.onMessage.addListener(
    function (message, sender) {

        if (
            !message ||
            !message.type
        ) {
            return Promise.resolve({
                ok: false
            });
        }

        /*
         * Content → Kotlin
         *
         * لا نرسل PING هنا لأن PING له
         * مسار Native منفصل أدناه.
         */
        if (
            nativePort &&
            message.type !== "PING"
        ) {

            logLocal(
                "↗️ Forwarding Content → Kotlin: " +
                JSON.stringify(message)
            );

            try {

                nativePort.postMessage(
                    message
                );

            } catch (e) {

                logLocal(
                    "❌ Content → Kotlin FAILED: " +
                    (
                        e &&
                        e.message
                            ? e.message
                            : String(e)
                    )
                );
            }
        }

        /*
         * ========================================================
         * PING
         * ========================================================
         */
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
                .then(
                    function (response) {

                        return {
                            ok: true,
                            pong: true,
                            native: response
                        };
                    }
                )
                .catch(
                    function (error) {

                        logLocal(
                            "❌ PING failed: " +
                            (
                                error &&
                                error.message
                                    ? error.message
                                    : String(error)
                            )
                        );

                        return {
                            ok: false,
                            pong: false
                        };
                    }
                );
        }

        return Promise.resolve({
            ok: true
        });
    }
);


/*
 * ================================================================
 * Start
 * ================================================================
 */

logLocal(
    "✅ Background script ready"
);

connectToNative();
