(function () {
    "use strict";

    // ============================================================
    // AiChat — CONTENT.JS INJECTION TEST
    // ============================================================

    var timestamp = new Date().toLocaleTimeString();

    console.log(
        "[AiChat TEST] content.js EXECUTED at " + timestamp
    );

    // ------------------------------------------------------------
    // 1. تغيير عنوان الصفحة
    // ------------------------------------------------------------

    try {
        document.title =
            "[AiChat TEST OK] " + document.title;

        console.log(
            "[AiChat TEST] document.title changed"
        );
    } catch (e) {
        console.log(
            "[AiChat TEST] title error:",
            e
        );
    }

    // ------------------------------------------------------------
    // 2. إنشاء علامة مرئية داخل الصفحة
    // ------------------------------------------------------------

    try {
        var testElement = document.createElement("div");

        testElement.id = "aichat-content-test";

        testElement.textContent =
            "✅ AiChat content.js يعمل — " + timestamp;

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
            "[AiChat TEST] Visible test element created"
        );

    } catch (e) {

        console.log(
            "[AiChat TEST] DOM error:",
            e
        );
    }

    // ------------------------------------------------------------
    // 3. محاولة وضع علامة في body أيضًا
    // ------------------------------------------------------------

    try {

        if (document.body) {

            document.body.setAttribute(
                "data-aichat-content-test",
                "SUCCESS"
            );

        }

    } catch (e) {

        console.log(
            "[AiChat TEST] body attribute error:",
            e
        );
    }

})();
