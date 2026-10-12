package com.example.aichat.ui.screens

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.aichat.data.model.MemoryItem
import com.example.aichat.ui.viewmodel.ChatViewModel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(
    viewModel: ChatViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }

    val sharedMemories by viewModel.sharedMemories.collectAsState()
    val conversationId by viewModel.currentConversationId.collectAsState()

    val conversationMemories by remember(conversationId) {
        if (conversationId != null) {
            viewModel.getConversationMemories(conversationId!!)
        } else {
            flowOf(emptyList<MemoryItem>())
        }
    }.collectAsState(initial = emptyList())

    var searchQuery by remember { mutableStateOf("") }
    var editingMemory by remember { mutableStateOf<MemoryItem?>(null) }

    // ✅ جديد: إضافة يدوية
    var showAddDialog by remember { mutableStateOf(false) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.processAndSaveFile(it) }
    }

    val filteredShared = if (searchQuery.isBlank()) {
        sharedMemories
    } else {
        sharedMemories.filter {
            it.content.contains(searchQuery, ignoreCase = true) ||
                it.category.contains(searchQuery, ignoreCase = true)
        }
    }

    val localMemories = if (searchQuery.isBlank()) {
        conversationMemories
    } else {
        conversationMemories.filter {
            it.content.contains(searchQuery, ignoreCase = true) ||
                it.category.contains(searchQuery, ignoreCase = true)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("الذكريات") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Filled.ArrowBack,
                            contentDescription = "رجوع"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = "إضافة ذاكرة"
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = "إضافة ذاكرة")
            }
        },
        snackbarHost = { SnackbarHost(snack) }
    ) { padding ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp)
        ) {

            Button(
                onClick = { filePickerLauncher.launch("*/*") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.AttachFile,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("رفع ملف (TXT أو PDF)")
            }

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("البحث في الذكريات") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = null
                    )
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {

                item {
                    Text(
                        text = "🌐 الذكريات المشتركة (${filteredShared.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 6.dp)
                    )
                }

                if (filteredShared.isEmpty()) {
                    item { EmptyMemoryText(text = "لا توجد ذكريات مشتركة.") }
                } else {
                    items(
                        items = filteredShared,
                        key = { it.id }
                    ) { memory ->
                        MemoryCard(
                            memory = memory,
                            onEdit = { editingMemory = memory },
                            onDelete = { viewModel.deleteMemory(memory) }
                        )
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "💬 ذكريات هذه المحادثة (${localMemories.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 6.dp)
                    )
                }

                when {
                    conversationId == null -> {
                        item { EmptyMemoryText(text = "لا توجد محادثة مفتوحة حاليًا.") }
                    }

                    localMemories.isEmpty() -> {
                        item { EmptyMemoryText(text = "لا توجد ذكريات خاصة بهذه المحادثة.") }
                    }

                    else -> {
                        items(
                            items = localMemories,
                            key = { it.id }
                        ) { memory ->
                            MemoryCard(
                                memory = memory,
                                onEdit = { editingMemory = memory },
                                onDelete = { viewModel.deleteMemory(memory) }
                            )
                        }
                    }
                }

                item { Spacer(modifier = Modifier.height(24.dp)) }
            }
        }
    }

    // ✅ Dialog إضافة ذاكرة يدوياً
    if (showAddDialog) {
        AddMemoryDialog(
            conversationId = conversationId,
            onDismiss = { showAddDialog = false },
            onSave = { content, category, isShared ->
                scope.launch {
                    runCatching {
                        viewModel.addManualMemory(
                            content = content,
                            category = category,
                            isShared = isShared,
                            conversationId = conversationId
                        )
                        snack.showSnackbar("✅ تم الحفظ")
                    }.onFailure {
                        snack.showSnackbar("❌ فشل الحفظ: ${it.message}")
                    }
                }
            }
        )
    }

    // Dialog تعديل ذاكرة موجودة
    editingMemory?.let { memory ->
        EditMemoryDialog(
            memory = memory,
            onDismiss = { editingMemory = null },
            onSave = { updatedContent ->
                viewModel.updateMemory(memory.copy(content = updatedContent))
                editingMemory = null
            }
        )
    }
}

@Composable
private fun MemoryCard(
    memory: MemoryItem,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {

            Row(verticalAlignment = Alignment.CenterVertically) {

                Icon(
                    imageVector = Icons.Filled.Memory,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text = categoryName(memory.category),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                IconButton(onClick = onEdit) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = "تعديل"
                    )
                }

                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = "حذف"
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = memory.content,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 8,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = if (memory.isShared) "🌐 ذاكرة مشتركة" else "💬 لهذه المحادثة فقط",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun EmptyMemoryText(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun EditMemoryDialog(
    memory: MemoryItem,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var content by remember { mutableStateOf(memory.content) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("تعديل الذاكرة") },
        text = {
            OutlinedTextField(
                value = content,
                onValueChange = { content = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 4,
                maxLines = 12
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (content.trim().isNotBlank()) onSave(content.trim())
                }
            ) { Text("حفظ") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("إلغاء") }
        }
    )
}

@Composable
private fun AddMemoryDialog(
    conversationId: Long?,
    onDismiss: () -> Unit,
    onSave: (content: String, category: String, isShared: Boolean) -> Unit
) {
    val context = LocalContext.current
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    var content by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("OTHER") }
    var isShared by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("إضافة ذاكرة") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {

                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("المحتوى") },
                    placeholder = { Text("الصق هنا أي ملخص/معلومة... (سيتم تقسيمه تلقائياً إن كان طويلاً)") },
                    minLines = 6,
                    maxLines = 14,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            val clip = clipboard.primaryClip
                            val txt = clip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                            if (txt.isNotBlank()) content = txt
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("لصق")
                    }

                    OutlinedButton(
                        onClick = { content = "" },
                        modifier = Modifier.weight(1f)
                    ) { Text("مسح") }
                }

                OutlinedTextField(
                    value = category,
                    onValueChange = { category = it },
                    label = { Text("التصنيف (مثل: PROJECT / KNOWLEDGE / PREFERENCE / OTHER)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Checkbox(
                        checked = isShared,
                        onCheckedChange = { isShared = it }
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            text = if (isShared) "🌐 حفظ كذاكرة مشتركة" else "💬 حفظ كذاكرة لهذه المحادثة فقط",
                            fontWeight = FontWeight.Bold
                        )
                        if (!isShared && conversationId == null) {
                            Text(
                                text = "لا توجد محادثة مفتوحة حالياً، سيتم الحفظ كمشتركة.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = content.trim().isNotBlank(),
                onClick = {
                    val finalShared = if (conversationId == null) true else isShared
                    onSave(content.trim(), category.trim().ifBlank { "OTHER" }, finalShared)
                    onDismiss()
                }
            ) { Text("حفظ") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("إلغاء") }
        }
    )
}

private fun categoryName(category: String): String {
    return when (category) {
        "KNOWLEDGE" -> "📚 معرفة"
        "PROJECT" -> "🛠️ مشروع"
        "PREFERENCE" -> "⭐ تفضيل"
        else -> "📝 أخرى"
    }
}
