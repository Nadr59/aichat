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

function sendToActiveTab(message) {

    return browser.tabs.query({
        active: true,
        currentWindow: true
    }).then(function (tabs) {

        logLocal(
            "🔎 Active tabs: " +
            tabs.length
        );

        if (!tabs || tabs.length === 0) {
            throw new Error(
                "NO_ACTIVE_TAB"
            );
        }

        var tabId =
            tabs[0].id;

        logLocal(
            "📤 Background → Content: " +
            "tab=" +
            tabId +
            ", type=" +
            message.type
        );

        return browser.tabs.sendMessage(
            tabId,
            message
        );
    });
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

        nativePort.onMessage.addListener(
            function (message) {

                logLocal(
                    "🚨 RECEIVED FROM KOTLIN: " +
                    JSON.stringify(message)
                );

                if (
                    !message ||
                    !message.type
                ) {
                    return;
                }

                /*
                 * المسار الرئيسي الجديد:
                 *
                 * Kotlin
                 *   ↓
                 * background.js
                 *   ↓
                 * content.js
                 */
                if (
                    message.type ===
                    "CONTEXT_TO_PAGE"
                ) {

                    sendToActiveTab({

                        type:
                            "CONTEXT_TO_PAGE",

                        text:
                            message.text ||
                            "",

                        contextId:
                            message.contextId ||
                            0,

                        domain:
                            message.domain ||
                            "",

                        timestamp:
                            message.timestamp ||
                            Date.now()

                    }).then(function (response) {

                        logLocal(
                            "✅ Content replied: " +
                            JSON.stringify(response)
                        );

                    }).catch(function (error) {

                        logLocal(
                            "❌ Background → Content FAILED: " +
                            (
                                error &&
                                error.message
                                    ? error.message
                                    : String(error)
                            )
                        );

                        /*
                         * إبلاغ Kotlin بالفشل.
                         */
                        if (nativePort) {

                            try {

                                nativePort.postMessage({

                                    type:
                                        "CONTEXT_WRITTEN",

                                    success:
                                        false,

                                    stage:
                                        "background_to_content_failed",

                                    detail:
                                        error &&
                                        error.message
                                            ? error.message
                                            : String(error),

                                    contextId:
                                        message.contextId ||
                                        0

                                });

                            } catch (e) {

                                logLocal(
                                    "❌ Failed reporting error to Kotlin"
                                );
                            }
                        }
                    });

                    return;
                }

                /*
                 * اختبار الاتجاه القديم.
                 * نتركه للتوافق فقط.
                 */
                if (
                    message.type ===
                    "REVERSE_TEST"
                ) {

                    sendToActiveTab({

                        type:
                            "REVERSE_TEST",

                        text:
                            message.text ||
                            "HELLO_FROM_KOTLIN",

                        original:
                            message

                    }).then(function (response) {

                        logLocal(
                            "✅ Reverse Content replied: " +
                            JSON.stringify(response)
                        );

                    }).catch(function (error) {

                        logLocal(
                            "❌ Reverse Background → Content FAILED: " +
                            (
                                error &&
                                error.message
                                    ? error.message
                                    : String(error)
                            )
                        );
                    });

                    return;
                }
            }
        );

        nativePort.onDisconnect.addListener(
            function () {

                logLocal(
                    "⚠️ Native Port disconnected"
                );

                nativePort =
                    null;

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

        nativePort.postMessage({

            type:
                "REVERSE_TEST_READY",

            source:
                "background.js",

            timestamp:
                new Date().toISOString()

        });

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

browser.runtime.onMessage.addListener(
    function (
        message,
        sender
    ) {

        if (
            !message ||
            !message.type
        ) {

            return Promise.resolve({
                ok: false
            });
        }

        /*
         * PING القديم.
         */
        if (
            message.type ===
            "PING"
        ) {

            return browser.runtime
                .sendNativeMessage(
                    NATIVE_APP,
                    {
                        type:
                            "PING"
                    }
                )
                .then(
                    function (response) {

                        return {
                            ok:
                                true,

                            pong:
                                true,

                            native:
                                response
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
                            ok:
                                false,

                            pong:
                                false
                        };
                    }
                );
        }

        /*
         * أي رسالة أخرى من content.js
         * تمر إلى Kotlin عبر Native Port.
         *
         * هذا يشمل:
         * CONTEXT_WRITTEN
         * CONTEXT_CONSUMED
         * DEBUG_INFO
         * CAPTURE_RESULT
         * AI_RESPONSE
         */
        if (nativePort) {

            logLocal(
                "↗️ Content → Kotlin: " +
                JSON.stringify(message)
            );

            try {

                nativePort.postMessage(
                    message
                );

            } catch (error) {

                logLocal(
                    "❌ Content → Kotlin FAILED: " +
                    (
                        error &&
                        error.message
                            ? error.message
                            : String(error)
                    )
                );

                return Promise.resolve({
                    ok:
                        false
                });
            }

        } else {

            logLocal(
                "❌ Content → Kotlin FAILED: " +
                "Native Port unavailable"
            );

            return Promise.resolve({
                ok:
                    false
            });
        }

        return Promise.resolve({
            ok:
                true
        });
    }
);

logLocal(
    "✅ Background script ready"
);

connectToNative();
