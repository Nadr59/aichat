package com.example.aichat.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.aichat.repository.MaterialItem
import com.example.aichat.repository.MaterialStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * حوار علبة المواد: قائمة المواد الملتقطة (الأحدث أولًا).
 * الضغط على مادة يوسّعها (النص والروابط)، وفيها زرا نسخ وحذف.
 * في الأسفل: «نسخ الكل» و«مسح الكل» (بتأكيد).
 */
@Composable
fun MaterialsDialog(
    store: MaterialStore,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val list = store.items
    var expandedId by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

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
                    text = "علبة المواد (${list.size})",
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
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(list, key = { it.id }) { m ->
                        MaterialRow(
                            m = m,
                            expanded = expandedId == m.id,
                            onToggle = {
                                expandedId = if (expandedId == m.id) null else m.id
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
        },
        confirmButton = {
            Row {
                TextButton(
                    onClick = {
                        // نأخذ لقطة من القائمة لحظة الضغط
                        val snapshot = list.toList()
                        copyToClipboard(
                            context,
                            snapshot.joinToString("\n\n════════\n\n") { materialToText(it) },
                            "تم نسخ ${snapshot.size} مادة"
                        )
                    },
                    enabled = list.isNotEmpty()
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("نسخ الكل")
                }

                TextButton(
                    onClick = { confirmClear = true },
                    enabled = list.isNotEmpty()
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
    onCopy: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        tonalElevation = 2.dp,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .clickable(onClick = onToggle)
                .padding(10.dp)
        ) {
            Text(
                text = m.title.ifBlank { m.url },
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

            if (expanded) {
                Spacer(Modifier.height(8.dp))

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

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onCopy) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("نسخ")
                    }

                    TextButton(onClick = onDelete) {
                        Icon(Icons.Filled.DeleteOutline, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("حذف")
                    }
                }
            }
        }
    }
}

/** نص المادة للنسخ: العنوان، الرابط، النص، ثم الروابط (كل رابط في سطر مستقل بينها سطر فارغ). */
private fun materialToText(m: MaterialItem): String = buildString {
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
