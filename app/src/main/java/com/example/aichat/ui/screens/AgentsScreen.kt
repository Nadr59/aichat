// app/src/main/java/com/example/aichat/ui/screens/AgentsScreen.kt
package com.example.aichat.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.aichat.data.model.AgentProfile
import com.example.aichat.ui.viewmodel.AgentsViewModel
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentsScreen(
    viewModel: AgentsViewModel,
    onNavigateUp: () -> Unit
) {
    val agents by viewModel.agents.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var importing by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<AgentProfile?>(null) }
    var editTarget by remember { mutableStateOf<AgentProfile?>(null) }

    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri == null) return@rememberLauncherForActivityResult

            importing = true
            viewModel.importAgent(uri) { result ->
                importing = false

                result.onSuccess { agent ->
                    scope.launch {
                        snackbar.showSnackbar("✅ تم استيراد: ${agent.name}")
                    }
                }.onFailure { e ->
                    scope.launch {
                        snackbar.showSnackbar("❌ فشل الاستيراد: ${e.message ?: "خطأ"}")
                    }
                }
            }
        }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Agents") },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "رجوع")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbar) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { importLauncher.launch(arrayOf("application/json", "image/png")) }
            ) {
                Icon(Icons.Filled.UploadFile, contentDescription = "استيراد")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (importing) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            if (agents.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(18.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("لا توجد شخصيات بعد", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "اضغط زر الاستيراد لاستيراد SillyTavern JSON أو PNG.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(agents, key = { it.id }) { agent ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        agent.name,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )

                                    val hint = agent.description
                                        .ifBlank { agent.personality }
                                        .ifBlank { agent.scenario }
                                        .ifBlank { agent.systemPrompt }

                                    if (hint.isNotBlank()) {
                                        Text(
                                            hint.take(120),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                IconButton(onClick = { editTarget = agent }) {
                                    Icon(Icons.Filled.Edit, contentDescription = "تعديل")
                                }

                                IconButton(onClick = { deleteTarget = agent }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "حذف")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    deleteTarget?.let { agent ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("حذف Agent") },
            text = { Text("هل تريد حذف: ${agent.name}؟") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteAgent(agent) {
                            deleteTarget = null
                        }
                    }
                ) { Text("حذف") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("إلغاء") }
            }
        )
    }

    editTarget?.let { agent ->
        AgentEditDialog(
            agent = agent,
            onDismiss = { editTarget = null },
            onSave = { updated ->
                viewModel.upsert(updated) { result ->
                    scope.launch {
                        result.onSuccess { snackbar.showSnackbar("✅ تم حفظ التعديل: ${updated.name}") }
                            .onFailure { e -> snackbar.showSnackbar("❌ فشل الحفظ: ${e.message ?: "خطأ"}") }
                    }
                }
                editTarget = null
            },
            onSaveCopy = { copy ->
                viewModel.upsert(copy) { result ->
                    scope.launch {
                        result.onSuccess { snackbar.showSnackbar("✅ تم إنشاء نسخة: ${copy.name}") }
                            .onFailure { e -> snackbar.showSnackbar("❌ فشل الحفظ: ${e.message ?: "خطأ"}") }
                    }
                }
                editTarget = null
            }
        )
    }
}

@Composable
private fun AgentEditDialog(
    agent: AgentProfile,
    onDismiss: () -> Unit,
    onSave: (AgentProfile) -> Unit,
    onSaveCopy: (AgentProfile) -> Unit
) {
    var name by remember(agent.id) { mutableStateOf(agent.name) }
    var systemPrompt by remember(agent.id) { mutableStateOf(agent.systemPrompt) }
    var description by remember(agent.id) { mutableStateOf(agent.description) }
    var personality by remember(agent.id) { mutableStateOf(agent.personality) }
    var scenario by remember(agent.id) { mutableStateOf(agent.scenario) }
    var exampleMessages by remember(agent.id) { mutableStateOf(agent.exampleMessages) }
    var postHistory by remember(agent.id) { mutableStateOf(agent.postHistoryInstructions) }
    var creatorNotes by remember(agent.id) { mutableStateOf(agent.creatorNotes) }

    fun buildUpdated(): AgentProfile = agent.copy(
        name = name.trim(),
        systemPrompt = systemPrompt.trim(),
        description = description.trim(),
        personality = personality.trim(),
        scenario = scenario.trim(),
        exampleMessages = exampleMessages.trim(),
        postHistoryInstructions = postHistory.trim(),
        creatorNotes = creatorNotes.trim(),
        updatedAt = System.currentTimeMillis()
    )

    val canSave = name.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("تعديل الوكيل") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "كل الحقول أدناه تُجمع وتُرسل للنموذج كتعليمات (الاسم أيضًا).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("الاسم") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = systemPrompt,
                    onValueChange = { systemPrompt = it },
                    label = { Text("التعليمات الرئيسية (System Prompt)") },
                    minLines = 6,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedButton(
                    onClick = {
                        description = ""
                        personality = ""
                        scenario = ""
                        exampleMessages = ""
                        postHistory = ""
                        creatorNotes = ""
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("تفريغ بقية الحقول (تبقى التعليمات الرئيسية فقط)") }

                Text(
                    "حقول أخرى",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )

                AgentTextField("الوصف (Description)", description) { description = it }
                AgentTextField("الشخصية (Personality)", personality) { personality = it }
                AgentTextField("السيناريو (Scenario)", scenario) { scenario = it }
                AgentTextField("أمثلة الحوار (Example Messages)", exampleMessages) { exampleMessages = it }
                AgentTextField("تعليمات ما بعد السجل (Post-History)", postHistory) { postHistory = it }
                AgentTextField("ملاحظات المنشئ (Creator Notes)", creatorNotes) { creatorNotes = it }

                OutlinedButton(
                    onClick = {
                        val base = buildUpdated()
                        val now = System.currentTimeMillis()
                        val copyName =
                            if (base.name == agent.name) "${base.name} (نسخة)" else base.name
                        onSaveCopy(
                            base.copy(
                                id = UUID.randomUUID().toString(),
                                name = copyName,
                                createdAt = now,
                                updatedAt = now
                            )
                        )
                    },
                    enabled = canSave,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("حفظ كنسخة جديدة (يبقى الأصل كما هو)") }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(buildUpdated()) },
                enabled = canSave
            ) { Text("حفظ") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("إلغاء") }
        }
    )
}

@Composable
private fun AgentTextField(
    label: String,
    value: String,
    onChange: (String) -> Unit
) {
    var open by remember { mutableStateOf(value.isNotBlank()) }

    if (!open) {
        TextButton(onClick = { open = true }) { Text("+ $label") }
    } else {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label) },
            minLines = 3,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
