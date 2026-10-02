(function () {
"use strict";

// ============================================================
// AiChat — SEND NATIVE MESSAGE DIAGNOSTIC TEST
// ============================================================

var timestamp = new Date().toLocaleTimeString();

console.log(
    "[AiChat NATIVE TEST] content.js EXECUTED at " + timestamp
);

var testElement = null;

// ------------------------------------------------------------
// 1. إنشاء العلامة المرئية
// ------------------------------------------------------------

try {
    testElement = document.createElement("div");

    testElement.id = "aichat-native-test";

    testElement.textContent =
        "🧪 sendNativeMessage TEST — " + timestamp;

    testElement.style.position = "fixed";
    testElement.style.top = "10px";
    testElement.style.left = "10px";
    testElement.style.right = "10px";
    testElement.style.zIndex = "2147483647";

    testElement.style.padding = "12px 16px";

    testElement.style.background =
        "rgba(0, 0, 0, 0.92)";

    testElement.style.color = "white";

    testElement.style.fontSize = "14px";
    testElement.style.fontFamily = "monospace";

    testElement.style.borderRadius = "8px";

    testElement.style.boxShadow =
        "0 2px 10px rgba(0,0,0,0.5)";

    testElement.style.whiteSpace = "pre-wrap";
    testElement.style.wordBreak = "break-word";

    document.documentElement.appendChild(testElement);

    console.log(
        "[AiChat NATIVE TEST] Visible test element created"
    );

} catch (e) {

    console.log(
        "[AiChat NATIVE TEST] DOM error:",
        e
    );
}

// ------------------------------------------------------------
// 2. دالة لعرض النص داخل العلامة
// ------------------------------------------------------------

function showResult(text) {

    try {

        if (testElement) {
            testElement.textContent = text;
        }

    } catch (e) {

        console.log(
            "[AiChat NATIVE TEST] UI update error:",
            e
        );
    }
}

// ------------------------------------------------------------
// 3. التحقق من browser.runtime
// ------------------------------------------------------------

try {

    if (
        typeof browser === "undefined" ||
        !browser.runtime
    ) {

        var runtimeError =
            "❌ browser.runtime unavailable";

        console.log(
            "[AiChat NATIVE TEST] " +
            runtimeError
        );

        showResult(runtimeError);

        return;
    }

    console.log(
        "[AiChat NATIVE TEST] browser.runtime AVAILABLE"
    );

} catch (e) {

    console.log(
        "[AiChat NATIVE TEST] runtime check exception:",
        e
    );

    showResult(
        "❌ runtime check exception\n\n" +
        "name: " + (e && e.name) + "\n" +
        "message: " + (e && e.message) + "\n" +
        "toString: " + String(e)
    );

    return;
}

// ------------------------------------------------------------
// 4. التحقق من sendNativeMessage
// ------------------------------------------------------------

try {

    if (
        typeof browser.runtime.sendNativeMessage !==
        "function"
    ) {

        var apiError =
            "❌ sendNativeMessage unavailable";

        console.log(
            "[AiChat NATIVE TEST] " +
            apiError
        );

        showResult(apiError);

        return;
    }

    console.log(
        "[AiChat NATIVE TEST] " +
        "sendNativeMessage AVAILABLE"
    );

} catch (e) {

    console.log(
        "[AiChat NATIVE TEST] API check exception:",
        e
    );

    showResult(
        "❌ API CHECK EXCEPTION\n\n" +
        "name: " + (e && e.name) + "\n" +
        "message: " + (e && e.message) + "\n" +
        "toString: " + String(e)
    );

    return;
}

// ------------------------------------------------------------
// 5. الرسالة التي سيتم إرسالها
// ------------------------------------------------------------

var message = {
    type: "AICHAT_NATIVE_TEST",
    source: "content.js",
    timestamp: timestamp,
    test: true
};

console.log(
    "[AiChat NATIVE TEST] Sending native message:",
    message
);

showResult(
    "🟡 sendNativeMessage STARTED\n\n" +
    "host: aichat\n" +
    "type: AICHAT_NATIVE_TEST\n" +
    "time: " + timestamp
);

// ------------------------------------------------------------
// 6. استدعاء Native Host
// ------------------------------------------------------------

try {

    var nativePromise =
        browser.runtime.sendNativeMessage(
            "aichat",
            message
        );

    console.log(
        "[AiChat NATIVE TEST] " +
        "sendNativeMessage returned:",
        nativePromise
    );

    nativePromise
        .then(function (response) {

            console.log(
                "[AiChat NATIVE TEST] SUCCESS",
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
                responseText = String(response);
            }

            showResult(
                "✅ sendNativeMessage SUCCESS\n\n" +
                "time: " + timestamp +
                "\n\n" +
                "response:\n" +
                responseText
            );

        })
        .catch(function (error) {

            console.log(
                "[AiChat NATIVE TEST] FAILED — raw error:",
                error
            );

            console.log(
                "[AiChat NATIVE TEST] error.name:",
                error && error.name
            );

            console.log(
                "[AiChat NATIVE TEST] error.message:",
                error && error.message
            );

            console.log(
                "[AiChat NATIVE TEST] error.toString:",
                String(error)
            );

            var errorName =
                error && error.name !== undefined
                    ? error.name
                    : "(undefined)";

            var errorMessage =
                error && error.message !== undefined
                    ? error.message
                    : "(undefined)";

            var errorString =
                String(error);

            showResult(
                "❌ sendNativeMessage FAILED\n\n" +
                "time: " + timestamp +
                "\n\n" +
                "error.name:\n" +
                errorName +
                "\n\n" +
                "error.message:\n" +
                errorMessage +
                "\n\n" +
                "error.toString():\n" +
                errorString
            );

        });

} catch (e) {

    console.log(
        "[AiChat NATIVE TEST] " +
        "SYNCHRONOUS EXCEPTION:",
        e
    );

    console.log(
        "[AiChat NATIVE TEST] exception.name:",
        e && e.name
    );

    console.log(
        "[AiChat NATIVE TEST] exception.message:",
        e && e.message
    );

    console.log(
        "[AiChat NATIVE TEST] exception.toString:",
        String(e)
    );

    showResult(
        "❌ sendNativeMessage EXCEPTION\n\n" +
        "time: " + timestamp +
        "\n\n" +
        "error.name:\n" +
        (e && e.name !== undefined
            ? e.name
            : "(undefined)") +
        "\n\n" +
        "error.message:\n" +
        (e && e.message !== undefined
            ? e.message
            : "(undefined)") +
        "\n\n" +
        "error.toString():\n" +
        String(e)
    );
}

})();
