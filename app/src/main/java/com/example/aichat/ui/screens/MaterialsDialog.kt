package com.example.aichat.ui.screens

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
 * الضغط على مادة يوسّعها (النص والروابط)، وفيها زر حذف. «مسح الكل» بتأكيد.
 */
@Composable
fun MaterialsDialog(
    store: MaterialStore,
    onDismiss: () -> Unit
) {
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
            TextButton(
                onClick = { confirmClear = true },
                enabled = list.isNotEmpty()
            ) {
                Icon(Icons.Filled.DeleteOutline, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("مسح الكل")
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

                SelectionContainer {
                    Column {
                        if (m.url.isNotBlank()) {
                            Text(
                                text = m.url,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.height(6.dp))
                        }

                        Text(
                            text = m.text,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
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
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (l in m.links) {
                                Text(
                                    text = "• ${l.text}\n${l.href}",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
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
