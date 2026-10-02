"use strict";
var NATIVE_APP = "browser"; // اتركه "browser" لأن PING نجح عندك بهذا الاسم
var FORWARD_TYPES = { AI_RESPONSE: true, CAPTURE_RESULT: true, CONTEXT_WRITTEN: true, CONTEXT_CONSUMED: true, DEBUG_INFO: true };
var QUIET_TYPES = { POLL: true, DEBUG_INFO: true };
var EMPTY_POLL = { capture: false, hasContext: false, context: "", id: 0 };

function logLocal(message) {
  var ts = new Date().toLocaleTimeString("en-US", {hour12:false});
  console.log("[Background] " + ts + "  " + message);
}
var lastPollError = "";
function logPollErrorOnce(msg) { if(msg !== lastPollError){ lastPollError=msg; logLocal("❌ POLL error: "+msg); } }
function normalize(r){ if(typeof r==="string"){ try{return JSON.parse(r)}catch(e){return r} } return r; }
function sendToNative(payload){
  var quiet = !!QUIET_TYPES[payload.type];
  if(!quiet) logLocal("🔄 → native: " + payload.type);
  return browser.runtime.sendNativeMessage(NATIVE_APP, payload)
    .then(function(r){ var n=normalize(r); if(!quiet) logLocal("✅ native replied: "+payload.type+" → "+JSON.stringify(n)); return n; })
    .catch(function(e){ var m=e&&e.message?e.message:String(e); logLocal("❌ native failed: "+payload.type+" — "+m); throw e; });
}

browser.runtime.onMessage.addListener(function(message, sender){
  if(!message || !message.type){
    logLocal("❌ Message without type");
    return Promise.resolve({ok:false, error:"No message type"});
  }
  var type = message.type;
  logLocal("📩 ← content: " + type + " " + JSON.stringify(message).substring(0,200));

  if(type === "POLL"){
    return sendToNative({type:"POLL", domain: message.domain||"", visible: message.visible!==false})
      .then(function(r){ r=r||{}; lastPollError=""; return {ok:true, capture:!!r.capture, hasContext:!!r.hasContext, context:r.context||"", id:r.id||0}; })
      .catch(function(e){ logPollErrorOnce(e&&e.message?e.message:String(e)); return EMPTY_POLL; });
  }

  // === الاختبار الحاسم - الآن خارج أي if آخر ===
  if(type === "DIRECT_TEST_2"){
    logLocal(\'🧪 DIRECT_TEST_2 received from content.js: \' + (message.text||\'\'));
    return sendToNative({
        type: "DIRECT_TEST_2",
        source: message.source || "unknown",
        text: message.text || "",
        timestamp: message.timestamp || ""
    }).then(function(response){
        logLocal(\'✅ DIRECT_TEST_2 native response received: \'+JSON.stringify(response));
        return { ok: true, native: true, response: normalize(response) };
    }).catch(function(error){
        var msg = error&&error.message?error.message:String(error);
        logLocal(\'❌ DIRECT_TEST_2 native FAILED: \' + msg);
        return { ok: false, native: false, error: msg };
    });
  }

  if(type === "AICHAT_NATIVE_TEST"){
    logLocal("🧪 AICHAT_NATIVE_TEST received");
    var payload = { type:"AICHAT_NATIVE_TEST", source: message.source||"content.js", timestamp: message.timestamp||new Date().toLocaleTimeString(), test:true };
    return sendToNative(payload)
      .then(function(r){ var n=normalize(r); logLocal("🎯 Kotlin response: "+JSON.stringify(n)); return {ok:true, native:true, response:n}; })
      .catch(function(e){ var m=e&&e.message?e.message:String(e); logLocal("❌ Kotlin response failed: "+m); return {ok:false, native:false, error:m}; });
  }

  if(FORWARD_TYPES[type]){
    return sendToNative(message)
      .then(function(r){ return {ok:true, native:true, response:normalize(r)}; })
      .catch(function(e){ return {ok:false, native:false, error:e&&e.message?e.message:String(e)}; });
  }

  logLocal("⚠️ UNKNOWN_TYPE: " + type);
  return Promise.resolve({ok:false, error:"Unknown message type: "+type});
});

browser.runtime.onInstalled.addListener(function(d){ logLocal(d.reason==="install"?"🎉 Extension installed":d.reason==="update"?"🔄 Extension updated":"ℹ️ onInstalled: "+d.reason); });
logLocal("✅ Background script ready, v=" + browser.runtime.getManifest().version);
logLocal("🧪 Testing Native Messaging...");
sendToNative({type:"PING"}).then(function(r){ logLocal("🏓 PING reply: "+JSON.stringify(r)); }).catch(function(e){ logLocal("🏓 PING failed: "+(e&&e.message?e.message:String(e))); });
