(function () {
    "use strict";

    // ============================================================
    // AiChat — REAL ASSISTANT RESPONSE EXTRACTION TEST
    // Version 1.0.30
    // ============================================================

    var VERSION = "1.0.30";

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
    // INPUT FINDER
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
                var element = document.querySelector(INPUT_SELECTORS[i]);

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
    // TEXT INJECTION
    // ============================================================

    function injectText(input, text) {
        try {
            if (
                input.tagName === "TEXTAREA" ||
                input.tagName === "INPUT"
            ) {
                var prototype = Object.getPrototypeOf(input);
                var descriptor = Object.getOwnPropertyDescriptor(
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

            var eventOptions = {
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
                    eventOptions
                )
            );

            input.dispatchEvent(
                new KeyboardEvent(
                    "keypress",
                    eventOptions
                )
            );

            input.dispatchEvent(
                new KeyboardEvent(
                    "keyup",
                    eventOptions
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
    // REAL CHATGPT CONVERSATION CONTAINER
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
            return (element.innerText || element.textContent || "")
                .replace(/\u00a0/g, " ")
                .replace(/\r/g, "")
                .trim();
        } catch (e) {
            return "";
        }
    }

    // ============================================================
    // EXTRACT ONLY ASSISTANT PART
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
                conversationText.lastIndexOf(markers[i]);

            if (currentPosition > position) {
                position = currentPosition;
                markerLength = markers[i].length;
            }
        }

        if (position === -1) {
            return "";
        }

        var answer = conversationText
            .substring(position + markerLength)
            .trim();

        // Remove ChatGPT footer.
        var footer = "ChatGPT هو نظام ذكاء اصطناعي وقد يخطئ.";

        var footerPosition = answer.indexOf(footer);

        if (footerPosition !== -1) {
            answer = answer
                .substring(0, footerPosition)
                .trim();
        }

        // Remove the sources section from the extracted answer.
        var sourcesMarkers = [
            "\nF المصادر",
            "F المصادر"
        ];

        for (var j = 0; j < sourcesMarkers.length; j++) {
            var sourcesPosition =
                answer.indexOf(sourcesMarkers[j]);

            if (sourcesPosition !== -1) {
                answer = answer
                    .substring(0, sourcesPosition)
                    .trim();

                break;
            }
        }

        return answer;
    }

    // ============================================================
    // RESPONSE STATE
    // ============================================================

    var baselineAssistant = "";
    var baselineConversation = "";
    var captureActive = false;
    var captureFinished = false;
    var lastCandidate = "";
    var stableSince = 0;

    function getCurrentAssistantText() {
        var containers = getConversationContainers();

        if (containers.length === 0) {
            return "";
        }

        // Use the last conversation container.
        var lastContainer =
            containers[containers.length - 1];

        var text = getConversationText(lastContainer);

        return extractAssistantText(text);
    }

    function startResponseCapture() {
        captureActive = true;
        captureFinished = false;
        lastCandidate = "";
        stableSince = 0;

        var containers = getConversationContainers();

        if (containers.length > 0) {
            var lastContainer =
                containers[containers.length - 1];

            baselineConversation =
                getConversationText(lastContainer);

            baselineAssistant =
                extractAssistantText(
                    baselineConversation
                );
        } else {
            baselineConversation = "";
            baselineAssistant = "";
        }

        log(
            "🧪 Response capture started " +
            "baselineAssistant=" +
            baselineAssistant.length +
            " baselineConversation=" +
            baselineConversation.length
        );

        watchForResponse();
    }

    function finishCapture(answer) {
        if (captureFinished) {
            return;
        }

        captureFinished = true;
        captureActive = false;

        answer = (answer || "").trim();

        if (!answer) {
            log("⚠️ Assistant answer empty");
            return;
        }

        log(
            "✅ REAL ASSISTANT RESPONSE extracted len=" +
            answer.length
        );

        log(
            "📝 REAL RESPONSE: " +
            answer.substring(0, 1200)
        );

        try {
            browser.runtime.sendMessage({
                type: "ASSISTANT_RESPONSE",
                text: answer,
                source: "chatgpt",
                timestamp: Date.now()
            });

            log("📤 ASSISTANT_RESPONSE sent to background");
        } catch (e) {
            log(
                "❌ ASSISTANT_RESPONSE send failed: " +
                e
            );
        }
    }

    function watchForResponse() {
        if (!captureActive || captureFinished) {
            return;
        }

        var current = getCurrentAssistantText();

        if (!current) {
            setTimeout(watchForResponse, 1000);
            return;
        }

        // Ignore the old assistant response.
        if (
            baselineAssistant &&
            current === baselineAssistant
        ) {
            setTimeout(watchForResponse, 1000);
            return;
        }

        // New response detected.
        if (current !== lastCandidate) {
            lastCandidate = current;
            stableSince = Date.now();

            log(
                "🔄 Assistant response changed len=" +
                current.length
            );

            setTimeout(watchForResponse, 1200);
            return;
        }

        // Wait until the response remains unchanged.
        if (
            lastCandidate &&
            stableSince > 0 &&
            Date.now() - stableSince >= 1800
        ) {
            finishCapture(lastCandidate);
            return;
        }

        setTimeout(watchForResponse, 800);
    }

    // ============================================================
    // RECEIVE COMMAND FROM BACKGROUND
    // ============================================================

    browser.runtime.onMessage.addListener(
        function (message) {

            if (!message) {
                return;
            }

            // ----------------------------------------------------
            // CONTEXT_TO_PAGE
            // ----------------------------------------------------

            if (message.type === "CONTEXT_TO_PAGE") {

                var text = message.text || "";

                log(
                    "📥 CONTEXT_TO_PAGE received len=" +
                    text.length
                );

                if (!text) {
                    log("⚠️ CONTEXT_TO_PAGE text empty");
                    return;
                }

                // Start capture BEFORE sending the request.
                startResponseCapture();

                findInputWithRetry(
                    10,
                    500,
                    function (input) {

                        var selectorInfo =
                            input.tagName +
                            (input.id
                                ? "#" + input.id
                                : "") +
                            (input.className
                                ? "." +
                                  String(input.className)
                                      .split(/\s+/)
                                      .slice(0, 4)
                                      .join(".")
                                : "");

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
    // INITIAL PING
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
