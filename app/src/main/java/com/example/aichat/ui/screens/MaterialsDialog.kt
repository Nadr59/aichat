
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
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
import com.example.aichat.data.model.AgentProfile
import com.example.aichat.repository.BOX_NAME_MAX
import com.example.aichat.repository.BOX_QUESTION_MAX
import com.example.aichat.repository.BoxStore
import com.example.aichat.repository.MAX_BOXES
import com.example.aichat.repository.MaterialBox
import com.example.aichat.repository.MaterialItem
import com.example.aichat.repository.MaterialStatus
import com.example.aichat.repository.MaterialStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** حدّ التحذير لطول مدخل الوكيل على العلبة (بالأحرف). تحذير فقط، لا منع. */
private const val BOX_AGENT_SOFT_LIMIT = 12000

/**
 * حوار علبة المواد: قائمة المواد الملتقطة (الأحدث أولًا) مع بحث محلي،
 * وعلب صغيرة (boxStore) تجمع مواد مختارة تحت اسم وسؤال اختياري.
 *
 * كل مادة بطاقة: خلاصة (إن وُجدت) أو معاينة + أزرار
 * (إلى مربع الإدخال، وكيل، لخّص، النص الكامل، فتح الرابط، مشاركة، نسخ، حذف).
 * في الأسفل: «نسخ الكل» (الظاهر فقط) و«مسح الكل» (بتأكيد، ومعطّل أثناء البحث وداخل العلب).
 *
 * onInsertToInput: يستلم نصًا ليضعه المستدعي في مربع الإدخال (مادة، أو ملخص علبة).
 * إن كان null يختفي الزر.
 *
 * agents + onRunAgent: زر «وكيل» يفتح قائمة بأسماء agents؛ عند الاختيار يُستدعى
 * onRunAgent(النص، الوكيل). المستدعي هو من يقرر إغلاق الحوار.
 * يُستعمل للمادة الواحدة (نصها) وللعلبة (خلاصات موادها المُلخَّصة).
 *
 * boxStore: إن كان null تختفي واجهة العلب كلها.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MaterialsDialog(
    store: MaterialStore,
    onDismiss: () -> Unit,
    onInsertToInput: ((String) -> Unit)? = null,
    agents: List<AgentProfile> = emptyList(),
    onRunAgent: ((String, AgentProfile) -> Unit)? = null,
    boxStore: BoxStore? = null
) {
    val context = LocalContext.current
    val list = store.items
    val boxList: List<MaterialBox> = boxStore?.boxes ?: emptyList()

    var expandedId by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    // العلب
    var selectedBoxId by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showCreateName by remember { mutableStateOf(false) }
    var editingBoxId by remember { mutableStateOf<String?>(null) }
    var confirmDeleteBoxId by remember { mutableStateOf<String?>(null) }

    // وكيل العلبة: قائمة الاختيار، ثم شاشة المعاينة
    var boxAgentMenu by remember { mutableStateOf(false) }
    var boxAgentFor by remember { mutableStateOf<AgentProfile?>(null) }

    // المواد الحية فقط: معرّفات العلب الميتة تُرشَح هنا عند كل عرض
    val aliveIds = list.map { it.id }.toHashSet()
    val currentBox: MaterialBox? =
        if (selectedBoxId == null) null else boxList.firstOrNull { it.id == selectedBoxId }

    val base: List<MaterialItem> = if (currentBox == null) {
        list.toList()
    } else {
        val ids = currentBox.materialIds.toHashSet()
        list.filter { it.id in ids }
    }

    val searching = query.isNotBlank()
    val shown: List<MaterialItem> = if (!searching) {
        base
    } else {
        val terms = normalizeForSearch(query).split(' ').filter { it.isNotEmpty() }
        base.filter { m ->
            val hay = normalizeForSearch(searchableText(m))
            terms.all { hay.contains(it) }
        }
    }

    val titleText = when {
        creating -> "علبة جديدة"
        currentBox != null ->
            if (searching) "${currentBox.name} (${shown.size} من ${base.size})"
            else "${currentBox.name} (${base.size})"
        searching -> "علبة المواد (${shown.size} من ${list.size})"
        else -> "علبة المواد (${list.size})"
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
                    text = titleText,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
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

                    // شرائح العلب
                    if (boxStore != null && !creating) {
                        Spacer(Modifier.height(8.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            item {
                                FilterChip(
                                    selected = currentBox == null,
                                    onClick = {
                                        selectedBoxId = null
                                        expandedId = null
                                    },
                                    label = { Text("الكل (${list.size})") }
                                )
                            }
                            items(boxList, key = { it.id }) { b ->
                                val cnt = b.materialIds.count { it in aliveIds }
                                FilterChip(
                                    selected = currentBox?.id == b.id,
                                    onClick = {
                                        selectedBoxId = b.id
                                        expandedId = null
                                    },
                                    label = {
                                        Text(
                                            text = "${b.name} ($cnt)",
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                )
                            }
                            item {
                                AssistChip(
                                    onClick = {
                                        if (boxList.size >= MAX_BOXES) {
                                            Toast.makeText(
                                                context,
                                                "الحد الأقصى $MAX_BOXES علبة",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        } else {
                                            creating = true
                                            selection = emptySet()
                                            selectedBoxId = null
                                            expandedId = null
                                            query = ""
                                        }
                                    },
                                    label = { Text("＋ علبة جديدة") }
                                )
                            }
                        }
                    }

                    // شريط وضع الإنشاء
                    if (creating) {
                        Spacer(Modifier.height(8.dp))
                        Surface(
                            tonalElevation = 3.dp,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "اختر المواد (${selection.size})",
                                        style = MaterialTheme.typography.labelLarge,
                                        modifier = Modifier.weight(1f)
                                    )
                                    val allShownSelected =
                                        shown.isNotEmpty() && shown.all { it.id in selection }
                                    TextButton(
                                        onClick = {
                                            selection = if (allShownSelected) {
                                                selection - shown.map { it.id }.toSet()
                                            } else {
                                                selection + shown.map { it.id }
                                            }
                                        },
                                        enabled = shown.isNotEmpty()
                                    ) {
                                        Text(
                                            if (allShownSelected) "إلغاء تحديد الظاهر" else "تحديد الظاهر",
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                    }
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = { showCreateName = true },
                                        enabled = selection.isNotEmpty()
                                    ) { Text("إضافة") }
                                    TextButton(
                                        onClick = {
                                            creating = false
                                            selection = emptySet()
                                        }
                                    ) { Text("إلغاء") }
                                }
                            }
                        }
                    }

                    // رأس العلبة المختارة
                    if (currentBox != null && !creating) {
                        // base هنا = كل مواد العلبة الحية (غير مصفّاة بالبحث)
                        val readyCount = base.count { isReady(it) }
                        val notReady = base.size - readyCount
                        val digestable = base.filter { canDigestItem(it) }
                        val inQueue = base.count {
                            it.status == MaterialStatus.PENDING ||
                                it.status == MaterialStatus.PROCESSING
                        }

                        Spacer(Modifier.height(8.dp))
                        Surface(
                            tonalElevation = 1.dp,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = currentBox.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    IconButton(
                                        onClick = { editingBoxId = currentBox.id },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(Icons.Filled.Edit, contentDescription = "تعديل اسم العلبة وسؤالها")
                                    }
                                    IconButton(
                                        onClick = { confirmDeleteBoxId = currentBox.id },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(Icons.Filled.DeleteOutline, contentDescription = "حذف العلبة")
                                    }
                                }
                                if (currentBox.question.isNotBlank()) {
                                    Text(
                                        text = "السؤال: ${currentBox.question}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                // إشعار المواد غير المُلخَّصة
                                if (base.isNotEmpty() && notReady > 0) {
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        text = if (readyCount == 0) {
                                            "لا توجد مادة مُلخَّصة في هذه العلبة. " +
                                                "زر «وكيل» معطّل حتى تُلخَّص مادة واحدة على الأقل."
                                        } else {
                                            "$notReady من ${base.size} مادة غير مُلخَّصة " +
                                                "وستُستبعد من الوكيل."
                                        } + if (inQueue > 0) " ($inQueue في الطابور)" else "",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                    if (digestable.isNotEmpty()) {
                                        TextButton(
                                            onClick = {
                                                for (d in digestable) {
                                                    store.update(d.id) {
                                                        it.copy(status = MaterialStatus.PENDING)
                                                    }
                                                }
                                                Toast.makeText(
                                                    context,
                                                    "أُضيفت ${digestable.size} مادة إلى طابور التلخيص",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            },
                                            contentPadding = PaddingValues(horizontal = 4.dp)
                                        ) {
                                            Icon(
                                                Icons.Filled.AutoAwesome,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(Modifier.width(4.dp))
                                            Text(
                                                text = "لخّصها الآن (${digestable.size})",
                                                style = MaterialTheme.typography.labelMedium
                                            )
                                        }
                                    }
                                }

                                // أزرار العلبة كلها
                                Spacer(Modifier.height(4.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (onRunAgent != null) {
                                        Box {
                                            TextButton(
                                                onClick = { boxAgentMenu = true },
                                                enabled = readyCount > 0,
                                                contentPadding = PaddingValues(horizontal = 4.dp)
                                            ) {
                                                Icon(
                                                    Icons.Filled.Person,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                                Spacer(Modifier.width(4.dp))
                                                Text(
                                                    text = "وكيل",
                                                    style = MaterialTheme.typography.labelMedium
                                                )
                                            }
                                            DropdownMenu(
                                                expanded = boxAgentMenu,
                                                onDismissRequest = { boxAgentMenu = false }
                                            ) {
                                                if (agents.isEmpty()) {
                                                    DropdownMenuItem(
                                                        text = { Text("لا يوجد وكلاء. أضف وكيلًا من شاشة الوكلاء") },
                                                        onClick = { boxAgentMenu = false },
                                                        enabled = false
                                                    )
                                                } else {
                                                    agents.forEach { a ->
                                                        DropdownMenuItem(
                                                            text = {
                                                                Text(
                                                                    text = a.name,
                                                                    maxLines = 1,
                                                                    overflow = TextOverflow.Ellipsis
                                                                )
                                                            },
                                                            onClick = {
                                                                boxAgentMenu = false
                                                                boxAgentFor = a
                                                            }
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    if (onInsertToInput != null) {
                                        TextButton(
                                            onClick = {
                                                onInsertToInput(
                                                    boxToInputText(
                                                        currentBox.name,
                                                        currentBox.question,
                                                        base
                                                    )
                                                )
                                                Toast.makeText(
                                                    context,
                                                    "أُضيفت العلبة إلى مربع الإدخال",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                                onDismiss()
                                            },
                                            enabled = base.isNotEmpty(),
                                            contentPadding = PaddingValues(horizontal = 4.dp)
                                        ) {
                                            Text(
                                                text = "إلى الإدخال",
                                                style = MaterialTheme.typography.labelMedium
                                            )
                                        }
                                    }

                                    Spacer(Modifier.weight(1f))

                                    IconButton(
                                        onClick = {
                                            copyToClipboard(
                                                context,
                                                boxToText(currentBox.name, currentBox.question, base),
                                                "تم نسخ العلبة (${base.size} مادة)"
                                            )
                                        },
                                        enabled = base.isNotEmpty(),
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(Icons.Filled.ContentCopy, contentDescription = "نسخ العلبة")
                                    }
                                    IconButton(
                                        onClick = {
                                            shareText(
                                                context,
                                                boxToText(currentBox.name, currentBox.question, base),
                                                currentBox.name
                                            )
                                        },
                                        enabled = base.isNotEmpty(),
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(Icons.Filled.Share, contentDescription = "مشاركة العلبة")
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    if (shown.isEmpty()) {
                        Text(
                            text = when {
                                searching -> "لا نتائج لـ «${query.trim()}»"
                                currentBox != null -> "العلبة فارغة (حُذفت موادها من العلبة الكبيرة)."
                                else -> "لا مواد."
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = if (currentBox != null && !creating) 260.dp else 380.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(shown, key = { it.id }) { m ->
                                MaterialRow(
                                    m = m,
                                    expanded = expandedId == m.id,
                                    agents = agents,
                                    selectionMode = creating,
                                    checked = m.id in selection,
                                    onCheckedChange = { c ->
                                        selection = if (c) selection + m.id else selection - m.id
                                    },
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
                                    onRunAgent = if (onRunAgent == null) null else {
                                        { agent -> onRunAgent(materialToInputText(m), agent) }
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
                                        selection = selection - m.id
                                        boxStore?.pruneDead(store.items.map { it.id }.toHashSet())
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
                    enabled = shown.isNotEmpty() && !creating
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("نسخ الكل")
                }

                TextButton(
                    onClick = { confirmClear = true },
                    enabled = list.isNotEmpty() && !searching && currentBox == null && !creating
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
                        boxStore?.pruneDead(emptySet())
                        expandedId = null
                        selection = emptySet()
                        confirmClear = false
                    }
                ) { Text("مسح") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("إلغاء") }
            }
        )
    }

    // إنشاء علبة: الاسم والسؤال
    if (showCreateName && boxStore != null) {
        BoxNameDialog(
            title = "علبة جديدة (${selection.size} مادة)",
            initialName = "",
            initialQuestion = "",
            confirmLabel = "إضافة",
            onConfirm = { n, q ->
                // نحفظ ترتيب العلبة الكبيرة لا ترتيب الضغط
                val ids = list.filter { it.id in selection }.map { it.id }
                val created = boxStore.add(n, q, ids)
                if (created == null) {
                    Toast.makeText(context, "تعذّر إنشاء العلبة", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "أُنشئت العلبة «${created.name}»", Toast.LENGTH_SHORT).show()
                    selectedBoxId = created.id
                }
                creating = false
                selection = emptySet()
                showCreateName = false
                query = ""
            },
            onDismiss = { showCreateName = false }
        )
    }

    // تعديل علبة: الاسم والسؤال
    val editingBox = editingBoxId?.let { id -> boxList.firstOrNull { it.id == id } }
    if (editingBox != null && boxStore != null) {
        BoxNameDialog(
            title = "تعديل العلبة",
            initialName = editingBox.name,
            initialQuestion = editingBox.question,
            confirmLabel = "حفظ",
            onConfirm = { n, q ->
                boxStore.update(editingBox.id, n, q)
                editingBoxId = null
            },
            onDismiss = { editingBoxId = null }
        )
    }

    // حذف علبة (العلبة فقط، لا المواد)
    val deletingBox = confirmDeleteBoxId?.let { id -> boxList.firstOrNull { it.id == id } }
    if (deletingBox != null && boxStore != null) {
        AlertDialog(
            onDismissRequest = { confirmDeleteBoxId = null },
            title = { Text("حذف العلبة «${deletingBox.name}»؟") },
            text = { Text("تُحذف العلبة فقط. المواد نفسها تبقى في العلبة الكبيرة.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        boxStore.remove(deletingBox.id)
                        if (selectedBoxId == deletingBox.id) selectedBoxId = null
                        confirmDeleteBoxId = null
                    }
                ) { Text("حذف") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteBoxId = null }) { Text("إلغاء") }
            }
        )
    }

    // معاينة تشغيل وكيل على العلبة
    val previewAgent = boxAgentFor
    if (previewAgent != null && currentBox != null && onRunAgent != null) {
        BoxAgentPreviewDialog(
            box = currentBox,
            agent = previewAgent,
            items = base,
            onRun = { text ->
                boxAgentFor = null
                onRunAgent(text, previewAgent)
            },
            onDismiss = { boxAgentFor = null }
        )
    }
}

/** معاينة قبل تشغيل وكيل على العلبة: ما سيدخل وما سيُستبعد، والحجم، والسؤال لهذه المرة. */
@Composable
private fun BoxAgentPreviewDialog(
    box: MaterialBox,
    agent: AgentProfile,
    items: List<MaterialItem>,
    onRun: (String) -> Unit,
    onDismiss: () -> Unit
) {
    // تعديل السؤال هنا لهذه المرة فقط ولا يغيّر المحفوظ
    var question by remember { mutableStateOf(box.question) }

    val ready = items.filter { isReady(it) }
    val skipped = items.size - ready.size
    val text = boxToAgentInput(box.name, question, ready)
    val tooLong = text.length > BOX_AGENT_SOFT_LIMIT

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("تشغيل «${agent.name}» على العلبة") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "العلبة: ${box.name}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "المواد الداخلة (بخلاصاتها): ${ready.size}",
                    style = MaterialTheme.typography.bodySmall
                )
                if (skipped > 0) {
                    Text(
                        text = "المستبعدة لأنها غير مُلخَّصة: $skipped",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Text(
                    text = "حجم المدخل التقريبي: ${text.length} حرف",
                    style = MaterialTheme.typography.bodySmall
                )
                if (tooLong) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "⚠️ المدخل أطول من $BOX_AGENT_SOFT_LIMIT حرف وقد يتجاوز ما يتحمله النموذج. " +
                            "يمكنك تقليل مواد العلبة.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = question,
                    onValueChange = { question = it.take(BOX_QUESTION_MAX) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4,
                    label = { Text("السؤال لهذه المرة") },
                    placeholder = { Text("إن تُرك فارغًا: لخّص وقارن بين هذه المواد") }
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "التعديل هنا لهذه المرة فقط ولا يغيّر سؤال العلبة المحفوظ. " +
                        "سيظهر المدخل كاملًا في الدردشة كرسالة منك.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onRun(text) },
                enabled = ready.isNotEmpty()
            ) { Text("تشغيل") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("إلغاء") }
        }
    )
}

/** حوار اسم العلبة وسؤالها الاختياري (للإنشاء والتعديل). */
@Composable
private fun BoxNameDialog(
    title: String,
    initialName: String,
    initialQuestion: String,
    confirmLabel: String,
    onConfirm: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var question by remember { mutableStateOf(initialQuestion) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(BOX_NAME_MAX) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("اسم العلبة") }
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = question,
                    onValueChange = { question = it.take(BOX_QUESTION_MAX) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4,
                    label = { Text("سؤال العلبة (اختياري)") }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name, question) },
                enabled = name.isNotBlank()
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("إلغاء") }
        }
    )
}

@Composable
private fun MaterialRow(
    m: MaterialItem,
    expanded: Boolean,
    agents: List<AgentProfile>,
    selectionMode: Boolean,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onToggle: () -> Unit,
    onInsert: (() -> Unit)?,
    onRunAgent: ((AgentProfile) -> Unit)?,
    onDigest: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit
) {
    var agentMenu by remember { mutableStateOf(false) }

    val canOpen = m.url.startsWith("http://") || m.url.startsWith("https://")
    val digest = m.digest.takeIf { m.status == MaterialStatus.READY }
    val shownTitle = digest?.titleAr?.takeIf { it.isNotBlank() } ?: m.title.ifBlank { m.url }
    val canDigest = canDigestItem(m)

    val statusLine: String? = when (m.status) {
        MaterialStatus.PENDING -> "⏳ بانتظار التلخيص"
        MaterialStatus.PROCESSING -> "⚙️ جارٍ التلخيص…"
        MaterialStatus.FAILED -> "❌ فشل التلخيص (التفاصيل في سجلات التشخيص)"
        else -> null
    }

    // في وضع الإنشاء: الضغط على الرأس يبدّل التحديد بدل التوسيع
    val headerClick: () -> Unit =
        if (selectionMode) { { onCheckedChange(!checked) } } else onToggle

    Surface(
        tonalElevation = 2.dp,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp)) {

            Row(verticalAlignment = Alignment.Top) {
                if (selectionMode) {
                    Checkbox(checked = checked, onCheckedChange = onCheckedChange)
                    Spacer(Modifier.width(4.dp))
                }

                // رأس البطاقة: الضغط عليه يوسّع/يطوي النص الكامل
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(onClick = headerClick)
                ) {
                    Text(
                        text = shownTitle,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = if (expanded && !selectionMode) 4 else 2,
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
                    } else if ((!expanded || selectionMode) && m.text.isNotBlank()) {
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
            }

            if (!selectionMode) {

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

                if (onRunAgent != null) {
                    Box {
                        TextButton(
                            onClick = { agentMenu = true },
                            contentPadding = PaddingValues(horizontal = 4.dp)
                        ) {
                            Icon(
                                Icons.Filled.Person,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "وكيل",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }

                        DropdownMenu(
                            expanded = agentMenu,
                            onDismissRequest = { agentMenu = false }
                        ) {
                            if (agents.isEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("لا يوجد وكلاء. أضف وكيلًا من شاشة الوكلاء") },
                                    onClick = { agentMenu = false },
                                    enabled = false
                                )
                            } else {
                                agents.forEach { a ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = a.name,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        },
                                        onClick = {
                                            agentMenu = false
                                            onRunAgent(a)
                                        }
                                    )
                                }
                            }
                        }
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
}

/** مادة مُلخَّصة وجاهزة (حالتها READY ولها خلاصة). */
private fun isReady(m: MaterialItem): Boolean =
    m.status == MaterialStatus.READY && m.digest != null

/** هل يصحّ وضع المادة في طابور التلخيص (غير ضعيفة، ولم تُلخَّص أو فشلت). */
private fun canDigestItem(m: MaterialItem): Boolean =
    m.quality != "weak" &&
        (m.status == MaterialStatus.NONE || m.status == MaterialStatus.FAILED)

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

/** ما يوضع في مربع الإدخال (أو يُرسل إلى وكيل): العنوان الأصلي، الرابط، ثم النص (بلا قائمة الروابط). */
private fun materialToInputText(m: MaterialItem): String = buildString {
    if (m.title.isNotBlank()) append(m.title).append('\n')
    if (m.url.isNotBlank()) append(m.url).append('\n')
    if (isNotEmpty()) append('\n')
    append(m.text.trim())
}

/**
 * مدخل وكيل العلبة: السؤال (أو تعليمة عامة)، ثم خلاصات المواد المُلخَّصة بأرقام [م1]…،
 * ثم قسم «المصادر» بالروابط الحقيقية من رابط كل مادة (الخلاصة نفسها لا تحمل روابط).
 */
private fun boxToAgentInput(
    boxName: String,
    question: String,
    ready: List<MaterialItem>
): String = buildString {
    val q = question.trim().ifBlank { "لخّص وقارن بين هذه المواد." }
    append(q).append("\n\n")
    append("العلبة: ").append(boxName).append('\n')
    append("فيما يلي خلاصات ").append(ready.size).append(" مادة. ")
    append("اعتمد عليها وحدها، واذكر رقم المادة بين قوسين مثل [م1] عند كل معلومة تأخذها منها. ")
    append("إن لم تجد فيها ما يجيب فقل ذلك صراحة ولا تخمّن.\n\n")

    ready.forEachIndexed { i, m ->
        val d = m.digest ?: return@forEachIndexed
        append("[م").append(i + 1).append("] ")
        append(d.titleAr.ifBlank { m.title.ifBlank { m.url } }).append('\n')
        for (b in d.bullets) append("• ").append(b).append('\n')
        if (d.keyQuote.isNotBlank()) append("« ").append(d.keyQuote).append(" »\n")
        append('\n')
    }

    append("المصادر:\n")
    ready.forEachIndexed { i, m ->
        append("[م").append(i + 1).append("] ")
        append(if (m.url.isNotBlank()) m.url else "(بلا رابط)").append('\n')
    }
}

/** ما يوضع في مربع الإدخال للعلبة: الاسم والسؤال وقائمة العناوين والروابط فقط (دون الخلاصات). */
private fun boxToInputText(
    boxName: String,
    question: String,
    items: List<MaterialItem>
): String = buildString {
    append("علبة: ").append(boxName).append('\n')
    if (question.isNotBlank()) append("السؤال: ").append(question.trim()).append('\n')
    append("\nالمواد (").append(items.size).append("):\n")
    items.forEachIndexed { i, m ->
        val t = m.title.ifBlank { m.digest?.titleAr.orEmpty() }.ifBlank { m.url }
        append(i + 1).append(". ").append(t).append('\n')
        if (m.url.isNotBlank() && m.url != t) append("   ").append(m.url).append('\n')
    }
}

/** نص العلبة كلها للنسخ والمشاركة: الرأس ثم كل مادة كاملة. */
private fun boxToText(
    boxName: String,
    question: String,
    items: List<MaterialItem>
): String = buildString {
    append("علبة: ").append(boxName).append('\n')
    if (question.isNotBlank()) append("السؤال: ").append(question.trim()).append('\n')
    append("عدد المواد: ").append(items.size)
    append("\n\n════════\n\n")
    append(items.joinToString("\n\n════════\n\n") { materialToText(it) })
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

