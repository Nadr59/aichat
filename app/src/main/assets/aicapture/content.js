(function () {
    "use strict";

    // ============================================================
    // AiChat — REAL ASSISTANT RESPONSE + CLEANING
    // Version 1.0.31
    // ============================================================

    var VERSION = "1.0.31";

    function log(message) {
        try {
            browser.runtime.sendMessage({
                type: "DEBUG_LOG",
                text: message
            });
        } catch (e) {
            console.log("[AiChat] " + message);
        }
    }

    log("🧪 content.js loaded v=" + VERSION);

    // ============================================================
    // INPUT
    // ============================================================

    var INPUT_SELECTORS = [
        "#prompt-textarea",
        "textarea[data-testid='textbox']",
        "textarea[data-testid*='textbox']",
        "textarea[placeholder*='Message']",
        "textarea[placeholder*='message']",
        "textarea[aria-label*='Message']",
        "textarea[aria-label*='message']",
        "textarea[aria-label*='الدردشة']",
        "div[contenteditable='true'][role='textbox']",
        "[contenteditable='true'][role='textbox']",
        "div[contenteditable='true'][data-placeholder]",
        "[contenteditable='true'][data-placeholder]",
        ".ProseMirror",
        "[data-lexical-editor='true']",
        "[role='textbox']",
        "[contenteditable='true']",
        "textarea"
    ];

    function findInput() {
        for (var i = 0; i < INPUT_SELECTORS.length; i++) {
            try {
                var element = document.querySelector(
                    INPUT_SELECTORS[i]
                );

                if (element) {
                    return element;
                }
            } catch (e) {}
        }

        return null;
    }

    function findInputWithRetry(attempts, delay, callback) {
        var count = 0;

        function attempt() {
            var input = findInput();

            if (input) {
                callback(input);
                return;
            }

            count++;

            if (count >= attempts) {
                log("❌ ChatGPT input not found");
                return;
            }

            setTimeout(attempt, delay);
        }

        attempt();
    }

    // ============================================================
    // INJECTION
    // ============================================================

    function injectText(input, text) {
        try {
            if (
                input.tagName === "TEXTAREA" ||
                input.tagName === "INPUT"
            ) {
                var prototype =
                    Object.getPrototypeOf(input);

                var descriptor =
                    Object.getOwnPropertyDescriptor(
                        prototype,
                        "value"
                    );

                if (descriptor && descriptor.set) {
                    descriptor.set.call(input, text);
                } else {
                    input.value = text;
                }

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
            } else {
                input.focus();

                try {
                    document.execCommand(
                        "selectAll",
                        false,
                        null
                    );
                } catch (e) {}

                try {
                    document.execCommand(
                        "insertText",
                        false,
                        text
                    );
                } catch (e) {
                    input.textContent = text;

                    input.dispatchEvent(
                        new InputEvent("input", {
                            bubbles: true,
                            composed: true,
                            inputType: "insertText",
                            data: text
                        })
                    );
                }
            }

            log("✅ Text injected successfully");
            return true;

        } catch (e) {
            log("❌ Text injection failed: " + e);
            return false;
        }
    }

    function sendEnter(input) {
        try {
            input.focus();

            var options = {
                key: "Enter",
                code: "Enter",
                keyCode: 13,
                which: 13,
                bubbles: true,
                cancelable: true,
                composed: true
            };

            input.dispatchEvent(
                new KeyboardEvent(
                    "keydown",
                    options
                )
            );

            input.dispatchEvent(
                new KeyboardEvent(
                    "keypress",
                    options
                )
            );

            input.dispatchEvent(
                new KeyboardEvent(
                    "keyup",
                    options
                )
            );

            log("✅ Enter sent to ChatGPT");
            return true;

        } catch (e) {
            log("❌ Enter failed: " + e);
            return false;
        }
    }

    // ============================================================
    // CONVERSATION CONTAINER
    // ============================================================

    function getConversationContainers() {
        var result = [];

        try {
            var sections = document.querySelectorAll(
                'section[aria-label^="محادثة"]'
            );

            for (var i = 0; i < sections.length; i++) {
                result.push(sections[i]);
            }
        } catch (e) {}

        if (result.length === 0) {
            try {
                var regions = document.querySelectorAll(
                    '[role="region"][aria-label="محادثة"]'
                );

                for (var j = 0; j < regions.length; j++) {
                    result.push(regions[j]);
                }
            } catch (e) {}
        }

        return result;
    }

    function getConversationText(element) {
        try {
            return (element.innerText ||
                element.textContent ||
                "")
                .replace(/\u00a0/g, " ")
                .replace(/\r/g, "")
                .trim();
        } catch (e) {
            return "";
        }
    }

    // ============================================================
    // CLEAN RESPONSE
    // ============================================================

    function cleanAssistantResponse(text) {
        if (!text) {
            return "";
        }

        var cleaned = text;

        // Normalize line endings.
        cleaned = cleaned
            .replace(/\r\n/g, "\n")
            .replace(/\r/g, "\n");

        // Remove known ChatGPT footer.
        var footer =
            "ChatGPT هو نظام ذكاء اصطناعي وقد يخطئ.";

        var footerIndex =
            cleaned.indexOf(footer);

        if (footerIndex !== -1) {
            cleaned =
                cleaned.substring(0, footerIndex);
        }

        // Remove the sources section.
        var sourceMarkers = [
            "\nF المصادر",
            "\nالمصادر",
            "F المصادر"
        ];

        var sourceIndex = -1;

        for (var i = 0; i < sourceMarkers.length; i++) {
            var index =
                cleaned.indexOf(sourceMarkers[i]);

            if (index !== -1) {
                if (
                    sourceIndex === -1 ||
                    index < sourceIndex
                ) {
                    sourceIndex = index;
                }
            }
        }

        if (sourceIndex !== -1) {
            cleaned =
                cleaned.substring(0, sourceIndex);
        }

        // Remove empty lines at beginning/end.
        cleaned = cleaned
            .replace(/^\s+/, "")
            .replace(/\s+$/, "");

        // Collapse excessive blank lines.
        cleaned = cleaned.replace(
            /\n[ \t]*\n[ \t]*\n+/g,
            "\n\n"
        );

        // Remove accidental spaces before newlines.
        cleaned = cleaned.replace(
            /[ \t]+\n/g,
            "\n"
        );

        return cleaned.trim();
    }

    // ============================================================
    // EXTRACT ASSISTANT
    // ============================================================

    function extractAssistantText(conversationText) {
        if (!conversationText) {
            return "";
        }

        var markers = [
            "قال ChatGPT:",
            "قال ChatGPT :",
            "ChatGPT قال:",
            "ChatGPT:"
        ];

        var position = -1;
        var markerLength = 0;

        for (var i = 0; i < markers.length; i++) {
            var currentPosition =
                conversationText.lastIndexOf(
                    markers[i]
                );

            if (currentPosition > position) {
                position = currentPosition;
                markerLength = markers[i].length;
            }
        }

        if (position === -1) {
            return "";
        }

        var answer =
            conversationText
                .substring(
                    position + markerLength
                )
                .trim();

        return cleanAssistantResponse(answer);
    }

    // ============================================================
    // CAPTURE STATE
    // ============================================================

    var baselineAssistant = "";
    var captureActive = false;
    var captureFinished = false;
    var lastCandidate = "";
    var stableSince = 0;

    function getCurrentAssistantText() {
        var containers =
            getConversationContainers();

        if (containers.length === 0) {
            return "";
        }

        var lastContainer =
            containers[containers.length - 1];

        var text =
            getConversationText(lastContainer);

        return extractAssistantText(text);
    }

    function startResponseCapture() {
        captureActive = true;
        captureFinished = false;
        lastCandidate = "";
        stableSince = 0;

        var containers =
            getConversationContainers();

        if (containers.length > 0) {
            var lastContainer =
                containers[containers.length - 1];

            var baselineText =
                getConversationText(
                    lastContainer
                );

            baselineAssistant =
                extractAssistantText(
                    baselineText
                );
        } else {
            baselineAssistant = "";
        }

        log(
            "🧪 Response capture started " +
            "baselineAssistant=" +
            baselineAssistant.length
        );

        watchForResponse();
    }

    // ============================================================
    // FINAL RESPONSE
    // ============================================================

    function finishCapture(answer) {
        if (captureFinished) {
            return;
        }

        captureFinished = true;
        captureActive = false;

        answer = cleanAssistantResponse(
            answer || ""
        );

        if (!answer) {
            log(
                "⚠️ Clean assistant response empty"
            );
            return;
        }

        log(
            "🧹 Response cleaned len=" +
            answer.length
        );

        log(
            "📝 CLEAN RESPONSE: " +
            answer.substring(0, 1200)
        );

        try {
            browser.runtime.sendMessage({
                type: "ASSISTANT_RESPONSE",
                text: answer,
                source: "chatgpt",
                timestamp: Date.now()
            });

            log(
                "📤 CLEAN ASSISTANT_RESPONSE sent"
            );

        } catch (e) {
            log(
                "❌ ASSISTANT_RESPONSE send failed: " +
                e
            );
        }
    }

    // ============================================================
    // RESPONSE WATCHER
    // ============================================================

    function watchForResponse() {
        if (!captureActive || captureFinished) {
            return;
        }

        var current =
            getCurrentAssistantText();

        if (!current) {
            setTimeout(
                watchForResponse,
                1000
            );
            return;
        }

        // Ignore the old response.
        if (
            baselineAssistant &&
            current === baselineAssistant
        ) {
            setTimeout(
                watchForResponse,
                1000
            );
            return;
        }

        // Response is still changing.
        if (current !== lastCandidate) {
            lastCandidate = current;
            stableSince = Date.now();

            log(
                "🔄 Assistant response changed len=" +
                current.length
            );

            setTimeout(
                watchForResponse,
                1200
            );

            return;
        }

        // Response has stopped changing.
        if (
            lastCandidate &&
            stableSince > 0 &&
            Date.now() - stableSince >= 1800
        ) {
            finishCapture(
                lastCandidate
            );

            return;
        }

        setTimeout(
            watchForResponse,
            800
        );
    }

    // ============================================================
    // MESSAGE FROM BACKGROUND
    // ============================================================

    browser.runtime.onMessage.addListener(
        function (message) {

            if (!message) {
                return;
            }

            if (
                message.type ===
                "CONTEXT_TO_PAGE"
            ) {

                var text =
                    message.text || "";

                log(
                    "📥 CONTEXT_TO_PAGE received len=" +
                    text.length
                );

                if (!text) {
                    log(
                        "⚠️ CONTEXT_TO_PAGE text empty"
                    );
                    return;
                }

                // Capture starts BEFORE sending.
                startResponseCapture();

                findInputWithRetry(
                    10,
                    500,
                    function (input) {

                        var selectorInfo =
                            input.tagName +
                            (
                                input.id
                                    ? "#" +
                                      input.id
                                    : ""
                            ) +
                            (
                                input.className
                                    ? "." +
                                      String(
                                          input.className
                                      )
                                      .split(/\s+/)
                                      .slice(0, 4)
                                      .join(".")
                                    : ""
                            );

                        log(
                            "🔎 ChatGPT input found: " +
                            selectorInfo
                        );

                        var injected =
                            injectText(
                                input,
                                text
                            );

                        if (!injected) {
                            return;
                        }

                        setTimeout(
                            function () {
                                sendEnter(input);
                            },
                            300
                        );
                    }
                );
            }
        }
    );

    // ============================================================
    // READY
    // ============================================================

    try {
        browser.runtime.sendMessage({
            type: "CONTENT_READY",
            version: VERSION,
            url: location.href
        });

        log("📡 CONTENT_READY sent");

    } catch (e) {
        log(
            "❌ CONTENT_READY failed: " +
            e
        );
    }

})();
