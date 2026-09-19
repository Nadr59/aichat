// background.js — مطلوب لتفعيل nativeMessaging
// يعمل كوسيط بين content.js و Kotlin

browser.runtime.onConnect.addListener(function(port) {
    // لا نحتاج شيء هنا
    // GeckoView يتولى الاتصال مباشرة
});
