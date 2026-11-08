/*
 * capture.js — التقاط عام لصفحة ظاهرة وتحويلها إلى "مواد" جاهزة للوكلاء.
 * ملف مستقل: لا يعدّل content.js ولا يتفاعل مع مسار ChatGPT/Venice/Arena.
 *
 * سلّم الالتقاط (يجرّب بالترتيب حتى ينجح أحدها):
 *   1) تحديد المستخدم            ← هو المادة (method = "selection")
 *   2) قائمة عناصر متكررة        ← مادة لكل عنصر، معرّفها رابطه (method = "feed")
 *   3) Readability (إن وُجدت)    ← مقال نظيف (method = "readability")
 *   4) عنصر دلالي (article/main) ← (method = "semantic")
 *   5) كثافة النص                ← (method = "density")
 *   6) body بعد حذف الشوائب       ← (method = "body")
 *
 * اختياري: ضع Readability.js (Mozilla) قبل هذا الملف في content_scripts.js
 * بالـmanifest. إن لم يوجد يتخطى الكود الخطوة 3 تلقائيًا.
 *
 * الرسائل المرسلة إلى background:
 *   MATERIAL_CAPTURE       مادة واحدة (تُرسل واحدة لكل مادة)
 *   MATERIAL_CAPTURE_DONE  ملخص الدفعة (count / method / reason)
 *   DEBUG_INFO             سطر للسجل
 * الرسالة المستقبَلة من background (اختيارية):
 *   MATERIAL_CAPTURE_REQUEST  { mode: "auto" | "feed" | "page" }
 */
(function () {
"use strict";

if (window.top !== window) return;
// حماية مسار ChatGPT/Venice/Arena: لا نعمل فيها حتى لو أخطأ manifest
if (/(^|\.)(chatgpt\.com|openai\.com|venice\.ai|arena\.ai)$/i.test(String(location.hostname || ""))) return;
if (window.__aichatCaptureLoaded) return;
window.__aichatCaptureLoaded = true;

var VERSION = "1.0.61";
var SHOW_BUTTON = true;

var LIMITS = {
  MAX_TEXT: 8000,
  MAX_LINKS: 40,
  MAX_ITEMS: 50,
  MIN_PAGE_TEXT: 200,
  MIN_ITEM_TEXT: 15,
  MIN_SIBLING_TEXT: 40,
  MAX_WALK_NODES: 8000
};

var HOST = String(location.hostname || "");
var api = (typeof browser !== "undefined") ? browser
  : (typeof chrome !== "undefined" ? chrome : null);

// صفحات لا يُلتقط منها (خاصة/حساسة)
var BLOCKED_HOSTS = /(^|\.)(mail\.google\.com|outlook\.live\.com|outlook\.office\.com|accounts\.google\.com|paypal\.com)$/i;
var BLOCKED_PATHS = /^\/(messages|notifications|settings|account|login|signin|direct|inbox|i\/(chat|messages|flow))(\/|$)/i;

// ============================================================
// Messaging
// ============================================================

var lastSendError = "";

function sendToBackground(payload) {
  try {
    if (!api || !payload || !payload.type) return;
    payload.source = payload.source || "capture.js";
    payload.platform = payload.platform || "capture";
    payload.host = payload.host || HOST;
    payload.url = payload.url || location.href;
    payload.version = payload.version || VERSION;
    payload.timestamp = payload.timestamp || Date.now();
    var p = api.runtime.sendMessage(payload);
    if (p && p.catch) p.catch(function (err) {
      lastSendError = String(err && err.message ? err.message : err);
    });
  } catch (e) {
    lastSendError = String(e && e.message ? e.message : e);
  }
}

function log(msg) {
  sendToBackground({ type: "DEBUG_INFO", info: String(msg || "") });
}

// ============================================================
// Text helpers
// ============================================================

function cleanText(s) {
  return String(s || "")
    .replace(/\u00a0/g, " ")
    .replace(/\r/g, "")
    .replace(/[ \t]+\n/g, "\n")
    .replace(/\n[ \t]+/g, "\n")
    .replace(/[ \t]{2,}/g, " ")
    .replace(/\n{3,}/g, "\n\n")
    .trim();
}

function hash(s) {
  var h = 5381;
  s = String(s || "");
  for (var i = 0; i < s.length; i++) h = ((h << 5) + h + s.charCodeAt(i)) | 0;
  return (h >>> 0).toString(36);
}

function cleanUrl(u) {
  try {
    var x = new URL(u, location.href);
    x.hash = "";
    var rm = [];
    x.searchParams.forEach(function (v, k) {
      if (/^(utm_|fbclid|gclid|igshid|ref_src)/i.test(k)) rm.push(k);
    });
    rm.forEach(function (k) { x.searchParams.delete(k); });
    return x.href;
  } catch (e) {
    return String(u || "");
  }
}

function textLen(el) {
  try { return (el.textContent || "").length; } catch (e) { return 0; }
}

function linkTextLen(el) {
  var n = 0;
  try {
    var as = el.querySelectorAll("a");
    for (var i = 0; i < as.length && i < 400; i++) {
      n += (as[i].textContent || "").length;
    }
  } catch (e) {}
  return n;
}

function isVisible(el) {
  try {
    var r = el.getBoundingClientRect();
    if (r.width < 1 && r.height < 1) return false;
    var cs = getComputedStyle(el);
    return !(cs.display === "none" || cs.visibility === "hidden");
  } catch (e) {
    return true;
  }
}

function detectLang(text) {
  var s = String(text || "").slice(0, 1500);
  var ar = (s.match(/[\u0600-\u06FF]/g) || []).length;
  var la = (s.match(/[A-Za-z]/g) || []).length;
  if (ar > la && ar >= 8) return "ar";
  var htmlLang = String(document.documentElement.lang || "").split("-")[0].toLowerCase();
  return htmlLang || "und";
}

function truncate(text) {
  var max = LIMITS.MAX_TEXT;
  if (text.length <= max) return { text: text, truncated: false };
  var cut = text.slice(0, max);
  var nl = cut.lastIndexOf("\n");
  if (nl > max * 0.7) cut = cut.slice(0, nl);
  return { text: cut.trim() + " …", truncated: true };
}

var NOISE_LINE = /^(share|follow|following|subscribe|sign in|sign up|log in|login|menu|search|reply|retweet|repost|like|more|show more|read more|advertisement|ad|sponsored|promoted|cookies?|accept( all)?|مشاركة|تابع|متابعة|اشترك|اشتراك|تسجيل الدخول|إنشاء حساب|المزيد|عرض المزيد|اقرأ المزيد|إعلان|ممول|رد|إعجاب)$/i;

function filterLines(text) {
  var lines = String(text || "").split("\n");
  var out = [];
  var prev = "";
  for (var i = 0; i < lines.length; i++) {
    var l = lines[i].trim();
    if (!l) {
      if (out.length && out[out.length - 1] !== "") out.push("");
      continue;
    }
    if (NOISE_LINE.test(l)) continue;
    if (l === prev) continue;
    out.push(l);
    prev = l;
  }
  return cleanText(out.join("\n"));
}

// ============================================================
// DOM walker: يقرأ النص الظاهر فقط ويتخطى الشوائب
// ------------------------------------------------------------
// لا يعتمد على innerText لأن نسخة منفصلة (clone) لا تحسب التخطيط
// فتفقد فواصل الأسطر. نبني الفواصل بأنفسنا من أنواع العناصر.
// ============================================================

var SKIP_TAGS = {
  SCRIPT: 1, STYLE: 1, NOSCRIPT: 1, SVG: 1, CANVAS: 1, IFRAME: 1,
  NAV: 1, FOOTER: 1, ASIDE: 1, BUTTON: 1, INPUT: 1, SELECT: 1,
  TEXTAREA: 1, TEMPLATE: 1, VIDEO: 1, AUDIO: 1, OBJECT: 1, EMBED: 1
};

var BLOCK_TAGS = {
  P: 1, DIV: 1, LI: 1, UL: 1, OL: 1, H1: 1, H2: 1, H3: 1, H4: 1, H5: 1,
  H6: 1, SECTION: 1, ARTICLE: 1, MAIN: 1, HEADER: 1, BLOCKQUOTE: 1,
  PRE: 1, TABLE: 1, TR: 1, DL: 1, DT: 1, DD: 1, FIGURE: 1,
  FIGCAPTION: 1, ADDRESS: 1, HR: 1
};

var JUNK_ROLES = /^(navigation|banner|complementary|contentinfo|dialog|alertdialog|search|menu|menubar|toolbar|tablist)$/i;

var JUNK_SELECTOR =
  'nav, footer, aside, [role="navigation"], [role="banner"], ' +
  '[role="complementary"], [role="contentinfo"], [role="dialog"], ' +
  '[role="alertdialog"], [aria-modal="true"], [aria-hidden="true"], [hidden]';

function extractText(root, opts) {
  opts = opts || {};
  var check = opts.checkVisible !== false;
  var skipJunk = opts.skipJunk !== false;
  var st = {
    out: [],
    len: 0,
    nodes: 0,
    max: opts.maxChars || (LIMITS.MAX_TEXT * 2)
  };

  function endsWithBreak() {
    return !st.out.length || st.out[st.out.length - 1] === "\n";
  }

  function nl() {
    if (!endsWithBreak()) st.out.push("\n");
  }

  function walk(n, inPre) {
    if (st.len >= st.max || st.nodes > LIMITS.MAX_WALK_NODES) return;
    st.nodes++;

    if (n.nodeType === 3) {
      var v = n.nodeValue || "";
      if (!inPre) v = v.replace(/\s+/g, " ");
      if (!v) return;
      var last = st.out.length ? st.out[st.out.length - 1] : "\n";
      if (/^\s/.test(v) && /\s$/.test(last)) v = v.replace(/^\s+/, "");
      if (!v) return;
      st.out.push(v);
      st.len += v.length;
      return;
    }

    if (n.nodeType !== 1) return;

    var tag = String(n.tagName || "").toUpperCase();
    if (SKIP_TAGS[tag]) return;

    if (skipJunk) {
      if (n.hasAttribute("hidden")) return;
      if (n.getAttribute("aria-hidden") === "true") return;
      if (n.getAttribute("aria-modal") === "true") return;
      var role = n.getAttribute("role");
      if (role && JUNK_ROLES.test(role)) return;
      var cls = typeof n.className === "string" ? n.className : "";
      if (cls && /(^|\s)sr-only(\s|$)/.test(cls)) return;
    }

    if (check) {
      try {
        var cs = getComputedStyle(n);
        if (cs.display === "none" || cs.visibility === "hidden") return;
      } catch (e) {}
    }

    if (tag === "BR") {
      st.out.push("\n");
      return;
    }

    var isBlock = !!BLOCK_TAGS[tag];
    if (isBlock) nl();
    if ((tag === "TD" || tag === "TH") && !endsWithBreak()) st.out.push(" | ");

    var pre = inPre || tag === "PRE";
    var kids = n.childNodes;
    for (var i = 0; i < kids.length; i++) walk(kids[i], pre);

    try {
      if (n.shadowRoot) {
        var sk = n.shadowRoot.childNodes;
        for (var j = 0; j < sk.length; j++) walk(sk[j], pre);
      }
    } catch (e2) {}

    if (isBlock) nl();
  }

  try { walk(root, false); } catch (e3) {}
  return cleanText(st.out.join(""));
}

// ============================================================
// Links
// ============================================================

function collectLinks(root, max) {
  var out = [];
  var seen = {};
  var self = cleanUrl(location.href);
  var list;

  try { list = root.querySelectorAll("a[href]"); } catch (e) { return out; }

  for (var i = 0; i < list.length && i < 600 && out.length < max; i++) {
    var a = list[i];
    var href = a.href || "";
    if (!/^https?:/i.test(href)) continue;
    href = cleanUrl(href);
    if (href === self || seen[href]) continue;

    try {
      if (a.closest && a.closest(JUNK_SELECTOR)) continue;
    } catch (_) {}

    var t = cleanText(a.innerText || a.textContent || "");
    if (!t) t = cleanText(a.getAttribute("aria-label") || a.getAttribute("title") || "");
    if (!t) continue;
    if (t.length > 160) t = t.slice(0, 160);

    seen[href] = 1;
    out.push({ text: t, href: href });
  }

  return out;
}

// ============================================================
// Page metadata (og / JSON-LD / time)
// ============================================================

function metaContent(sel) {
  try {
    var m = document.querySelector(sel);
    var v = m && (m.getAttribute("content") || m.getAttribute("datetime"));
    return v ? cleanText(v) : "";
  } catch (e) {
    return "";
  }
}

function readJsonLd() {
  var info = {};

  try {
    var nodes = document.querySelectorAll('script[type="application/ld+json"]');

    for (var i = 0; i < nodes.length && i < 12; i++) {
      var data;
      try { data = JSON.parse(nodes[i].textContent); } catch (e) { continue; }

      var stack = [data];
      var guard = 0;

      while (stack.length && guard++ < 200) {
        var d = stack.shift();
        if (!d || typeof d !== "object") continue;
        if (Array.isArray(d)) { stack = stack.concat(d); continue; }
        if (d["@graph"]) stack = stack.concat(d["@graph"]);

        var t = d["@type"];
        var ts = Array.isArray(t) ? t.join(",") : String(t || "");

        if (/Article|BlogPosting|NewsArticle|Report|SocialMediaPosting|VideoObject/i.test(ts)) {
          if (!info.headline && d.headline) info.headline = String(d.headline);
          if (!info.date && (d.datePublished || d.uploadDate)) {
            info.date = String(d.datePublished || d.uploadDate);
          }
          var au = d.author;
          if (au && !info.author) {
            if (Array.isArray(au)) au = au[0];
            info.author = typeof au === "string"
              ? au
              : (au && au.name ? String(au.name) : "");
          }
        }
      }
    }
  } catch (e2) {}

  return info;
}

function getPageMeta() {
  var ld = readJsonLd();

  var canon = "";
  try {
    var c = document.querySelector('link[rel="canonical"]');
    if (c && c.href) {
      var cu = new URL(c.href, location.href);
      if (cu.hostname === location.hostname) canon = cu.href;
    }
  } catch (e) {}

  var h1 = "";
  try {
    var h = document.querySelector("h1");
    if (h) h1 = cleanText(h.innerText || h.textContent);
  } catch (e2) {}

  var author = metaContent('meta[name="author"]') ||
    metaContent('meta[property="article:author"]') || ld.author || "";
  if (/^https?:/i.test(author)) author = ld.author || "";

  var date = metaContent('meta[property="article:published_time"]') ||
    metaContent('meta[itemprop="datePublished"]') || ld.date || "";

  if (!date) {
    try {
      var tm = document.querySelector("article time[datetime], main time[datetime], time[datetime]");
      if (tm) date = tm.getAttribute("datetime") || "";
    } catch (e3) {}
  }

  return {
    url: cleanUrl(canon || location.href),
    title: metaContent('meta[property="og:title"]') || ld.headline || h1 ||
      cleanText(document.title),
    author: cleanText(author),
    date: cleanText(date),
    siteName: metaContent('meta[property="og:site_name"]') || HOST
  };
}

// ============================================================
// Quality + blocked pages
// ============================================================

var BLOCK_PAGE = /(log ?in|sign ?in|sign ?up|captcha|verify you are human|are you a robot|access denied|enable javascript|تسجيل الدخول|إنشاء حساب|تأكيد أنك|محظور)/i;

function assessPage(text) {
  var len = text.length;
  if (len < LIMITS.MIN_PAGE_TEXT) return { quality: "weak", reason: "too_short" };
  if (len < 1500 && BLOCK_PAGE.test(text.slice(0, 800))) {
    return { quality: "weak", reason: "login_or_block" };
  }
  return { quality: "good", reason: "" };
}

function isBlockedPage() {
  return BLOCKED_HOSTS.test(HOST) || BLOCKED_PATHS.test(String(location.pathname || ""));
}

// ============================================================
// Selection (مع ذاكرة قصيرة لأن اللمس على الزر قد يمسح التحديد)
// ============================================================

var lastSel = null;
var selTimer = 0;

function readSelection() {
  try {
    var sel = window.getSelection();
    if (!sel || sel.isCollapsed || !sel.rangeCount) return null;

    var text = cleanText(sel.toString());
    if (text.length < 10) return null;

    var holder = document.createElement("div");
    holder.appendChild(sel.getRangeAt(0).cloneContents());

    return {
      text: text,
      links: collectLinks(holder, LIMITS.MAX_LINKS),
      at: Date.now()
    };
  } catch (e) {
    return null;
  }
}

document.addEventListener("selectionchange", function () {
  clearTimeout(selTimer);
  selTimer = setTimeout(function () {
    var s = readSelection();
    if (s) lastSel = s;
  }, 200);
}, true);

function getSelectionInfo() {
  var cur = readSelection();
  if (cur) return cur;
  if (lastSel && Date.now() - lastSel.at < 10000) return lastSel;
  return null;
}

// ============================================================
// Page-level finders
// ============================================================

function tryReadability() {
  if (typeof Readability !== "function") return null;

  try {
    var doc = document.cloneNode(true);
    var art = new Readability(doc, { charThreshold: 200 }).parse();
    if (!art || !art.content) return null;

    var tmp = document.implementation.createHTMLDocument("");
    tmp.body.innerHTML = art.content;

    var text = filterLines(extractText(tmp.body, { checkVisible: false }));
    if (text.length < LIMITS.MIN_PAGE_TEXT) return null;

    return {
      text: text,
      title: cleanText(art.title || ""),
      author: cleanText(art.byline || ""),
      date: art.publishedTime || "",
      links: collectLinks(tmp.body, LIMITS.MAX_LINKS)
    };
  } catch (e) {
    log("⚠️ readability failed: " + e);
    return null;
  }
}

function findSemanticElement() {
  var sels = ['[itemprop="articleBody"]', "article", '[role="main"]', "main"];

  for (var s = 0; s < sels.length; s++) {
    var list;
    try { list = document.querySelectorAll(sels[s]); } catch (e) { continue; }

    var best = null;
    var bestLen = 0;

    for (var i = 0; i < list.length; i++) {
      var el = list[i];
      if (!isVisible(el)) continue;
      var tl = textLen(el);
      if (tl <= bestLen) continue;
      if (linkTextLen(el) / Math.max(tl, 1) > 0.5) continue;
      best = el;
      bestLen = tl;
    }

    if (best && bestLen >= LIMITS.MIN_PAGE_TEXT) return best;
  }

  return null;
}

// خوارزمية كثافة على نمط Readability: نقاط للفقرات تُضاف للأب والجد
function findDensityElement() {
  var scores = new Map();
  var nodes;

  try {
    nodes = document.querySelectorAll("p, pre, blockquote, td, div, section, li");
  } catch (e) {
    return null;
  }

  var BLOCK_CHILD = "p,div,ul,ol,table,section,article,blockquote,pre,h1,h2,h3,h4,h5,h6";

  for (var i = 0; i < nodes.length && i < 4000; i++) {
    var el = nodes[i];
    var tag = el.tagName;

    if ((tag === "DIV" || tag === "SECTION" || tag === "LI" || tag === "TD") &&
        el.querySelector(BLOCK_CHILD)) continue;

    var txt = el.textContent || "";
    var len = txt.length;
    if (len < 40) continue;

    try {
      if (el.closest(JUNK_SELECTOR)) continue;
    } catch (_) {}

    var s = 1 + (txt.match(/[,،]/g) || []).length + Math.min(Math.floor(len / 100), 3);
    var p = el.parentElement;

    if (p) {
      scores.set(p, (scores.get(p) || 0) + s);
      var g = p.parentElement;
      if (g) scores.set(g, (scores.get(g) || 0) + s / 2);
    }
  }

  var best = null;
  var bestScore = 0;

  scores.forEach(function (sc, el) {
    var tag = el.tagName;
    if (tag === "BODY" || tag === "HTML") return;
    var tl = textLen(el);
    if (tl < 1) return;
    var density = linkTextLen(el) / tl;
    var f = sc * (1 - Math.min(density, 0.95));
    if (f > bestScore) {
      bestScore = f;
      best = el;
    }
  });

  return best;
}

function tryPage() {
  var meta = getPageMeta();
  var text = "";
  var method = "";
  var title = meta.title;
  var author = meta.author;
  var date = meta.date;
  var links = [];

  var rd = tryReadability();

  if (rd) {
    text = rd.text;
    method = "readability";
    title = rd.title || title;
    author = rd.author || author;
    date = date || rd.date;
    links = rd.links;
  }

  if (!text) {
    var sem = findSemanticElement();
    if (sem) {
      var t1 = filterLines(extractText(sem));
      if (t1.length >= LIMITS.MIN_PAGE_TEXT) {
        text = t1;
        method = "semantic";
        links = collectLinks(sem, LIMITS.MAX_LINKS);
      }
    }
  }

  if (!text) {
    var den = findDensityElement();
    if (den) {
      var t2 = filterLines(extractText(den));
      if (t2.length >= LIMITS.MIN_PAGE_TEXT) {
        text = t2;
        method = "density";
        links = collectLinks(den, LIMITS.MAX_LINKS);
      }
    }
  }

  if (!text) {
    text = filterLines(extractText(document.body || document.documentElement));
    method = "body";
    links = collectLinks(document.body || document.documentElement, LIMITS.MAX_LINKS);
  }

  if (!text) return null;

  var q = assessPage(text);
  var tr = truncate(text);

  return {
    id: meta.url,
    url: meta.url,
    host: HOST,
    title: title,
    author: author,
    date: date,
    lang: detectLang(tr.text),
    text: tr.text,
    truncated: tr.truncated,
    links: links,
    method: method,
    quality: q.quality,
    reason: q.reason,
    capturedAt: Date.now()
  };
}

// ============================================================
// Feed detection (مادة لكل عنصر متكرر)
// ============================================================

function sameHostHttp(href) {
  try {
    var u = new URL(href, location.href);
    return /^https?:$/.test(u.protocol) && u.hostname === location.hostname;
  } catch (e) {
    return false;
  }
}

function permalinkOf(el) {
  try {
    var times = el.querySelectorAll("time");
    for (var i = 0; i < times.length; i++) {
      var a = times[i].closest("a[href]");
      if (a && sameHostHttp(a.href)) return cleanUrl(a.href);
    }
  } catch (e) {}

  var best = "";
  var bestDepth = 1;

  try {
    var as = el.querySelectorAll("a[href]");
    for (var j = 0; j < as.length && j < 60; j++) {
      if (!sameHostHttp(as[j].href)) continue;
      var u = new URL(as[j].href, location.href);
      var segs = u.pathname.split("/").filter(Boolean).length +
        (u.searchParams.has("v") ? 2 : 0);
      if (segs > bestDepth) {
        bestDepth = segs;
        best = cleanUrl(u.href);
      }
    }
  } catch (e2) {}

  return best;
}

function findArticleElements() {
  var list = [];

  try {
    var all = document.querySelectorAll("article, [role='article']");
    for (var i = 0; i < all.length; i++) list.push(all[i]);
  } catch (e) {}

  // نُبقي الأعلى (الخارجي) ونحذف العناصر المتداخلة داخل غيرها
  return list.filter(function (el) {
    return !list.some(function (o) { return o !== el && o.contains(el); });
  });
}

function findSiblingGroup(deadline) {
  var parents;

  try {
    parents = (document.body || document.documentElement)
      .querySelectorAll("ul, ol, div, section, main, tbody");
  } catch (e) {
    return null;
  }

  var best = null;
  var bestTotal = 0;

  for (var i = 0; i < parents.length && i < 2500; i++) {
    if (Date.now() > deadline) break;

    var p = parents[i];
    var kids = p.children;
    if (kids.length < 3 || kids.length > 300) continue;

    try {
      if (p.closest(JUNK_SELECTOR)) continue;
    } catch (_) {}

    var counts = {};
    for (var k = 0; k < kids.length; k++) {
      counts[kids[k].tagName] = (counts[kids[k].tagName] || 0) + 1;
    }

    var topTag = "";
    var topN = 0;
    for (var tg in counts) {
      if (counts[tg] > topN) { topN = counts[tg]; topTag = tg; }
    }
    if (topN < 3) continue;

    var ok = [];
    var total = 0;

    for (var m = 0; m < kids.length; m++) {
      if (kids[m].tagName !== topTag) continue;
      var tl = textLen(kids[m]);
      if (tl < LIMITS.MIN_SIBLING_TEXT) continue;
      if (!permalinkOf(kids[m])) continue;
      ok.push(kids[m]);
      total += tl;
    }

    if (ok.length >= 3 && total > bestTotal) {
      best = ok;
      bestTotal = total;
    }
  }

  return best;
}

// محوّلات اختيارية لمواقع معروفة. إن فشل محوّل يسقط الكود للطريقة العامة.
function getAdapter() {
  if (/(^|\.)(x|twitter)\.com$/i.test(HOST)) {
    return function (el) {
      var t = el.querySelector('[data-testid="tweetText"]');
      return { text: t ? extractText(t) : "" };
    };
  }
  return null;
}

function authorOf(el, link) {
  try {
    if (/(^|\.)(x|twitter)\.com$/i.test(HOST) && link) {
      var seg = new URL(link).pathname.split("/").filter(Boolean)[0];
      if (seg) return "@" + seg;
    }
    var a = el.querySelector('[rel="author"], [itemprop="author"]');
    if (a) return cleanText(a.textContent).slice(0, 80);
  } catch (e) {}
  return "";
}

function buildItems(els, adapter) {
  var out = [];
  var seen = {};
  var now = Date.now();

  for (var i = 0; i < els.length && out.length < LIMITS.MAX_ITEMS; i++) {
    var el = els[i];
    var text = "";

    if (adapter) {
      try {
        var ad = adapter(el);
        if (ad) text = ad.text || "";
      } catch (e) {}
    } else {
      text = extractText(el);
    }

    text = filterLines(text);
    if (text.length < LIMITS.MIN_ITEM_TEXT) continue;

    var link = permalinkOf(el);
    var id = link || ("t:" + hash(text.slice(0, 300) + HOST));
    if (seen[id]) continue;
    seen[id] = 1;

    var date = "";
    try {
      var tm = el.querySelector("time[datetime]");
      if (tm) date = tm.getAttribute("datetime") || "";
    } catch (e2) {}

    var tr = truncate(text);

    out.push({
      id: id,
      url: link || cleanUrl(location.href),
      host: HOST,
      title: text.split("\n")[0].slice(0, 80),
      author: authorOf(el, link),
      date: date,
      lang: detectLang(tr.text),
      text: tr.text,
      truncated: tr.truncated,
      links: collectLinks(el, LIMITS.MAX_LINKS),
      method: "feed",
      quality: "good",
      reason: "",
      capturedAt: now
    });
  }

  return out;
}

function tryFeed() {
  var deadline = Date.now() + 500;
  var els = findArticleElements();

  if (els.length < 3) {
    els = findSiblingGroup(deadline) || els;
  }

  if (!els.length) return null;

  var adapter = getAdapter();
  var items = buildItems(els, adapter);

  // سقوط تلقائي: إن أخفق المحوّل مع كل العناصر نجرّب الطريقة العامة
  if (!items.length && adapter) items = buildItems(els, null);
  if (!items.length) return null;

  var total = 0;
  var maxLen = 0;
  for (var i = 0; i < items.length; i++) {
    total += items[i].text.length;
    if (items[i].text.length > maxLen) maxLen = items[i].text.length;
  }

  // قائمة حقيقية: 3 عناصر فأكثر ولا عنصر واحد يهيمن على النص
  var isFeed = items.length >= 3 && maxLen <= total * 0.6;

  return { materials: items, method: "feed", isFeed: isFeed };
}

// ============================================================
// Orchestration
// ============================================================

function buildMaterials(opts) {
  var mode = (opts && opts.mode) || "auto";

  if (isBlockedPage()) {
    return { materials: [], method: "blocked", reason: "blocked_page" };
  }

  var sel = getSelectionInfo();

  if (sel) {
    var meta = getPageMeta();
    var tr = truncate(sel.text);

    return {
      method: "selection",
      materials: [{
        id: meta.url + "#sel:" + hash(sel.text.slice(0, 300)),
        url: meta.url,
        host: HOST,
        title: meta.title,
        author: meta.author,
        date: meta.date,
        lang: detectLang(tr.text),
        text: tr.text,
        truncated: tr.truncated,
        links: sel.links,
        method: "selection",
        quality: "good",
        reason: "",
        capturedAt: Date.now()
      }]
    };
  }

  var feed = mode === "page" ? null : tryFeed();

  if (feed && (mode === "feed" || feed.isFeed)) {
    return { materials: feed.materials, method: "feed" };
  }

  if (mode !== "feed") {
    var page = tryPage();
    if (page) return { materials: [page], method: page.method };
  }

  if (feed) return { materials: feed.materials, method: "feed" };

  return { materials: [], method: "none", reason: "no_content" };
}

function waitForQuiet(maxMs, quietMs) {
  return new Promise(function (resolve) {
    var done = false;
    var timer = 0;
    var obs = null;
    var maxTimer = setTimeout(finish, maxMs);

    function finish() {
      if (done) return;
      done = true;
      clearTimeout(timer);
      clearTimeout(maxTimer);
      try { if (obs) obs.disconnect(); } catch (e) {}
      resolve();
    }

    try {
      obs = new MutationObserver(function () {
        clearTimeout(timer);
        timer = setTimeout(finish, quietMs);
      });
      obs.observe(document.documentElement, {
        childList: true,
        subtree: true,
        characterData: true
      });
    } catch (e2) {
      finish();
      return;
    }

    timer = setTimeout(finish, quietMs);
  });
}

function deliver(result) {
  var list = result.materials || [];
  var batchId = "b" + Date.now().toString(36);
  var chars = 0;
  var links = 0;

  for (var i = 0; i < list.length; i++) {
    var m = list[i];
    chars += m.text.length;
    links += m.links.length;

    sendToBackground({
      type: "MATERIAL_CAPTURE",
      batchId: batchId,
      index: i,
      total: list.length,
      id: m.id,
      url: m.url,
      host: m.host,
      title: m.title,
      author: m.author,
      date: m.date,
      lang: m.lang,
      text: m.text,
      links: m.links,
      method: m.method,
      quality: m.quality,
      reason: m.reason || "",
      truncated: !!m.truncated,
      capturedAt: m.capturedAt
    });
  }

  sendToBackground({
    type: "MATERIAL_CAPTURE_DONE",
    batchId: batchId,
    count: list.length,
    method: result.method || "",
    reason: result.reason || ""
  });

  log(
    "📦 capture method=" + (result.method || "") +
    " items=" + list.length +
    " chars=" + chars +
    " links=" + links +
    (result.reason ? " reason=" + result.reason : "")
  );
}

var busy = false;

function captureNow(opts) {
  if (busy) return Promise.resolve(null);
  busy = true;

  return waitForQuiet(1200, 250)
    .then(function () {
      var result = buildMaterials(opts || {});
      deliver(result);
      return result;
    })
    .catch(function (e) {
      log("❌ capture failed: " + (e && e.message ? e.message : String(e)));
      sendToBackground({
        type: "MATERIAL_CAPTURE_DONE",
        count: 0,
        method: "error",
        reason: "exception"
      });
      return null;
    })
    .then(function (r) {
      busy = false;
      return r;
    });
}

// ============================================================
// Floating button (Shadow DOM). التأكيد الفعلي يأتي من التطبيق (Toast)
// ============================================================

function installButton() {
  if (!SHOW_BUTTON || !document.documentElement) return;

  var host = document.createElement("div");
  host.id = "aichat-capture-host";
  host.style.cssText =
    "all:initial;position:fixed;z-index:2147483647;bottom:96px;right:12px;";

  var root = host.attachShadow({ mode: "closed" });
  var style = document.createElement("style");
  style.textContent =
    "button{all:initial;box-sizing:border-box;width:52px;height:52px;" +
    "border-radius:26px;background:#1f2937;color:#fff;font:600 13px/52px " +
    "system-ui,sans-serif;text-align:center;cursor:pointer;" +
    "border:1px solid rgba(255,255,255,.25);}" +
    "button[disabled]{opacity:.6}";

  var btn = document.createElement("button");
  btn.type = "button";
  btn.textContent = "التقط";
  btn.setAttribute("aria-label", "التقاط محتوى الصفحة");

  // منع مسح تحديد المستخدم عند الضغط بالماوس (اللمس يعتمد على lastSel)
  btn.addEventListener("mousedown", function (e) { e.preventDefault(); });

  btn.addEventListener("click", function (e) {
    e.preventDefault();
    e.stopPropagation();
    if (btn.disabled) return;
    btn.disabled = true;
    btn.textContent = "…";
    lastSendError = "";
    log("🔘 click start");
    captureNow({ mode: "auto" }).then(function (r) {
      var n = (r && r.materials) ? r.materials.length : -1;
      btn.disabled = false;
      setTimeout(function () {
        btn.textContent = lastSendError ? "✗" : (n >= 0 ? "✓" + n : "؟");
        log("🔘 click done items=" + n + " sendErr=" + (lastSendError || "none"));
        setTimeout(function () { btn.textContent = "التقط"; }, 2500);
      }, 400);
    });
  });

  root.appendChild(style);
  root.appendChild(btn);
  document.documentElement.appendChild(host);

  // الصفحات التي تعيد بناء الـDOM قد تزيل الزر
  try {
    new MutationObserver(function () {
      if (!host.isConnected && document.documentElement) {
        document.documentElement.appendChild(host);
      }
    }).observe(document.documentElement, { childList: true });
  } catch (e) {}

  // إخفاء الزر في الصفحات المحظورة (تتغير مع تنقل SPA)
  setInterval(function () {
    host.style.display = isBlockedPage() ? "none" : "";
  }, 1500);
}

// ============================================================
// Messages from background
// ============================================================

if (api && api.runtime && api.runtime.onMessage) {
  api.runtime.onMessage.addListener(function (message) {
    if (message && message.type === "MATERIAL_CAPTURE_REQUEST") {
      captureNow({ mode: message.mode || "auto" });
      return Promise.resolve({ ok: true, type: "MATERIAL_CAPTURE_REQUEST" });
    }
    return undefined;
  });
}

try { installButton(); } catch (e) { log("⚠️ button install failed: " + e); }
log("🧩 capture.js loaded v=" + VERSION + " host=" + HOST);

})();
