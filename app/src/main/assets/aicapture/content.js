(function () {
    "use strict";

    if (window !== window.top) return;

    var element = document.createElement("div");

    element.id = "aichat-content-test-1023";
    element.textContent =
        "✅ AiChat content.js v1.0.23 يعمل";

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

})();
