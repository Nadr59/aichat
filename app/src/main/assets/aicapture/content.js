(function () {
    "use strict";

    if (window !== window.top) {
        return;
    }

    // ============================================================
    // AiChat — ChatGPT Direct Injection
    // ============================================================

    function log(message) {

        console.log(
            "[AiChat INJECTION] " +
            message
        );

        try {

            browser.runtime.sendMessage({
                type: "DEBUG_INFO",
                info: message
            }).catch(function () {
            });

        } catch (e) {
        }
    }

    // ============================================================
    // Check whether an element is a usable visible input
    // ============================================================

    function isUsableInput(element) {

        if (!element) {
            return false;
        }

        try {

            var rect =
                element.getBoundingClientRect();

            if (
                rect.width <= 0 ||
                rect.height <= 0
            ) {
                return false;
            }

            if (
                element.disabled === true ||
                element.getAttribute("disabled") !== null
            ) {
                return false;
            }

            if (
                element.getAttribute("aria-hidden") === "true"
            ) {
                return false;
            }

            var style =
                window.getComputedStyle(element);

            if (
                style.display === "none" ||
                style.visibility === "hidden"
            ) {
                return false;
            }

            return true;

        } catch (e) {

            return false;
        }
    }

    // ============================================================
    // Find ChatGPT composer
    // ============================================================

    function findChatGPTInput() {

        var selectors = [

            // Current / common ChatGPT selectors
            "#prompt-textarea",

            "textarea[data-testid='textbox']",

            "textarea[data-testid*='textbox']",

            "textarea[placeholder*='Message']",

            "textarea[placeholder*='message']",

            "textarea[aria-label*='Message']",

            "textarea[aria-label*='message']",

            // Contenteditable editors
            "div[contenteditable='true'][role='textbox']",

            "[contenteditable='true'][role='textbox']",

            "div[contenteditable='true'][data-placeholder]",

            "[contenteditable='true'][data-placeholder]",

            // ProseMirror / Lexical style editors
            ".ProseMirror",

            "[data-lexical-editor='true']",

            // Generic textbox
            "[role='textbox']",

            // Last fallback
            "[contenteditable='true']",

            "textarea"
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

                if (
                    isUsableInput(element)
                ) {

                    return element;
                }
            }
        }

        return null;
    }

    // ============================================================
    // Set native value for textarea/input
    // ============================================================

    function setNativeValue(
        element,
        value
    ) {

        try {

            var prototype = null;

            if (
                element.tagName === "TEXTAREA"
            ) {

                prototype =
                    window.HTMLTextAreaElement
                        .prototype;

            } else if (
                element.tagName === "INPUT"
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

                element.value =
                    value;
            }

        } catch (e) {

            try {
                element.value = value;
            } catch (ignored) {
            }
        }
    }

    // ============================================================
    // Inject text
    // ============================================================

    function injectText(
        element,
        text
    ) {

        try {

            element.focus();

            // ----------------------------------------------------
            // textarea / input
            // ----------------------------------------------------

            if (
                element.tagName === "TEXTAREA" ||
                element.tagName === "INPUT"
            ) {

                setNativeValue(
                    element,
                    text
                );

                element.dispatchEvent(
                    new Event(
                        "input",
                        {
                            bubbles: true
                        }
                    )
                );

                element.dispatchEvent(
                    new Event(
                        "change",
                        {
                            bubbles: true
                        }
                    )
                );

                return true;
            }

            // ----------------------------------------------------
            // contenteditable / ProseMirror / Lexical
            // ----------------------------------------------------

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

            // First attempt:
            // browser editing command. This usually triggers
            // the browser's native editing path.
            try {

                inserted =
                    document.execCommand(
                        "insertText",
                        false,
                        text
                    );

            } catch (e) {

                inserted = false;
            }

            // Fallback
            if (!inserted) {

                element.textContent =
                    text;

                try {

                    element.dispatchEvent(
                        new InputEvent(
                            "input",
                            {
                                bubbles: true,
                                inputType: "insertText",
                                data: text
                            }
                        )
                    );

                } catch (e) {

                    element.dispatchEvent(
                        new Event(
                            "input",
                            {
                                bubbles: true
                            }
                        )
                    );
                }
            }

            return true;

        } catch (e) {

            log(
                "❌ Injection error: " +
                (
                    e && e.message
                        ? e.message
                        : String(e)
                )
            );

            return false;
        }
    }

    // ============================================================
    // Send Enter
    // ============================================================

    function sendEnter(
        element
    ) {

        try {

            element.focus();

            var keyboardEventInit = {

                key: "Enter",

                code: "Enter",

                keyCode: 13,

                which: 13,

                bubbles: true,

                cancelable: true
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
                    e && e.message
                        ? e.message
                        : String(e)
                )
            );

            return false;
        }
    }

    // ============================================================
    // Report CONTEXT_WRITTEN
    // ============================================================

    function reportWritten(
        success,
        stage,
        detail,
        contextId
    ) {

        try {

            browser.runtime.sendMessage({

                type: "CONTEXT_WRITTEN",

                success: success,

                stage: stage,

                detail: detail,

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

    // ============================================================
    // Report CONTEXT_CONSUMED
    // ============================================================

    function reportConsumed(
        contextId
    ) {

        try {

            browser.runtime.sendMessage({

                type: "CONTEXT_CONSUMED",

                contextId:
                    contextId || 0

            }).catch(function () {
            });

        } catch (e) {
        }
    }

    // ============================================================
    // Wait for ChatGPT composer
    // ============================================================

    function findInputWithRetry(
        attempts,
        delay,
        callback
    ) {

        var input =
            findChatGPTInput();

        if (input) {

            callback(input);
            return;
        }

        if (attempts <= 0) {

            callback(null);
            return;
        }

        setTimeout(
            function () {

                findInputWithRetry(
                    attempts - 1,
                    delay,
                    callback
                );

            },
            delay
        );
    }

    // ============================================================
    // WebExtension message receiver
    // ============================================================

    browser.runtime.onMessage.addListener(
        function (message) {

            if (!message) {

                return Promise.resolve({
                    ok: false
                });
            }

            // ====================================================
            // MAIN PATH
            //
            // Kotlin
            //   ↓
            // background.js
            //   ↓
            // content.js
            //   ↓
            // ChatGPT
            // ====================================================

            if (
                message.type ===
                "CONTEXT_TO_PAGE"
            ) {

                var text =
                    message.text || "";

                var contextId =
                    Number(
                        message.contextId || 0
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
                        ok: false,
                        reason: "empty_text"
                    });
                }

                // ------------------------------------------------
                // ChatGPT may still be constructing its composer.
                // Try several times before declaring failure.
                // ------------------------------------------------

                findInputWithRetry(
                    10,
                    500,
                    function (input) {

                        if (!input) {

                            log(
                                "❌ ChatGPT input not found after retry"
                            );

                            reportWritten(
                                false,
                                "input_not_found",
                                "لم يتم العثور على مربع إدخال ChatGPT",
                                contextId
                            );

                            return;
                        }

                        log(
                            "✅ ChatGPT input found: " +
                            input.tagName +
                            (
                                input.id
                                    ? "#" + input.id
                                    : ""
                            ) +
                            (
                                input.getAttribute(
                                    "contenteditable"
                                ) === "true"
                                    ? " [contenteditable]"
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

                            return;
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

                        // ------------------------------------------------
                        // Give ChatGPT/React time to process the input.
                        // ------------------------------------------------

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
                            500
                        );
                    }
                );

                return Promise.resolve({

                    ok: true,

                    received: true,

                    contextId:
                        contextId
                });
            }

            // ====================================================
            // Old compatibility test
            // ====================================================

            if (
                message.type ===
                "REVERSE_TEST"
            ) {

                log(
                    "ℹ️ Old REVERSE_TEST received"
                );

                return Promise.resolve({

                    ok: true,

                    received: true
                });
            }

            return Promise.resolve({
                ok: false
            });
        }
    );

    // ============================================================
    // Ready
    // ============================================================

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
