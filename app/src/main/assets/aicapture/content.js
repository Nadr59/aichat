(function () {
    "use strict";

    /*
     * ================================================================
     * AiChat GeckoView DIRECT NATIVE MESSAGE TEST
     * ================================================================
     *
     * هذا اختبار تشخيصي فقط.
     *
     * لا نستخدم:
     *   browser.runtime.sendMessage()
     *   background.js
     *   POLL
     *   DOM
     *   MutationObserver
     *   الكتابة داخل ChatGPT
     *
     * الهدف:
     *
     * content.js
     *      ↓
     * browser.runtime.sendNativeMessage()
     *      ↓
     * GeckoView
     *      ↓
     * AichatApp.MessageDelegate
     *
     * إذا ظهرت DIRECT_TEST RECEIVED في Kotlin،
     * فهذا يعني أن content.js قادر على الوصول مباشرة
     * إلى native messaging.
     * ================================================================
     */

    var host = "";

    try {
        host = location.hostname || "";
    } catch (e) {
        host = "unknown";
    }

    console.log(
        "[AiChat DIRECT TEST] SCRIPT START - host=" + host
    );

    try {

        browser.runtime.sendNativeMessage(
            "browser",
            {
                type: "DIRECT_TEST",
                message: "DIRECT_NATIVE_MESSAGE_OK",
                domain: host,
                timestamp: Date.now()
            }
        )
        .then(function (response) {

            console.log(
                "[AiChat DIRECT TEST] Native response:",
                response
            );

        })
        .catch(function (error) {

            console.log(
                "[AiChat DIRECT TEST] Native message FAILED:",
                error
            );

        });

    } catch (error) {

        console.log(
            "[AiChat DIRECT TEST] sendNativeMessage exception:",
            error
        );
    }

})();
