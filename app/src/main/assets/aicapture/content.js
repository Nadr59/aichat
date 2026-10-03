(function () {  
    "use strict";  
    if (window !== window.top) return;  
  
    var element = document.createElement("div");  
    element.textContent = "⏳ في انتظار Kotlin → Background → Content...";  
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
    element.style.border = "2px solid yellow";  
    document.documentElement.appendChild(element);  
  
    browser.runtime.onMessage.addListener(function (message) {  
        console.log("[AiChat TEST] Message from background:", message);  
  
        if (message && message.type === "REVERSE_TEST") {  
            element.textContent = "✅ Kotlin → Background → Content نجح: " + message.text;  
            element.style.background = "green";  
            console.log("✅ SUCCESS DISPLAYED");  
            return Promise.resolve({ ok: true, received: true });  
        }  
        return Promise.resolve({ ok: false });  
    });  
      
    console.log("✅ content.js ready on " + location.href);  
})();  
