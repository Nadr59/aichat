package com.example.aichat.ui.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.aichat.repository.BoxStore
import com.example.aichat.repository.MaterialItem
import com.example.aichat.repository.MaterialStore
import com.example.aichat.ui.screens.boxToInputText
import com.example.aichat.ui.screens.isReady
import com.example.aichat.ui.screens.materialToInputText
import kotlinx.coroutines.delay

/**
 * لوحة علبة المواد وأدوات النص داخل لوحة المفاتيح.
 * onInsert: يدرج النص عند المؤشر ويعيد هل نجح. لا يرسل.
 * session: يتغيّر مع كل حقل كتابة جديد فتُمسح نتيجة «أدوات النص» القديمة.
 * host: صلة «أدوات النص» بالحقل والمزوّد.
 * onSwitchKeyboard: يفتح قائمة اختيار لوحة المفاتيح في النظام.
 */
@Composable
fun MaterialsKeyboardPanel(
    store: MaterialStore,
    boxStore: BoxStore,
    blockedReason: String?,
    session: Int,
    host: TextToolsHost,
    onInsert: (String) -> Boolean,
    onSwitchKeyboard: () -> Unit
) {
    var tab by remember { mutableStateOf(0) }
    var tick by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf<Pair<String, Int>?>(null) }

    LaunchedEffect(status) {
        if (status != null) {
            delay(2000)
            status = null
        }
    }

    fun showStatus(message: String) {
        tick++
        status = message to tick
    }

    fun doInsert(text: String, label: String) {
        val ok = onInsert(text)
        showStatus(if (ok) "✓ أُدرج: $label" else "⚠️ تعذّر الإدراج في هذا الحقل")
    }

    // قراءة القوائم هنا تجعل اللوحة تتحدّث تلقائيًا
    val materials = store.items.toList()
    val boxes = boxStore.boxes.toList()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp)
            .background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onSwitchKeyboard) {
                Icon(Icons.Filled.Language, contentDescription = "تبديل لوحة المفاتيح")
            }
            TabRow(selectedTabIndex = tab, modifier = Modifier.weight(1f)) {
                Tab(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    text = { Text("المواد (${materials.size})") }
                )
                Tab(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    text = { Text("العلب الصغيرة (${boxes.size})") }
                )
                Tab(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    text = { Text("أدوات النص") }
                )
            }
        }

        status?.let {
            Text(
                text = it.first,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
            )
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                blockedReason != null -> Message(blockedReason)

                tab == 0 -> {
                    if (materials.isEmpty()) {
                        Message("لا مواد في العلبة. التقط صفحات من داخل AiChat بزر «التقط».")
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(materials, key = { it.id }) { m ->
                                MaterialCard(m) {
                                    doInsert(materialToInputText(m), titleOf(m))
                                }
                            }
                        }
                    }
                }

                tab == 1 -> {
                    if (boxes.isEmpty()) {
                        Message("لا علب صغيرة. أنشئها من علبة المواد داخل AiChat.")
                    } else {
                        val byId = materials.associateBy { it.id }
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(boxes, key = { it.id }) { b ->
                                // المواد الحية فقط، بترتيب العلبة الكبيرة (كما في الحوار)
                                val ids = b.materialIds.toHashSet()
                                val members = materials.filter { it.id in ids && byId.containsKey(it.id) }
                                val ready = members.filter { isReady(it) }
                                val enabled = ready.isNotEmpty()

                                Surface(
                                    tonalElevation = 2.dp,
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(enabled = enabled) {
                                            doInsert(
                                                boxToInputText(b.name, b.question, ready),
                                                b.name
                                            )
                                        }
                                ) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Text(
                                            text = b.name,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = if (enabled) {
                                                "${ready.size} مُلخَّصة من ${members.size} مادة"
                                            } else {
                                                "لا توجد مادة مُلخَّصة (التلخيص من داخل AiChat)"
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (enabled) {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            } else {
                                                MaterialTheme.colorScheme.error
                                            }
                                        )
                                        if (b.question.isNotBlank()) {
                                            Text(
                                                text = "السؤال: ${b.question}",
                                                style = MaterialTheme.typography.bodySmall,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                else -> key(session) {
                    TextToolsTab(
                        host = host,
                        onStatus = { showStatus(it) }
                    )
                }
            }
        }
    }
}

@Composable
private fun Message(text: String) {
    Box(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun MaterialCard(m: MaterialItem, onClick: () -> Unit) {
    Surface(
        tonalElevation = 2.dp,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                text = titleOf(m),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = buildString {
                    if (m.host.isNotBlank()) append(m.host).append(" • ")
                    append(m.text.length).append(" حرف")
                    if (isReady(m)) append(" • مُلخَّصة")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (m.text.isNotBlank()) {
                Text(
                    text = m.text.trim(),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private fun titleOf(m: MaterialItem): String {
    val d = m.digest?.titleAr?.takeIf { it.isNotBlank() && isReady(m) }
    return d ?: m.title.ifBlank { m.url }
}
