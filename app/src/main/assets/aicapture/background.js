"use strict";

var NATIVE_APP = "browser";

// ============================================================
// Message types forwarded to Kotlin
// ============================================================

var FORWARD_TYPES = {
AI_RESPONSE: true,
CAPTURE_RESULT: true,
CONTEXT_WRITTEN: true,
CONTEXT_CONSUMED: true,
DEBUG_INFO: true
};

// ============================================================
// Quiet message types
// ============================================================

var QUIET_TYPES = {
POLL: true,
DEBUG_INFO: true
};

// ============================================================
// Empty POLL response
// ============================================================

var EMPTY_POLL = {
capture: false,
hasContext: false,
context: "",
id: 0
};

// ============================================================
// Logging
// ============================================================

function logLocal(message) {

var timestamp = new Date().toLocaleTimeString("en-US", {
    hour12: false,
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit"
});

console.log(
    "[Background] " +
    timestamp +
    "  " +
    message
);

}

var lastPollError = "";

function logPollErrorOnce(msg) {

if (msg !== lastPollError) {

    lastPollError = msg;

    logLocal(
        "❌ POLL error: " +
        msg
    );
}

}

// ============================================================
// Normalize Native response
// ============================================================

function normalize(response) {

if (typeof response === "string") {

    try {
        return JSON.parse(response);
    } catch (e) {
        return response;
    }
}

return response;

}

// ============================================================
// Send to GeckoView / Kotlin
// ============================================================

function sendToNative(payload) {

var quiet =
    !!QUIET_TYPES[payload.type];

if (!quiet) {

    logLocal(
        "🔄 → native: " +
        payload.type
    );
}

return browser.runtime
    .sendNativeMessage(
        NATIVE_APP,
        payload
    )

    .then(function (response) {

        var normalized =
            normalize(response);

        if (!quiet) {

            logLocal(
                "✅ native replied: " +
                payload.type +
                " → " +
                JSON.stringify(normalized)
            );
        }

        return normalized;
    })

    .catch(function (error) {

        var message =
            (error && error.message)
                ? error.message
                : String(error);

        logLocal(
            "❌ native failed: " +
            payload.type +
            " — " +
            message
        );

        throw error;
    });

}

// ============================================================
// Message Listener
// ============================================================

browser.runtime.onMessage.addListener(
function (message, sender) {

    if (!message || !message.type) {

        logLocal(
            "❌ Message without type"
        );

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

    // ====================================================
    // POLL
    // ====================================================

    if (type === "POLL") {

        return sendToNative({

            type: "POLL",

            domain:
                message.domain || "",

            visible:
                message.visible !== false
        })

        .then(function (r) {

            r = r || {};

            lastPollError = "";

            return {

                ok: true,

                capture:
                    !!r.capture,

                hasContext:
                    !!r.hasContext,

                context:
                    r.context || "",

                id:
                    r.id || 0
            };
        })

        .catch(function (e) {

            logPollErrorOnce(
                (e && e.message)
                    ? e.message
                    : String(e)
            );

            return EMPTY_POLL;
        });
    }

    // ====================================================
    // Forward messages to Kotlin
    // ====================================================

    if (FORWARD_TYPES[type]) {

        return sendToNative(message)

            .then(function (response) {

                return {
                    ok: true,
                    native: true,
                    response:
                        normalize(response)
                };
            })

            .catch(function (error) {

                return {
                    ok: false,
                    native: false,
                    error:
                        error &&
                        error.message
                            ? error.message
                            : String(error)
                };
            });
    }

    // ====================================================
    // Native diagnostic test
    // ====================================================

    if (type === "AICHAT_NATIVE_TEST") {

        logLocal(
            "🧪 AICHAT_NATIVE_TEST received"
        );

        var payload = {

            type: "AICHAT_NATIVE_TEST",

            source:
                message.source ||
                "content.js",

            timestamp:
                message.timestamp ||
                new Date().toLocaleTimeString(),

            test: true
        };

        logLocal(
            "🧪 Sending diagnostic test to Kotlin"
        );

        return sendToNative(payload)

            .then(function (response) {

                var normalized =
                    normalize(response);

                logLocal(
                    "🎯 Kotlin response received: " +
                    JSON.stringify(normalized)
                );

                return {

                    ok: true,

                    native: true,

                    response:
                        normalized
                };
            })

            .catch(function (error) {

                var errorMessage =
                    error &&
                    error.message
                        ? error.message
                        : String(error);

                logLocal(
                    "❌ Kotlin response failed: " +
                    errorMessage
                );

                return {

                    ok: false,

                    native: false,

                    error:
                        errorMessage
                };
            });
    }

    // ====================================================
    // Unknown message
    // ====================================================

    logLocal(
        "⚠️ UNKNOWN_TYPE: " +
        type
    );

    return Promise.resolve({

        ok: false,

        error:
            "Unknown message type: " +
            type
    });
}

);

// ============================================================
// Extension Events
// ============================================================

browser.runtime.onInstalled.addListener(
function (details) {

    if (details.reason === "install") {

        logLocal(
            "🎉 Extension installed"
        );

    } else if (
        details.reason === "update"
    ) {

        logLocal(
            "🔄 Extension updated"
        );

    } else {

        logLocal(
            "ℹ️ onInstalled: " +
            details.reason
        );
    }
}

);

// ============================================================
// Startup
// ============================================================

logLocal(
"✅ Background script ready, v=" +
browser.runtime.getManifest().version
);

// ============================================================
// Native channel diagnostic
// ============================================================

logLocal(
"🧪 Testing Native Messaging..."
);

sendToNative({

type: "PING"

})

.then(function (response) {

logLocal(
    "🏓 PING reply: " +
    JSON.stringify(response)
);

})

.catch(function (error) {

logLocal(
    "🏓 PING failed: " +
    (
        error &&
        error.message
            ? error.message
            : String(error)
    )
);

});
