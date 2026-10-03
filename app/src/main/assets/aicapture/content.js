(function () {
    "use strict";

    if (window !== window.top) {
        return;
    }

    function log(message) {

        console.log(
            "[AiChat INJECTION] " +
            message
        );

        try {

            browser.runtime.sendMessage({
                type:
                    "DEBUG_INFO",

                info:
                    message

            }).catch(function () {
            });

        } catch (e) {
        }
    }

    function findChatGPTInput() {

        var selectors = [

            "#prompt-textarea",

            "textarea[data-testid='textbox']",

            "textarea[placeholder*='Message']",

            "textarea[placeholder*='message']",

            "div[contenteditable='true'][role='textbox']",

            "div[contenteditable='true']"
        ];

        for (
            var i = 0;
            i < selectors.length;
            i++
        ) {

            var elements;

            try {

                elements =
                    document.querySelectorAll(
                        selectors[i]
                    );

            } catch (e) {

                continue;
            }

            for (
                var j = 0;
                j < elements.length;
                j++
            ) {

                var element =
                    elements[j];

                if (!element) {
                    continue;
                }

                var rect =
                    element.getBoundingClientRect();

                if (
                    rect.width > 0 &&
                    rect.height > 0 &&
                    !element.disabled &&
                    element.getAttribute(
                        "aria-hidden"
                    ) !== "true"
                ) {

                    return element;
                }
            }
        }

        return null;
    }

    function setNativeValue(
        element,
        value
    ) {

        var prototype;

        if (
            element.tagName ===
            "TEXTAREA"
        ) {

            prototype =
                window.HTMLTextAreaElement
                    .prototype;

        } else if (
            element.tagName ===
            "INPUT"
        ) {

            prototype =
                window.HTMLInputElement
                    .prototype;
        }

        if (prototype) {

            var descriptor =
                Object.getOwnPropertyDescriptor(
                    prototype,
                    "value"
                );

            if (
                descriptor &&
                descriptor.set
            ) {

                descriptor.set.call(
                    element,
                    value
                );

            } else {

                element.value =
                    value;
            }

        } else {

            element.textContent =
                value;
        }
    }

    function injectText(
        element,
        text
    ) {

        try {

            element.focus();

            /*
             * textarea / input
             */
            if (
                element.tagName ===
                    "TEXTAREA" ||
                element.tagName ===
                    "INPUT"
            ) {

                setNativeValue(
                    element,
                    text
                );

                element.dispatchEvent(
                    new Event(
                        "input",
                        {
                            bubbles:
                                true
                        }
                    )
                );

                element.dispatchEvent(
                    new Event(
                        "change",
                        {
                            bubbles:
                                true
                        }
                    )
                );

                return true;
            }

            /*
             * contenteditable
             */
            element.focus();

            var selection =
                window.getSelection();

            var range =
                document.createRange();

            range.selectNodeContents(
                element
            );

            selection.removeAllRanges();

            selection.addRange(
                range
            );

            var inserted =
                false;

            try {

                inserted =
                    document.execCommand(
                        "insertText",
                        false,
                        text
                    );

            } catch (e) {

                inserted =
                    false;
            }

            if (!inserted) {

                element.textContent =
                    text;
            }

            element.dispatchEvent(
                new InputEvent(
                    "input",
                    {
                        bubbles:
                            true,

                        inputType:
                            "insertText",

                        data:
                            text
                    }
                )
            );

            return true;

        } catch (e) {

            log(
                "❌ Injection error: " +
                (
                    e &&
                    e.message
                        ? e.message
                        : String(e)
                )
            );

            return false;
        }
    }

    function sendEnter(
        element
    ) {

        try {

            element.focus();

            var keyboardEventInit = {

                key:
                    "Enter",

                code:
                    "Enter",

                keyCode:
                    13,

                which:
                    13,

                bubbles:
                    true,

                cancelable:
                    true
            };

            element.dispatchEvent(
                new KeyboardEvent(
                    "keydown",
                    keyboardEventInit
                )
            );

            element.dispatchEvent(
                new KeyboardEvent(
                    "keypress",
                    keyboardEventInit
                )
            );

            element.dispatchEvent(
                new KeyboardEvent(
                    "keyup",
                    keyboardEventInit
                )
            );

            return true;

        } catch (e) {

            log(
                "❌ Enter error: " +
                (
                    e &&
                    e.message
                        ? e.message
                        : String(e)
                )
            );

            return false;
        }
    }

    function reportWritten(
        success,
        stage,
        detail,
        contextId
    ) {

        try {

            browser.runtime.sendMessage({

                type:
                    "CONTEXT_WRITTEN",

                success:
                    success,

                stage:
                    stage,

                detail:
                    detail,

                contextId:
                    contextId || 0

            }).catch(function () {
            });

        } catch (e) {

            log(
                "❌ Could not report CONTEXT_WRITTEN"
            );
        }
    }

    function reportConsumed(
        contextId
    ) {

        try {

            browser.runtime.sendMessage({

                type:
                    "CONTEXT_CONSUMED",

                contextId:
                    contextId || 0

            }).catch(function () {
            });

        } catch (e) {
        }
    }

    browser.runtime.onMessage.addListener(
        function (message) {

            if (!message) {

                return Promise.resolve({
                    ok:
                        false
                });
            }

            /*
             * المسار الرئيسي:
             *
             * Kotlin
             *   ↓
             * background.js
             *   ↓
             * content.js
             *   ↓
             * ChatGPT input
             */
            if (
                message.type ===
                "CONTEXT_TO_PAGE"
            ) {

                var text =
                    message.text || "";

                var contextId =
                    Number(
                        message.contextId ||
                        0
                    );

                log(
                    "📥 CONTEXT_TO_PAGE received: " +
                    text.length +
                    " chars, id=" +
                    contextId
                );

                if (!text) {

                    reportWritten(
                        false,
                        "empty_text",
                        "النص المرسل فارغ",
                        contextId
                    );

                    return Promise.resolve({
                        ok:
                            false,

                        reason:
                            "empty_text"
                    });
                }

                var input =
                    findChatGPTInput();

                if (!input) {

                    log(
                        "❌ ChatGPT input not found"
                    );

                    reportWritten(
                        false,
                        "input_not_found",
                        "لم يتم العثور على مربع إدخال ChatGPT",
                        contextId
                    );

                    return Promise.resolve({
                        ok:
                            false,

                        reason:
                            "input_not_found"
                    });
                }

                log(
                    "✅ ChatGPT input found: " +
                    input.tagName +
                    (
                        input.id
                            ? "#" + input.id
                            : ""
                    )
                );

                var injected =
                    injectText(
                        input,
                        text
                    );

                if (!injected) {

                    reportWritten(
                        false,
                        "injection_failed",
                        "فشل وضع النص داخل مربع الإدخال",
                        contextId
                    );

                    return Promise.resolve({
                        ok:
                            false,

                        reason:
                            "injection_failed"
                    });
                }

                log(
                    "✏️ Text injected successfully"
                );

                reportWritten(
                    true,
                    "text_injected",
                    "تم وضع النص في مربع إدخال ChatGPT",
                    contextId
                );

                /*
                 * ننتظر قليلًا حتى يستوعب React
                 * التغيير في مربع الإدخال.
                 */
                setTimeout(
                    function () {

                        var currentInput =
                            findChatGPTInput();

                        if (!currentInput) {

                            log(
                                "⚠️ Input disappeared before Enter"
                            );

                            reportWritten(
                                false,
                                "input_disappeared",
                                "اختفى مربع الإدخال قبل إرسال Enter",
                                contextId
                            );

                            return;
                        }

                        var sent =
                            sendEnter(
                                currentInput
                            );

                        if (sent) {

                            log(
                                "📤 Enter sent to ChatGPT"
                            );

                            reportWritten(
                                true,
                                "enter_sent",
                                "تم الضغط على Enter",
                                contextId
                            );

                            setTimeout(
                                function () {

                                    reportConsumed(
                                        contextId
                                    );

                                },
                                1500
                            );

                        } else {

                            reportWritten(
                                false,
                                "enter_failed",
                                "فشل إرسال Enter",
                                contextId
                            );
                        }

                    },
                    300
                );

                return Promise.resolve({

                    ok:
                        true,

                    received:
                        true,

                    contextId:
                        contextId
                });
            }

            /*
             * اختبار الاتجاه القديم.
             * لا يستخدم في التدفق الجديد.
             */
            if (
                message.type ===
                "REVERSE_TEST"
            ) {

                log(
                    "ℹ️ Old REVERSE_TEST received"
                );

                return Promise.resolve({

                    ok:
                        true,

                    received:
                        true
                });
            }

            return Promise.resolve({
                ok:
                    false
            });
        }
    );

    log(
        "✅ Direct injection content.js ready on " +
        location.href
    );

    log(
        "🌐 Domain: " +
        (
            location.hostname ||
            ""
        )
    );

})();
