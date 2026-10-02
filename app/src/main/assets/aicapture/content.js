(function () {
    "use strict";

    // ============================================================
    // AiChat — ChatGPT INPUT WRITE TEST
    // الإصدار: 1.0.20 TEST
    //
    // الهدف:
    //   كتابة نص ثابت في خانة ChatGPT فقط.
    //
    // لا يوجد:
    //   - nativeMessaging
    //   - background.js
    //   - Kotlin
    //   - Memory
    //   - GET_CONTEXT
    //   - CHECK_CAPTURE
    //   - ضغط زر الإرسال
    // ============================================================

    if (window !== window.top) return;

    console.log("[AiChat TEST] content.js loaded");

    var TEST_TEXT =
        "اختبار AiChat: تمت الكتابة في خانة ChatGPT بنجاح.";

    function log(message) {
        console.log("[AiChat TEST] " + message);
    }

    function findChatGPTInput() {
        var selectors = [
            "#prompt-textarea",
            "textarea[placeholder*='Message']",
            "textarea[data-id='root']",
            "div[contenteditable='true']",
            "textarea"
        ];

        for (var i = 0; i < selectors.length; i++) {
            var el = document.querySelector(selectors[i]);

            if (!el) continue;

            var rect = el.getBoundingClientRect();

            if (rect.width === 0 && rect.height === 0) {
                continue;
            }

            return el;
        }

        return null;
    }

    function writeToInput(input, text) {

        log(
            "Input found: tag=" +
            input.tagName +
            ", contenteditable=" +
            input.getAttribute("contenteditable")
        );

        input.focus();

        // --------------------------------------------------------
        // TEXTAREA / INPUT
        // --------------------------------------------------------
        if (
            input.tagName === "TEXTAREA" ||
            input.tagName === "INPUT"
        ) {
            try {
                var prototype =
                    input.tagName === "TEXTAREA"
                        ? window.HTMLTextAreaElement.prototype
                        : window.HTMLInputElement.prototype;

                var descriptor =
                    Object.getOwnPropertyDescriptor(
                        prototype,
                        "value"
                    );

                if (!descriptor || !descriptor.set) {
                    log("ERROR: value setter not found");
                    return false;
                }

                descriptor.set.call(input, text);

                input.dispatchEvent(
                    new Event("input", {
                        bubbles: true,
                        composed: true
                    })
                );

                input.dispatchEvent(
                    new Event("change", {
                        bubbles: true,
                        composed: true
                    })
                );

                log("SUCCESS: text written to textarea/input");

                return true;

            } catch (e) {
                log(
                    "ERROR writing textarea/input: " +
                    e.message
                );

                return false;
            }
        }

        // --------------------------------------------------------
        // CONTENTEDITABLE
        // --------------------------------------------------------
        if (input.isContentEditable) {

            try {
                var selection = window.getSelection();

                selection.removeAllRanges();

                var range = document.createRange();

                range.selectNodeContents(input);
                range.collapse(false);

                selection.addRange(range);

                var inserted =
                    document.execCommand(
                        "insertText",
                        false,
                        text
                    );

                if (!inserted) {
                    log("ERROR: execCommand returned false");
                    return false;
                }

                log("SUCCESS: text inserted into contenteditable");

                return true;

            } catch (e) {
                log(
                    "ERROR writing contenteditable: " +
                    e.message
                );

                return false;
            }
        }

        log("ERROR: unsupported input element");

        return false;
    }

    function runTest() {

        log("Starting ChatGPT input test...");

        var host = location.hostname;

        if (
            host !== "chatgpt.com" &&
            host !== "www.chatgpt.com"
        ) {
            log(
                "Not ChatGPT. Current host: " +
                host
            );
            return;
        }

        var input = findChatGPTInput();

        if (!input) {
            log("Input not found. Retrying in 1 second...");

            setTimeout(runTest, 1000);

            return;
        }

        log("ChatGPT input found.");

        var success =
            writeToInput(input, TEST_TEXT);

        if (success) {
            log(
                "TEST PASSED: text should now be visible."
            );
            log(
                "IMPORTANT: no send button was clicked."
            );
        } else {
            log("TEST FAILED: could not write text.");
        }
    }

    // انتظر حتى تستقر واجهة ChatGPT
    setTimeout(runTest, 1500);

})();
