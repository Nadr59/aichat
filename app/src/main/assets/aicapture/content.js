(function () {
    "use strict";

    // ============================================================
    // AiChat — SEND NATIVE MESSAGE TEST
    // ============================================================

    var timestamp = new Date().toLocaleTimeString();

    console.log(
        "[AiChat NATIVE TEST] content.js EXECUTED at " + timestamp
    );

    // ------------------------------------------------------------
    // 1. علامة مرئية تؤكد أن الاختبار بدأ
    // ------------------------------------------------------------

    try {
        var testElement = document.createElement("div");

        testElement.id = "aichat-native-test";

        testElement.textContent =
            "🧪 sendNativeMessage TEST — " + timestamp;

        testElement.style.position = "fixed";
        testElement.style.top = "10px";
        testElement.style.left = "10px";
        testElement.style.zIndex = "2147483647";

        testElement.style.padding = "12px 16px";

        testElement.style.background =
            "rgba(0, 0, 0, 0.90)";

        testElement.style.color = "white";

        testElement.style.fontSize = "16px";
        testElement.style.fontFamily = "sans-serif";

        testElement.style.borderRadius = "8px";

        testElement.style.boxShadow =
            "0 2px 10px rgba(0,0,0,0.5)";

        document.documentElement.appendChild(
            testElement
        );

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
    // 2. التأكد من وجود sendNativeMessage
    // ------------------------------------------------------------

    try {

        if (
            typeof browser === "undefined" ||
            !browser.runtime
        ) {

            console.log(
                "[AiChat NATIVE TEST] ERROR: browser.runtime unavailable"
            );

            return;
        }

        if (
            typeof browser.runtime.sendNativeMessage !== "function"
        ) {

            console.log(
                "[AiChat NATIVE TEST] ERROR: sendNativeMessage unavailable"
            );

            return;
        }

        console.log(
            "[AiChat NATIVE TEST] sendNativeMessage AVAILABLE"
        );

    } catch (e) {

        console.log(
            "[AiChat NATIVE TEST] API check error:",
            e
        );

        return;
    }

    // ------------------------------------------------------------
    // 3. إرسال رسالة Native واحدة فقط
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

    try {

        browser.runtime.sendNativeMessage(
            "aichat",
            message
        )
        .then(function (response) {

            console.log(
                "[AiChat NATIVE TEST] SUCCESS — native response:",
                response
            );

            try {

                testElement.textContent =
                    "✅ sendNativeMessage SUCCESS — " +
                    timestamp;

            } catch (e) {
                // ignore UI update error
            }

        })
        .catch(function (error) {

            console.log(
                "[AiChat NATIVE TEST] FAILED:",
                error
            );

            try {

                testElement.textContent =
                    "❌ sendNativeMessage FAILED — " +
                    timestamp;

            } catch (e) {
                // ignore UI update error
            }

        });

    } catch (e) {

        console.log(
            "[AiChat NATIVE TEST] EXCEPTION:",
            e
        );

        try {

            testElement.textContent =
                "❌ sendNativeMessage EXCEPTION — " +
                timestamp;

        } catch (ignore) {
            // ignore
        }
    }

})();
