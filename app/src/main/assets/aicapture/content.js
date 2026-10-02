(function () {
    "use strict";

    if (window !== window.top) return;

    var element = document.createElement("div");

    element.textContent =
        "🧪 AiChat: اختبار Content → Background";

    element.style.position = "fixed";
    element.style.top = "10px";
    element.style.left = "10px";
    element.style.zIndex = "2147483647";
    element.style.padding = "12px 16px";
    element.style.background = "black";
    element.style.color = "white";
    element.style.fontSize = "16px";
    element.style.fontFamily = "sans-serif";
    element.style.borderRadius = "8px";

    document.documentElement.appendChild(element);

    console.log("[AiChat TEST] content.js loaded");

    setTimeout(function () {

        console.log(
            "[AiChat TEST] Sending DIRECT_TEST_2 to background..."
        );

        browser.runtime.sendMessage({
            type: "DIRECT_TEST_2",
            source: "content.js",
            text: "HELLO_FROM_CONTENT_JS_2",
            timestamp: new Date().toISOString()
        })
        .then(function (response) {

            console.log(
                "[AiChat TEST] Background response:",
                response
            );

            element.textContent =
                "✅ Content → Background نجح";

        })
        .catch(function (error) {

            console.error(
                "[AiChat TEST] sendMessage failed:",
                error
            );

            element.textContent =
                "❌ Content → Background فشل";

        });

    }, 2000);

})();
