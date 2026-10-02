(function () {
    "use strict";

    if (window !== window.top) return;

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

        })
        .catch(function (error) {

            console.error(
                "[AiChat TEST] sendMessage failed:",
                error
            );

        });

    }, 2000);

})();
