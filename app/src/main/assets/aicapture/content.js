(function () {
    "use strict";

    if (window !== window.top) return;

    var POLL_INTERVAL = 1000;
    var pollTimer = null;
    var lastInjectedId = 0;

    function log(message) {
        console.log("[AiChat INJECTION] " + message);

        try {
            browser.runtime.sendMessage({
                type: "DEBUG_INFO",
                info: message
            });
        } catch (e) {
        }
    }

    function getDomain() {
        return location.hostname || "";
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

        for (var i = 0; i < selectors.length; i++) {
            var elements;

            try {
                elements = document.querySelectorAll(selectors[i]);
            } catch (e) {
                continue;
            }

            for (var j = 0; j < elements.length; j++) {
                var element = elements[j];

                if (!element) continue;

                var rect = element.getBoundingClientRect();

                if (
                    rect.width > 0 &&
                    rect.height > 0 &&
                    !element.disabled &&
                    element.getAttribute("aria-hidden") !== "true"
                ) {
                    return element;
                }
            }
        }

        return null;
    }

    function setNativeValue(element, value) {
        var prototype;

        if (element.tagName === "TEXTAREA") {
            prototype = window.HTMLTextAreaElement.prototype;
        } else if (element.tagName === "INPUT") {
            prototype = window.HTMLInputElement.prototype;
        }

        if (prototype) {
            var descriptor = Object.getOwnPropertyDescriptor(
                prototype,
                "value"
            );

            if (descriptor && descriptor.set) {
                descriptor.set.call(element, value);
            } else {
                element.value = value;
            }
        } else {
            element.textContent = value;
        }
    }

    function injectText(element, text) {
        try {
            element.focus();

            if (element.tagName === "TEXTAREA" || element.tagName === "INPUT") {
                setNativeValue(element, text);

                element.dispatchEvent(
                    new Event("input", {
                        bubbles: true
                    })
                );

                element.dispatchEvent(
                    new Event("change", {
                        bubbles: true
                    })
                );

                return true;
            }

            element.focus();

            var selection = window.getSelection();
            var range = document.createRange();

            range.selectNodeContents(element);

            selection.removeAllRanges();
            selection.addRange(range);

            document.execCommand(
                "insertText",
                false,
                text
            );

            element.dispatchEvent(
                new InputEvent("input", {
                    bubbles: true,
                    inputType: "insertText",
                    data: text
                })
            );

            return true;

        } catch (e) {
            log("Injection error: " + e);
            return false;
        }
    }

    function sendEnter(element) {
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
            log("Enter error: " + e);
            return false;
        }
    }

    function reportResult(
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
                contextId: contextId || 0
            });
        } catch (e) {
            log("Could not report CONTEXT_WRITTEN: " + e);
        }
    }

    function requestContext() {
        var domain = getDomain();

        browser.runtime.sendMessage({
            type: "POLL",
            domain: domain,
            visible: !document.hidden
        }).then(function (response) {

            if (!response) {
                return;
            }

            if (!response.hasContext) {
                return;
            }

            var contextId = Number(response.id || 0);
            var text = response.context || "";

            if (!text) {
                return;
            }

            if (
                contextId !== 0 &&
                contextId === lastInjectedId
            ) {
                return;
            }

            log(
                "📥 Context received: " +
                text.length +
                " chars, id=" +
                contextId
            );

            var input = findChatGPTInput();

            if (!input) {
                log("❌ ChatGPT input not found");

                reportResult(
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
                (input.id ? "#" + input.id : "")
            );

            var injected = injectText(
                input,
                text
            );

            if (!injected) {
                reportResult(
                    false,
                    "injection_failed",
                    "فشل وضع النص داخل مربع الإدخال",
                    contextId
                );

                return;
            }

            lastInjectedId = contextId;

            log(
                "✏️ Text injected successfully"
            );

            reportResult(
                true,
                "text_injected",
                "تم وضع النص في مربع إدخال ChatGPT",
                contextId
            );

            setTimeout(function () {

                var currentInput =
                    findChatGPTInput();

                if (!currentInput) {
                    log(
                        "⚠️ Input disappeared before Enter"
                    );
                    return;
                }

                var sent = sendEnter(
                    currentInput
                );

                if (sent) {
                    log(
                        "📤 Enter sent to ChatGPT"
                    );

                    reportResult(
                        true,
                        "enter_sent",
                        "تم الضغط على Enter",
                        contextId
                    );

                    setTimeout(function () {

                        browser.runtime.sendMessage({
                            type: "CONTEXT_CONSUMED",
                            contextId: contextId
                        }).catch(function () {
                        });

                    }, 1500);

                } else {

                    reportResult(
                        false,
                        "enter_failed",
                        "فشل إرسال Enter",
                        contextId
                    );
                }

            }, 300);

        }).catch(function (error) {

            log(
                "❌ POLL failed: " +
                (error && error.message
                    ? error.message
                    : error)
            );

        });
    }

    browser.runtime.onMessage.addListener(
        function (message) {

            if (!message) {
                return Promise.resolve({
                    ok: false
                });
            }

            if (message.type === "REVERSE_TEST") {
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

    function startPolling() {
        if (pollTimer !== null) {
            clearInterval(pollTimer);
        }

        requestContext();

        pollTimer = setInterval(
            requestContext,
            POLL_INTERVAL
        );
    }

    log(
        "✅ Injection content.js ready on " +
        location.href
    );

    log(
        "🌐 Domain: " +
        getDomain()
    );

    startPolling();

})();
