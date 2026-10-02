(function () {

"use strict";

var timestamp =
    new Date().toLocaleTimeString();

console.log(
    "[AiChat CONTENT TEST] content.js EXECUTED at " +
    timestamp
);

var testElement = null;

// ============================================================
// Visible diagnostic box
// ============================================================

try {

    testElement =
        document.createElement("div");

    testElement.id =
        "aichat-native-test";

    testElement.textContent =
        "🧪 AiChat Native Test — " +
        timestamp;

    testElement.style.position =
        "fixed";

    testElement.style.top =
        "10px";

    testElement.style.left =
        "10px";

    testElement.style.right =
        "10px";

    testElement.style.zIndex =
        "2147483647";

    testElement.style.padding =
        "12px 16px";

    testElement.style.background =
        "rgba(0,0,0,0.92)";

    testElement.style.color =
        "white";

    testElement.style.fontSize =
        "14px";

    testElement.style.fontFamily =
        "monospace";

    testElement.style.borderRadius =
        "8px";

    testElement.style.boxShadow =
        "0 2px 10px rgba(0,0,0,0.5)";

    testElement.style.whiteSpace =
        "pre-wrap";

    testElement.style.wordBreak =
        "break-word";

    document.documentElement.appendChild(
        testElement
    );

} catch (e) {

    console.log(
        "[AiChat CONTENT TEST] DOM error:",
        e
    );
}

// ============================================================
// Helper
// ============================================================

function showResult(text) {

    try {

        if (testElement) {

            testElement.textContent =
                text;
        }

    } catch (e) {

        console.log(
            "[AiChat CONTENT TEST] UI error:",
            e
        );
    }
}

// ============================================================
// Check runtime
// ============================================================

if (
    typeof browser === "undefined" ||
    !browser.runtime
) {

    showResult(
        "❌ browser.runtime unavailable"
    );

    console.log(
        "[AiChat CONTENT TEST] browser.runtime unavailable"
    );

    return;
}

if (
    typeof browser.runtime.sendMessage !==
    "function"
) {

    showResult(
        "❌ runtime.sendMessage unavailable"
    );

    console.log(
        "[AiChat CONTENT TEST] " +
        "runtime.sendMessage unavailable"
    );

    return;
}

// ============================================================
// IMPORTANT
//
// Do NOT call sendNativeMessage here.
//
// Send the test to background.js instead.
// ============================================================

var message = {

    type:
        "AICHAT_NATIVE_TEST",

    source:
        "content.js",

    timestamp:
        timestamp,

    test:
        true
};

console.log(
    "[AiChat CONTENT TEST] Sending message to background:",
    message
);

showResult(
    "🟡 Content → Background\n\n" +
    "Sending AICHAT_NATIVE_TEST..."
);

// ============================================================
// Send to Background
// ============================================================

try {

    browser.runtime
        .sendMessage(message)

        .then(function (response) {

            console.log(
                "[AiChat CONTENT TEST] " +
                "Background response:",
                response
            );

            var responseText;

            try {

                responseText =
                    JSON.stringify(
                        response,
                        null,
                        2
                    );

            } catch (e) {

                responseText =
                    String(response);
            }

            if (
                response &&
                response.native === true
            ) {

                showResult(
                    "✅ Content → Background → Native\n\n" +
                    "Native channel responded.\n\n" +
                    "Response:\n" +
                    responseText
                );

            } else if (
                response &&
                response.native === false
            ) {

                showResult(
                    "❌ Background reached Native,\n" +
                    "but Native Messaging failed.\n\n" +
                    "Error:\n" +
                    (
                        response.error ||
                        "Unknown error"
                    )
                );

            } else {

                showResult(
                    "⚠️ Background response received\n\n" +
                    responseText
                );
            }

        })

        .catch(function (error) {

            console.log(
                "[AiChat CONTENT TEST] " +
                "Background communication FAILED:",
                error
            );

            showResult(
                "❌ Content → Background FAILED\n\n" +
                "error.name:\n" +
                (
                    error &&
                    error.name !== undefined
                        ? error.name
                        : "(undefined)"
                ) +
                "\n\n" +
                "error.message:\n" +
                (
                    error &&
                    error.message !== undefined
                        ? error.message
                        : "(undefined)"
                ) +
                "\n\n" +
                "error.toString():\n" +
                String(error)
            );
        });

} catch (e) {

    console.log(
        "[AiChat CONTENT TEST] " +
        "Synchronous exception:",
        e
    );

    showResult(
        "❌ Content → Background EXCEPTION\n\n" +
        "name:\n" +
        (
            e && e.name
                ? e.name
                : "(undefined)"
        ) +
        "\n\n" +
        "message:\n" +
        (
            e && e.message
                ? e.message
                : "(undefined)"
        ) +
        "\n\n" +
        "toString:\n" +
        String(e)
    );
}

})();
