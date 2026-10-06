"use strict";

var NATIVE_APP = "browser";
var nativePort = null;

// ============================================================
// Current platform tab
// ============================================================

var currentPlatformTabId = null;


function logLocal(message) {
    var ts = new Date().toLocaleTimeString("en-US", {
        hour12: false
    });

    console.log("[Background] " + ts + "  " + message);
}


// ============================================================
// Find current platform tab
// ============================================================

function findActivePlatformTab() {

    if (currentPlatformTabId !== null) {

        logLocal(
            "🎯 Using current platform tab: " +
            currentPlatformTabId
        );

        return browser.tabs.get(
            currentPlatformTabId
        ).then(function (tab) {

            if (!tab) {
                throw new Error("NO_PLATFORM_TAB");
            }

            logLocal(
                "✅ Platform tab found: " +
                tab.id +
                " url=" +
                (tab.url || "")
            );

            return tab;

        }).catch(function (error) {

            logLocal(
                "⚠️ Saved platform tab unavailable: " +
                (
                    error &&
                    error.message
                        ? error.message
                        : String(error)
                )
            );

            currentPlatformTabId = null;

            throw new Error("NO_PLATFORM_TAB");
        });
    }

    throw new Error("NO_PLATFORM_TAB");
}


// ============================================================
// ChatGPT tab — kept for legacy tests
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

        for (var i = 0; i < tabs.length; i++) {

            if (tabs[i].active) {

                logLocal(
                    "🎯 Using active ChatGPT tab: " +
                    tabs[i].id
                );

                return tabs[i];
            }
        }

        logLocal(
            "🎯 Using first ChatGPT tab: " +
            tabs[0].id
        );

        return tabs[0];
    });
}


// ============================================================
// Send CONTEXT_TO_PAGE to current platform
// ============================================================

function sendToActivePlatform(message) {

    return findActivePlatformTab()
        .then(function (tab) {

            logLocal(
                "📤 Background → Content: " +
                "tab=" +
                tab.id +
                " url=" +
                (tab.url || "")
            );

            return browser.tabs.sendMessage(
                tab.id,
                {
                    type: "CONTEXT_TO_PAGE",

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
// Manual capture
//
// Kotlin
//   ↓
// Native Port
//   ↓
// Background
//   ↓
// Content
// ============================================================

function sendManualCaptureToActivePlatform() {

    return findActivePlatformTab()
        .then(function (tab) {

            logLocal(
                "📤 MANUAL_CAPTURE → Content: " +
                "tab=" +
                tab.id +
                " url=" +
                (tab.url || "")
            );

            return browser.tabs.sendMessage(
                tab.id,
                {
                    type: "MANUAL_CAPTURE",
                    timestamp: Date.now()
                }
            );
        })
        .then(function (response) {

            logLocal(
                "✅ MANUAL_CAPTURE Content replied: " +
                JSON.stringify(response)
            );

            return response;
        });
}


// ============================================================
// Report manual capture failure to Kotlin
// ============================================================

function reportManualCaptureFailure(
    detail
) {

    if (!nativePort) {

        logLocal(
            "❌ Cannot report manual capture failure: Native Port unavailable"
        );

        return;
    }

    try {

        nativePort.postMessage({

            type: "CAPTURE_RESULT",

            success: false,

            text: "",

            debug: {
                reason: detail
            }
        });

        logLocal(
            "📤 CAPTURE_RESULT failure → Kotlin: " +
            detail
        );

    } catch (error) {

        logLocal(
            "❌ Failed to report capture failure: " +
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
                // CONTEXT_TO_PAGE
                // ------------------------------------------------

                if (
                    message &&
                    message.type ===
                        "CONTEXT_TO_PAGE"
                ) {

                    logLocal(
                        "📥 CONTEXT_TO_PAGE received from Kotlin"
                    );

                    sendToActivePlatform(message)

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
                // MANUAL_CAPTURE
                // ------------------------------------------------

                if (
                    message &&
                    message.type ===
                        "MANUAL_CAPTURE"
                ) {

                    logLocal(
                        "📥 MANUAL_CAPTURE received from Kotlin"
                    );

                    sendManualCaptureToActivePlatform(
                    )

                        .then(function () {

                            logLocal(
                                "✅ MANUAL_CAPTURE delivered to content.js"
                            );

                        })

                        .catch(function (error) {

                            var detail =
                                error &&
                                error.message
                                    ? error.message
                                    : String(error);

                            logLocal(
                                "❌ MANUAL_CAPTURE → Content FAILED: " +
                                detail
                            );

                            reportManualCaptureFailure(
                                detail
                            );
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
        // CONTENT_READY
        // ----------------------------------------------------

        if (
            message.type ===
                "CONTENT_READY"
        ) {

            if (
                sender &&
                sender.tab &&
                sender.tab.id !== undefined
            ) {

                currentPlatformTabId =
                    sender.tab.id;

                logLocal(
                    "📌 CONTENT_READY: saved platform tab=" +
                    currentPlatformTabId +
                    " url=" +
                    (
                        sender.tab.url ||
                        message.url ||
                        ""
                    )
                );

            } else {

                logLocal(
                    "⚠️ CONTENT_READY received without sender.tab.id"
                );
            }
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
