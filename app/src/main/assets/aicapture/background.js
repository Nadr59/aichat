"use strict";

var NATIVE_APP = "browser";
var nativePort = null;

function logLocal(message) {
    var ts = new Date().toLocaleTimeString("en-US", {
        hour12: false
    });

    console.log("[Background] " + ts + "  " + message);
}


// ============================================================
// Find ChatGPT tab
// ============================================================

function findChatGPTTab() {

    return browser.tabs.query({
        url: [
            "https://chatgpt.com/*",
            "https://chat.openai.com/*"
        ]
    }).then(function (tabs) {

        logLocal(
            "🔎 ChatGPT tabs found: " +
            tabs.length
        );

        if (!tabs || tabs.length === 0) {
            throw new Error("NO_CHATGPT_TAB");
        }

        // Prefer the active tab if one exists.
        for (var i = 0; i < tabs.length; i++) {
            if (tabs[i].active) {
                logLocal(
                    "🎯 Using active ChatGPT tab: " +
                    tabs[i].id
                );

                return tabs[i];
            }
        }

        // Otherwise use the first ChatGPT tab.
        logLocal(
            "🎯 Using first ChatGPT tab: " +
            tabs[0].id
        );

        return tabs[0];
    });
}


// ============================================================
// Send message to ChatGPT content.js
// ============================================================

function sendToChatGPT(message) {

    return findChatGPTTab()
        .then(function (tab) {

            logLocal(
                "📤 Background → Content: tab=" +
                tab.id
            );

            return browser.tabs.sendMessage(
                tab.id,
                {
                    type: "CONTEXT_TO_PAGE",

                    text: message.text || "",

                    contextId:
                        message.contextId || 0,

                    domain:
                        message.domain || "",

                    timestamp:
                        message.timestamp ||
                        Date.now()
                }
            );
        })
        .then(function (response) {

            logLocal(
                "✅ Content replied: " +
                JSON.stringify(response)
            );

            return response;
        });
}


// ============================================================
// Connect Native Messaging
// ============================================================

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


        // ====================================================
        // Kotlin → Background
        // ====================================================

        nativePort.onMessage.addListener(
            function (message) {

                logLocal(
                    "🚨 RECEIVED FROM KOTLIN: " +
                    JSON.stringify(message)
                );


                // ------------------------------------------------
                // DIRECT CONTEXT INJECTION
                // ------------------------------------------------

                if (
                    message &&
                    message.type ===
                        "CONTEXT_TO_PAGE"
                ) {

                    logLocal(
                        "📥 CONTEXT_TO_PAGE received from Kotlin"
                    );

                    sendToChatGPT(message)

                        .then(function () {

                            logLocal(
                                "✅ CONTEXT_TO_PAGE delivered"
                            );

                        })

                        .catch(function (error) {

                            var detail =
                                error &&
                                error.message
                                    ? error.message
                                    : String(error);

                            logLocal(
                                "❌ Background → Content FAILED: " +
                                detail
                            );


                            // Report failure back to Kotlin
                            if (nativePort) {

                                try {

                                    nativePort.postMessage({

                                        type:
                                            "CONTEXT_WRITTEN",

                                        success:
                                            false,

                                        detail:
                                            detail,

                                        contextId:
                                            message.contextId ||
                                            0,

                                        stage:
                                            "background_to_content_failed"
                                    });

                                } catch (postError) {

                                    logLocal(
                                        "❌ Failed to report error to Kotlin: " +
                                        (
                                            postError &&
                                            postError.message
                                                ? postError.message
                                                : String(postError)
                                        )
                                    );
                                }
                            }
                        });

                    return;
                }


                // ------------------------------------------------
                // OLD REVERSE TEST
                // ------------------------------------------------

                if (
                    message &&
                    message.type ===
                        "REVERSE_TEST"
                ) {

                    findChatGPTTab()

                        .then(function (tab) {

                            logLocal(
                                "📤 Reverse test → Content: tab=" +
                                tab.id
                            );

                            return browser.tabs.sendMessage(
                                tab.id,
                                {
                                    type:
                                        "REVERSE_TEST",

                                    text:
                                        message.text ||
                                        "HELLO_FROM_KOTLIN",

                                    original:
                                        message
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
            }
        );


        // ====================================================
        // Native Port disconnect
        // ====================================================

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


        // ====================================================
        // Ready message
        // ====================================================

        nativePort.postMessage({

            type:
                "REVERSE_TEST_READY",

            source:
                "background.js",

            timestamp:
                new Date().toISOString()
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


// ============================================================
// Content → Background
// ============================================================

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


        // ----------------------------------------------------
        // PING
        // ----------------------------------------------------

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

                .then(function (response) {

                    return {
                        ok: true,
                        pong: true,
                        native: response
                    };
                })

                .catch(function (error) {

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
                });
        }


        // ----------------------------------------------------
        // Other Content → Kotlin messages
        // ----------------------------------------------------

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
    }
);


// ============================================================
// Startup
// ============================================================

logLocal(
    "✅ Background script ready"
);

connectToNative();
