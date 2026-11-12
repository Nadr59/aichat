package com.example.aichat.repository

import org.json.JSONObject

/**
 * يحفظ ردّ نموذج مادةً في العلبة.
 * يعيد true إن كانت المادة جديدة، وfalse إن حدّث مادة سابقة بالمفتاح نفسه،
 * وnull إن كان النص فارغًا. يُستدعى من الخيط الرئيسي فقط (كما قاعدة MaterialStore).
 */
fun exportReplyToMaterial(
    store: MaterialStore,
    replyText: String,
    sourceLabel: String,
    key: String
): Boolean? {
    val text = replyText.trim()
    if (text.isEmpty()) return null

    val firstLine = text.lineSequence()
        .map { it.trim().trimStart('#', '*', '-', ' ') }
        .firstOrNull { it.isNotEmpty() } ?: ""
    val title = firstLine.take(60).ifBlank { "ردّ نموذج" }

    val o = JSONObject()
        .put("id", "reply:$key")
        .put("url", "")
        .put("host", sourceLabel)
        .put("title", title)
        .put("text", text)
        .put("method", "reply")
        .put("quality", "good")
        .put("reason", "ردّ نموذج")
        .put("capturedAt", System.currentTimeMillis())

    // fromJson يطبّق حد 8000 حرف نفسه، فلا أكرر منطقه
    val item = MaterialItem.fromJson(o) ?: return null
    // دفعة فريدة في كل مرة ليظهر الردّ في أول القائمة
    return store.upsert(item, "reply:${System.currentTimeMillis()}")
}
