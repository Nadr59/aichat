   
package com.example.aichat.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.aichat.data.model.MemoryItem
import com.example.aichat.ui.viewmodel.ChatViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(
    viewModel: ChatViewModel,
    onBack: () -> Unit
) {
    val sharedMemories by viewModel.sharedMemories.collectAsState()
    val conversationId by viewModel.currentConversationId.collectAsState()

    val conversationMemories =
        if (conversationId != null) {
            viewModel.getConversationMemories(conversationId!!)
                .collectAsState(initial = emptyList())
        } else {
            remember {
                mutableStateOf(emptyList())
            }
        }

    var searchQuery by remember {
        mutableStateOf("")
    }

    var editingMemory by remember {
        mutableStateOf<MemoryItem?>(null)
    }

    val filteredShared =
        if (searchQuery.isBlank()) {
            sharedMemories
        } else {
            sharedMemories.filter {
                it.content.contains(
                    searchQuery,
                    ignoreCase = true
                ) ||
                it.category.contains(
                    searchQuery,
                    ignoreCase = true
                )
            }
        }

        // في Composable

val filePickerLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.GetContent()
) { uri: Uri? ->
    uri?.let {
        viewModel.processAndSaveFile(it)
    }
}

Button(
    onClick = {
        filePickerLauncher.launch("*/*")  // أو "text/plain|application/pdf"
    }
) {
    Icon(Icons.Default.AttachFile, contentDescription = null)
    Spacer(modifier = Modifier.width(8.dp))
    Text("رفع ملف (TXT/PDF)")
}
    val localMemories =
        if (searchQuery.isBlank()) {
            conversationMemories.value
        } else {
            conversationMemories.value.filter {
                it.content.contains(
                    searchQuery,
                    ignoreCase = true
                ) ||
                it.category.contains(
                    searchQuery,
                    ignoreCase = true
                )
            }
        }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("الذكريات")
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Filled.ArrowBack,
                            contentDescription = "رجوع"
                        )
                    }
                }
            )
        }
    ) { padding ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp)
        ) {

            OutlinedTextField(
                value = searchQuery,
                onValueChange = {
                    searchQuery = it
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = {
                    Text("البحث في الذكريات")
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = null
                    )
                }
            )

            Spacer(
                modifier = Modifier.height(12.dp)
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement =
                    Arrangement.spacedBy(8.dp)
            ) {

                item {

                    Text(
                        text = "🌐 الذكريات المشتركة",
                        style =
                            MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier =
                            Modifier.padding(
                                vertical = 6.dp
                            )
                    )
                }

                if (filteredShared.isEmpty()) {

                    item {

                        EmptyMemoryText(
                            text = "لا توجد ذكريات مشتركة."
                        )
                    }

                } else {

                    items(
                        items = filteredShared,
                        key = { it.id }
                    ) { memory ->

                        MemoryCard(
                            memory = memory,
                            onEdit = {
                                editingMemory = memory
                            },
                            onDelete = {
                                viewModel.deleteMemory(memory)
                            }
                        )
                    }
                }

                item {

                    Spacer(
                        modifier = Modifier.height(12.dp)
                    )

                    Text(
                        text = "💬 ذكريات هذه المحادثة",
                        style =
                            MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier =
                            Modifier.padding(
                                vertical = 6.dp
                            )
                    )
                }

                if (conversationId == null) {

                    item {

                        EmptyMemoryText(
                            text =
                                "لا توجد محادثة مفتوحة حاليًا."
                        )
                    }

                } else if (localMemories.isEmpty()) {

                    item {

                        EmptyMemoryText(
                            text =
                                "لا توجد ذكريات خاصة بهذه المحادثة."
                        )
                    }

                } else {

                    items(
                        items = localMemories,
                        key = { it.id }
                    ) { memory ->

                        MemoryCard(
                            memory = memory,
                            onEdit = {
                                editingMemory = memory
                            },
                            onDelete = {
                                viewModel.deleteMemory(memory)
                            }
                        )
                    }
                }

                item {
                    Spacer(
                        modifier = Modifier.height(24.dp)
                    )
                }
            }
        }
    }

    editingMemory?.let { memory ->

        EditMemoryDialog(
            memory = memory,
            onDismiss = {
                editingMemory = null
            },
            onSave = { updatedContent ->

                viewModel.updateMemory(
                    memory.copy(
                        content = updatedContent
                    )
                )

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
            containerColor =
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {

        Column(
            modifier = Modifier.padding(12.dp)
        ) {

            Row(
                verticalAlignment =
                    Alignment.CenterVertically
            ) {

                Icon(
                    imageVector = Icons.Filled.Memory,
                    contentDescription = null,
                    tint =
                        MaterialTheme.colorScheme.primary
                )

                Spacer(
                    modifier = Modifier.width(8.dp)
                )

                Text(
                    text = categoryName(memory.category),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )

                IconButton(
                    onClick = onEdit
                ) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = "تعديل"
                    )
                }

                IconButton(
                    onClick = onDelete
                ) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = "حذف"
                    )
                }
            }

            Spacer(
                modifier = Modifier.height(6.dp)
            )

            Text(
                text = memory.content,
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(
                modifier = Modifier.height(6.dp)
            )

            Text(
                text =
                    if (memory.isShared) {
                        "🌐 ذاكرة مشتركة"
                    } else {
                        "💬 لهذه المحادثة فقط"
                    },
                style = MaterialTheme.typography.labelSmall,
                color =
                    MaterialTheme.colorScheme
                        .onSurfaceVariant
            )
        }
    }
}

@Composable
private fun EmptyMemoryText(
    text: String
) {
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        style = MaterialTheme.typography.bodySmall,
        color =
            MaterialTheme.colorScheme
                .onSurfaceVariant
    )
}

@Composable
private fun EditMemoryDialog(
    memory: MemoryItem,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var content by remember {
        mutableStateOf(memory.content)
    }

    AlertDialog(
        onDismissRequest = onDismiss,

        title = {
            Text("تعديل الذاكرة")
        },

        text = {

            OutlinedTextField(
                value = content,
                onValueChange = {
                    content = it
                },
                modifier = Modifier.fillMaxWidth(),
                minLines = 4,
                maxLines = 10
            )
        },

        confirmButton = {

            TextButton(
                onClick = {
                    if (content.trim().isNotBlank()) {
                        onSave(content.trim())
                    }
                }
            ) {
                Text("حفظ")
            }
        },

        dismissButton = {

            TextButton(
                onClick = onDismiss
            ) {
                Text("إلغاء")
            }
        }
    )
}

private fun categoryName(
    category: String
): String {
    return when (category) {
        "KNOWLEDGE" -> "📚 معرفة"
        "PROJECT" -> "🛠️ مشروع"
        "PREFERENCE" -> "⭐ تفضيل"
        else -> "📝 أخرى"
    }
}
