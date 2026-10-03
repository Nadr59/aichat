(function () {

    "use strict";

    if (window !== window.top) {
        return;
    }

    var element =
        document.createElement("div");

    element.textContent =
        "⏳ في انتظار Kotlin → Background → Content...";

    element.style.position =
        "fixed";

    element.style.top =
        "10px";

    element.style.left =
        "10px";

    element.style.zIndex =
        "2147483647";

    element.style.padding =
        "12px 16px";

    element.style.background =
        "black";

    element.style.color =
        "white";

    element.style.fontSize =
        "16px";

    element.style.fontFamily =
        "sans-serif";

    element.style.borderRadius =
        "8px";

    document.documentElement.appendChild(
        element
    );


    /*
     * ========================================================
     * استقبال الرسالة من background.js
     * ========================================================
     */

    browser.runtime.onMessage.addListener(
        function (message) {

            console.log(
                "[AiChat TEST] Message from background:",
                message
            );

            if (
                message &&
                message.type ===
                    "REVERSE_TEST"
            ) {

                element.textContent =
                    "✅ Kotlin → Background → Content نجح";

                element.style.background =
                    "green";

                return Promise.resolve({
                    ok: true
                });
            }

            /*
             * رسالة الاختبار الأولية
             */
            if (
                message &&
                message.type ===
                    "REVERSE_TEST_READY"
            ) {

                element.textContent =
                    "🟡 Kotlin متصل بالـ Background";

                element.style.background =
                    "orange";

                return Promise.resolve({
                    ok: true
                });
            }

            return Promise.resolve({
                ok: false
            });
        }
    );

})();
