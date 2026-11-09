package com.example.aichat

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.example.aichat.repository.SharedMaterialParser

/**
 * نشاط شفاف بلا واجهة: يستقبل المشاركة (ACTION_SEND) والنص المحدّد (PROCESS_TEXT)،
 * يضيفهما إلى علبة المواد، يعرض Toast، ثم ينتهي فورًا فيعود المستخدم إلى تطبيقه.
 * ينتهي دائمًا داخل onCreate، فلا توجد إعادة معالجة عند تدوير الشاشة.
 */
class ShareReceiverActivity : Activity() {

    // ثوابت نصية لتفادي اشتراط API 23 في Intent.ACTION_PROCESS_TEXT / EXTRA_PROCESS_TEXT
    private val actionProcessText = "android.intent.action.PROCESS_TEXT"
    private val extraProcessText = "android.intent.extra.PROCESS_TEXT"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val app = application as AichatApp

        try {
            if (savedInstanceState == null) handle(app, intent)
        } catch (e: Exception) {
            app.logDebug("❌ share failed: ${e.message ?: e}")
            app.showToast("❌ تعذّرت الإضافة إلى علبة المواد")
        } finally {
            finish()
        }
    }

    private fun handle(app: AichatApp, intent: Intent?) {
        if (intent == null) return

        val raw: String
        val subject: String?
        val method: String

        when (intent.action) {
            Intent.ACTION_SEND -> {
                raw = (intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
                    ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text)
                    ?.toString().orEmpty()
                subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
                method = SharedMaterialParser.METHOD_SHARE
            }

            actionProcessText -> {
                raw = intent.getCharSequenceExtra(extraProcessText)?.toString().orEmpty()
                subject = null
                method = SharedMaterialParser.METHOD_PROCESS_TEXT
            }

            else -> return
        }

        if (raw.isBlank()) {
            app.showToast("⚠️ لا يوجد نص لإضافته")
            return
        }

        val item = SharedMaterialParser.parse(raw, subject, method)
        if (item == null) {
            app.showToast("⚠️ لا يوجد نص لإضافته")
            return
        }

        // onCreate يعمل على الخيط الرئيسي، وهو شرط MaterialStore
        val isNew = app.materialStore.upsert(item, "share-" + System.currentTimeMillis())
        val total = app.materialStore.items.size

        app.logDebug(
            "📥 SHARE method=${item.method} chars=${item.text.length} " +
                "links=${item.links.size} new=$isNew quality=${item.quality} total=$total"
        )

        val base = if (isNew) "📥 أُضيفت إلى علبة المواد (الإجمالي $total)"
        else "🔄 حُدّثت مادة موجودة (الإجمالي $total)"

        app.showToast(
            if (item.reason == "link_only") "$base\nرابط فقط، لم يُجلب محتوى الصفحة"
            else base
        )
    }
}
