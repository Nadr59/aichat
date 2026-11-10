package com.example.aichat.ui.screens

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.aichat.repository.MaterialItem
import com.example.aichat.repository.MaterialStatus
import com.example.aichat.repository.MaterialStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * حوار علبة المواد: قائمة المواد الملتقطة (الأحدث أولًا) مع بحث محلي.
 * كل مادة بطاقة: خلاصة (إن وُجدت) أو معاينة + أزرار
 * (إلى مربع الإدخال، لخّص، النص الكامل، فتح الرابط، مشاركة، نسخ، حذف).
 * في الأسفل: «نسخ الكل» (الظاهر فقط) و«مسح الكل» (بتأكيد، ومعطّل أثناء البحث).
 *
 * onInsertToInput: يستلم نص المادة (عنوان + رابط + نص) ليضعه المستدعي في مربع الإدخال.
 * إن كان null يختفي الزر.
 */
@Composable
fun MaterialsDialog(
    store: MaterialStore,
    onDismiss: () -> Unit,
    onInsertToInput: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    val list = store.items
    var expandedId by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    val searching = query.isNotBlank()
    val shown: List<MaterialItem> = if (!searching) {
        list.toList()
    } else {
        val terms = normalizeForSearch(query).split(' ').filter { it.isNotEmpty() }
        list.filter { m ->
            val hay = normalizeForSearch(searchableText(m))
            terms.all { hay.contains(it) }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Inbox, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (searching) "علبة المواد (${shown.size} من ${list.size})"
                    else "علبة المواد (${list.size})",
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "إغلاق")
                }
            }
        },
        text = {
            if (list.isEmpty()) {
                Text(
                    "العلبة فارغة. افتح صفحة داخل التطبيق واضغط زر «التقط».",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Column(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("ابحث في العلبة") },
                        leadingIcon = {
                            Icon(Icons.Filled.Search, contentDescription = null)
                        },
                        trailingIcon = {
                            if (searching) {
                                IconButton(onClick = { query = "" }) {
                                    Icon(Icons.Filled.Clear, contentDescription = "مسح البحث")
                                }
                            }
                        }
                    )

                    Spacer(Modifier.height(8.dp))

                    if (shown.isEmpty()) {
                        Text(
                            "لا نتائج لـ «${query.trim()}»",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 380.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(shown, key = { it.id }) { m ->
                                MaterialRow(
                                    m = m,
                                    expanded = expandedId == m.id,
                                    onToggle = {
                                        expandedId = if (expandedId == m.id) null else m.id
                                    },
                                    onInsert = if (onInsertToInput == null) null else {
                                        {
                                            onInsertToInput(materialToInputText(m))
                                            Toast.makeText(
                                                context,
                                                "أُضيفت المادة إلى مربع الإدخال",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                            onDismiss()
                                        }
                                    },
                                    onDigest = {
                                        store.update(m.id) { it.copy(status = MaterialStatus.PENDING) }
                                    },
                                    onOpen = { openExternally(context, m.url) },
                                    onShare = {
                                        shareText(
                                            context,
                                            materialToText(m),
                                            m.title.ifBlank { m.url }
                                        )
                                    },
                                    onCopy = {
                                        copyToClipboard(
                                            context,
                                            materialToText(m),
                                            "تم نسخ المادة"
                                        )
                                    },
                                    onDelete = {
                                        if (expandedId == m.id) expandedId = null
                                        store.remove(m.id)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(
                    onClick = {
                        // نأخذ لقطة من المواد الظاهرة لحظة الضغط
                        val snapshot = shown.toList()
                        copyToClipboard(
                            context,
                            snapshot.joinToString("\n\n════════\n\n") { materialToText(it) },
                            "تم نسخ ${snapshot.size} مادة"
                        )
                    },
                    enabled = shown.isNotEmpty()
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("نسخ الكل")
                }

                TextButton(
                    onClick = { confirmClear = true },
                    enabled = list.isNotEmpty() && !searching
                ) {
                    Icon(Icons.Filled.DeleteOutline, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("مسح الكل")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("إغلاق") }
        }
    )

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("مسح كل المواد؟") },
            text = { Text("سيُحذف كل ما في العلبة (${list.size} مادة) ولا يمكن التراجع.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        store.clear()
                        expandedId = null
                        confirmClear = false
                    }
                ) { Text("مسح") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("إلغاء") }
            }
        )
    }
}

@Composable
private fun MaterialRow(
    m: MaterialItem,
    expanded: Boolean,
    onToggle: () -> Unit,
    onInsert: (() -> Unit)?,
    onDigest: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit
) {
    val canOpen = m.url.startsWith("http://") || m.url.startsWith("https://")
    val digest = m.digest.takeIf { m.status == MaterialStatus.READY }
    val shownTitle = digest?.titleAr?.takeIf { it.isNotBlank() } ?: m.title.ifBlank { m.url }
    val canDigest = m.quality != "weak" &&
        (m.status == MaterialStatus.NONE || m.status == MaterialStatus.FAILED)

    val statusLine: String? = when (m.status) {
        MaterialStatus.PENDING -> "⏳ بانتظار التلخيص"
        MaterialStatus.PROCESSING -> "⚙️ جارٍ التلخيص…"
        MaterialStatus.FAILED -> "❌ فشل التلخيص (التفاصيل في سجلات التشخيص)"
        else -> null
    }

    Surface(
        tonalElevation = 2.dp,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp)) {

            // رأس البطاقة: الضغط عليه يوسّع/يطوي النص الكامل
            Column(modifier = Modifier.clickable(onClick = onToggle)) {
                Text(
                    text = shownTitle,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = if (expanded) 4 else 2,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(Modifier.height(2.dp))

                Text(
                    text = metaLine(m),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (statusLine != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = statusLine,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (m.status == MaterialStatus.FAILED)
                            MaterialTheme.colorScheme.error
                        else
                            MaterialTheme.colorScheme.primary
                    )
                }

                if (digest != null) {
                    Spacer(Modifier.height(6.dp))
                    for (b in digest.bullets) {
                        Text(
                            text = "• $b",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(2.dp))
                    }
                    if (digest.keyQuote.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "« ${digest.keyQuote} »",
                            style = MaterialTheme.typography.bodySmall,
                            fontStyle = FontStyle.Italic,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (digest.worthFullRead) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "⭐ تستحق القراءة كاملة",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                } else if (!expanded && m.text.isNotBlank()) {
                    // معاينة للمواد التي لم تُلخَّص بعد
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = m.text.trim(),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (expanded) {
                Spacer(Modifier.height(8.dp))

                if (digest != null && m.title.isNotBlank()) {
                    Text(
                        text = "العنوان الأصلي: ${m.title}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                }

                if (m.quality == "weak") {
                    Text(
                        text = "⚠️ جودة ضعيفة" +
                            (if (m.reason.isNotBlank()) " (${m.reason})" else ""),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(6.dp))
                }

                // نص واحد بفواصل أسطر صريحة: لا يلتصق الرابط بما بعده عند النسخ
                SelectionContainer {
                    Text(
                        text = if (m.url.isNotBlank()) m.url + "\n\n" + m.text else m.text,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                if (m.links.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "الروابط (${m.links.size})",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    SelectionContainer {
                        Text(
                            text = m.links.joinToString("\n\n") {
                                "• ${it.text.replace('\n', ' ')}\n${it.href}"
                            },
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }

            if (onInsert != null) {
                TextButton(
                    onClick = onInsert,
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    Icon(
                        Icons.Filled.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "إلى مربع الإدخال",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }

            if (canDigest) {
                TextButton(
                    onClick = onDigest,
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    Icon(
                        Icons.Filled.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = if (m.status == MaterialStatus.FAILED) "أعد التلخيص" else "لخّص",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            // صف الأزرار الثابت
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onToggle,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    Text(
                        text = if (expanded) "إخفاء النص" else "النص الكامل",
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1
                    )
                }

                IconButton(
                    onClick = onOpen,
                    enabled = canOpen,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(Icons.Filled.OpenInBrowser, contentDescription = "فتح الرابط في المتصفح")
                }

                IconButton(onClick = onShare, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Share, contentDescription = "مشاركة")
                }

                IconButton(onClick = onCopy, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = "نسخ")
                }

                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.DeleteOutline, contentDescription = "حذف")
                }
            }
        }
    }
}

/** كل ما يُبحث فيه عن المادة (الخلاصة إن وُجدت، ثم الأصل). */
private fun searchableText(m: MaterialItem): String = buildString {
    append(m.title).append(' ')
    m.digest?.let { d ->
        append(d.titleAr).append(' ')
        for (b in d.bullets) append(b).append(' ')
        append(d.keyQuote).append(' ')
    }
    append(m.host).append(' ')
    append(m.url).append(' ')
    append(m.text)
}

/**
 * تطبيع خفيف للبحث العربي: يحذف التشكيل والتطويل، ويوحّد الألف والياء والتاء المربوطة،
 * ويحوّل الأحرف اللاتينية إلى صغيرة.
 */
private fun normalizeForSearch(s: String): String {
    val sb = StringBuilder(s.length)
    for (ch in s) {
        when (ch) {
            in '\u064B'..'\u065F', '\u0670', '\u0640' -> {}
            'أ', 'إ', 'آ', 'ٱ' -> sb.append('ا')
            'ى' -> sb.append('ي')
            'ة' -> sb.append('ه')
            else -> sb.append(ch.lowercaseChar())
        }
    }
    return sb.toString().trim()
}

/** ما يوضع في مربع الإدخال: العنوان الأصلي، الرابط، ثم النص (بلا قائمة الروابط). */
private fun materialToInputText(m: MaterialItem): String = buildString {
    if (m.title.isNotBlank()) append(m.title).append('\n')
    if (m.url.isNotBlank()) append(m.url).append('\n')
    if (isNotEmpty()) append('\n')
    append(m.text.trim())
}

/** نص المادة للنسخ والمشاركة: الخلاصة (إن وُجدت)، العنوان، الرابط، النص، ثم الروابط. */
private fun materialToText(m: MaterialItem): String = buildString {
    val d = m.digest.takeIf { m.status == MaterialStatus.READY }
    if (d != null) {
        if (d.titleAr.isNotBlank()) append(d.titleAr).append('\n')
        for (b in d.bullets) append("• ").append(b).append('\n')
        if (d.keyQuote.isNotBlank()) append("« ").append(d.keyQuote).append(" »\n")
        append("\n────────\n\n")
    }

    if (m.title.isNotBlank()) append(m.title).append('\n')
    if (m.url.isNotBlank()) append(m.url).append('\n')
    if (isNotEmpty()) append('\n')
    append(m.text)

    if (m.links.isNotEmpty()) {
        append("\n\nالروابط (").append(m.links.size).append("):\n\n")
        append(
            m.links.joinToString("\n\n") {
                "• ${it.text.replace('\n', ' ')}\n${it.href}"
            }
        )
    }
}

private fun copyToClipboard(context: Context, text: String, okMessage: String) {
    val ok = runCatching {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("AiChat Materials", text))
    }.isSuccess

    Toast.makeText(
        context,
        if (ok) okMessage else "تعذّر النسخ (قد يكون الحجم كبيرًا)",
        Toast.LENGTH_SHORT
    ).show()
}

/** فتح الرابط في متصفح خارجي. */
private fun openExternally(context: Context, url: String) {
    val ok = runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }.isSuccess

    if (!ok) {
        Toast.makeText(context, "تعذّر فتح الرابط (لا يوجد متصفح؟)", Toast.LENGTH_SHORT).show()
    }
}

/** مشاركة نص المادة إلى أي تطبيق (مع إخفاء علبة المواد نفسها من القائمة). */
private fun shareText(context: Context, text: String, subject: String) {
    val ok = runCatching {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_SUBJECT, subject)
        }
        val chooser = Intent.createChooser(send, "مشاركة المادة")
        if (Build.VERSION.SDK_INT >= 24) {
            chooser.putExtra(
                Intent.EXTRA_EXCLUDE_COMPONENTS,
                arrayOf(ComponentName(context.packageName, "com.example.aichat.ShareReceiverActivity"))
            )
        }
        if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }.isSuccess

    if (!ok) {
        Toast.makeText(context, "تعذّرت المشاركة (قد يكون النص كبيرًا)", Toast.LENGTH_SHORT).show()
    }
}

private fun metaLine(m: MaterialItem): String {
    val parts = ArrayList<String>()
    if (m.host.isNotBlank()) parts.add(m.host)

    parts.add(
        if (m.date.isNotBlank()) m.date.take(10)
        else SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(m.capturedAt))
    )

    parts.add("${m.text.length} حرف")
    parts.add("${m.links.size} رابط")
    if (m.method.isNotBlank()) parts.add(m.method)
    if (m.truncated) parts.add("مقتطعة")

    return parts.joinToString(" • ")
}
