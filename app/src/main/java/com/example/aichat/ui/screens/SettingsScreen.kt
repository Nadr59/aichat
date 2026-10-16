package com.example.aichat.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.aichat.data.local.AiSettings
import com.example.aichat.data.model.ModelInfo
import com.example.aichat.repository.ModelCatalogRepository
import com.example.aichat.ui.components.AgentPickerDialog
import com.example.aichat.ui.viewmodel.AgentsViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: AiSettings,
    agentsViewModel: AgentsViewModel,
    onBack: () -> Unit,
    onOpenMemory: () -> Unit,
    onOpenWebPlatforms: () -> Unit,
    onOpenAgents: () -> Unit
) {
    var tab by remember { mutableIntStateOf(0) }

    val agents by agentsViewModel.agents.collectAsState()
    var showWebAgentPicker by remember { mutableStateOf(false) }
    var webAgentId by remember { mutableStateOf(settings.webAgentId) }

    var provider by remember { mutableStateOf(settings.provider) }
    var customUrl by remember { mutableStateOf(settings.customUrl) }

    val keys = remember {
        mutableStateMapOf(
            "gemini" to settings.geminiKey,
            "openrouter" to settings.openrouterKey,
            "openai" to settings.openaiKey,
            "mistral" to settings.mistralKey,
            "groq" to settings.groqKey,
            "nvidia" to settings.nvidiaKey,
            "huggingface" to settings.huggingfaceKey,
            "custom" to settings.customKey
        )
    }
    val modelsState = remember {
        mutableStateMapOf(
            "gemini" to settings.geminiModel,
            "openrouter" to settings.openrouterModel,
            "openai" to settings.openaiModel,
            "mistral" to settings.mistralModel,
            "groq" to settings.groqModel,
            "nvidia" to settings.nvidiaModel,
            "huggingface" to settings.huggingfaceModel,
            "ollama" to settings.ollamaModel,
            "custom" to settings.customModel
        )
    }

    var memoryCuratorEnabled by remember { mutableStateOf(settings.memoryCuratorEnabled) }
    var memoryCuratorProvider by remember { mutableStateOf(settings.memoryCuratorProvider) }
    var mediatorIdentityText by remember { mutableStateOf(settings.mediatorIdentityText) }

    var accuracyEnabled by remember { mutableStateOf(settings.accuracyPromptEnabled) }
    var customInstruction by remember { mutableStateOf(settings.customSystemInstruction) }
    var autoSave by remember { mutableStateOf(settings.autoSave) }

    var showKeys by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }

    val catalog = remember { ModelCatalogRepository(settings) }
    var models by remember { mutableStateOf<List<ModelInfo>>(emptyList()) }
    var loadingModels by remember { mutableStateOf(false) }
    var modelsError by remember { mutableStateOf<String?>(null) }
    var refreshTrigger by remember { mutableIntStateOf(0) }

    var onlyFree by remember { mutableStateOf(false) }
    var onlyRecommended by remember { mutableStateOf(false) }
    var onlyVision by remember { mutableStateOf(false) }

    val providers = listOf(
        "gemini" to "Google Gemini ⭐",
        "openrouter" to "OpenRouter",
        "openai" to "OpenAI",
        "mistral" to "Mistral AI",
        "groq" to "Groq",
        "nvidia" to "NVIDIA NIM",
        "huggingface" to "Hugging Face 🤗",
        "ollama" to "Ollama 🦙 (محلي)",
        "custom" to "Custom API"
    )

    val currentModel = modelsState[provider].orEmpty()

    fun selectProvider(newProvider: String) {
        if (provider == newProvider) return
        provider = newProvider
        saved = false
        onlyFree = false; onlyRecommended = false; onlyVision = false
        models = emptyList(); modelsError = null
    }

    fun selectCuratorProvider(newProvider: String) {
        if (memoryCuratorProvider == newProvider) return
        memoryCuratorProvider = newProvider
        saved = false
    }

    fun saveAll() {
        settings.apply {
            this.provider = provider

            geminiKey = keys["gemini"].orEmpty()
            geminiModel = modelsState["gemini"].orEmpty()

            openrouterKey = keys["openrouter"].orEmpty()
            openrouterModel = modelsState["openrouter"].orEmpty()

            openaiKey = keys["openai"].orEmpty()
            openaiModel = modelsState["openai"].orEmpty()

            mistralKey = keys["mistral"].orEmpty()
            mistralModel = modelsState["mistral"].orEmpty()

            groqKey = keys["groq"].orEmpty()
            groqModel = modelsState["groq"].orEmpty()

            nvidiaKey = keys["nvidia"].orEmpty()
            nvidiaModel = modelsState["nvidia"].orEmpty()

            huggingfaceKey = keys["huggingface"].orEmpty()
            huggingfaceModel = modelsState["huggingface"].orEmpty()

            ollamaModel = modelsState["ollama"].orEmpty()

            this.customUrl = customUrl
            customKey = keys["custom"].orEmpty()
            customModel = modelsState["custom"].orEmpty()

            accuracyPromptEnabled = accuracyEnabled
            customSystemInstruction = customInstruction

            this.memoryCuratorEnabled = memoryCuratorEnabled
            this.memoryCuratorProvider = memoryCuratorProvider
            this.mediatorIdentityText = mediatorIdentityText

            this.autoSave = autoSave

            // ✅ Web Agent مستقل
            this.webAgentId = webAgentId
        }
        saved = true
    }

    fun saveAccuracyOnly() {
        settings.accuracyPromptEnabled = accuracyEnabled
        settings.customSystemInstruction = customInstruction
        settings.autoSave = autoSave
        settings.webAgentId = webAgentId
        saved = true
    }

    LaunchedEffect(provider, refreshTrigger) {
        if (provider == "custom" || provider == "ollama") {
            models = emptyList(); loadingModels = false; modelsError = null
            return@LaunchedEffect
        }

        loadingModels = true
        models = emptyList()
        modelsError = null

        val keyToUse = keys[provider].orEmpty()
        val needsKey = provider != "huggingface"
        if (needsKey && keyToUse.isBlank()) {
            modelsError = "أدخل API Key أولاً ثم اضغط تحديث 🔄"
            loadingModels = false
            return@LaunchedEffect
        }

        try {
            models = catalog.getModels(provider = provider, apiKey = keyToUse)
        } catch (e: Exception) {
            modelsError = e.message ?: "تعذر تحميل النماذج"
        } finally {
            loadingModels = false
        }
    }

    val modelsWithSaved = remember(models, currentModel, provider) {
        if (currentModel.isBlank() || models.any { it.id == currentModel }) models
        else listOf(
            ModelInfo(
                id = currentModel,
                name = "$currentModel 💾",
                provider = provider,
                isFree = currentModel.contains(":free"),
                recommended = false
            )
        ) + models
    }

    val filteredModels = remember(modelsWithSaved, onlyFree, onlyRecommended, onlyVision) {
        modelsWithSaved
            .filter { !onlyFree || it.isFree }
            .filter { !onlyRecommended || it.recommended }
            .filter { !onlyVision || it.supportsVision }
    }

    // --- Provider meta map كما عندك (بدون تغيير) ---
    val providerMeta = remember { /* نفس map عندك */ emptyMap<String, ProviderMeta>() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("الإعدادات") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "رجوع") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {

            val tabs = listOf("🤖 المحادثة", "🧠 الذاكرة", "🌐 الويب", "🎯 الدقة", "⚙️ عام")
            ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
                tabs.forEachIndexed { i, title ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title) })
                }
            }

            when (tab) {
                // 0 و 1 و 3 و 4: نفس محتواك السابق (يمكنك إبقاؤه كما أرسلته)
                // لأجل الاختصار، لم أكررها هنا.

                2 -> TabColumn {
                    SectionTitle("🌐 الويب", "إدارة منصات الويب، ووكيل الويب المستقل.")

                    Card(
                        Modifier.fillMaxWidth().clickable(onClick = onOpenWebPlatforms),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("🌐", style = MaterialTheme.typography.headlineMedium)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("منصات الويب", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                                Text("إدارة منصات الويب وإعداداتها.", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }

                    // ✅ جديد: Web Agent مستقل
                    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.SmartToy, contentDescription = null)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("🤖 Web Agent (مستقل)", fontWeight = FontWeight.Bold)
                                    Text(
                                        "هذا الوكيل يُستخدم فقط عند الإرسال إلى منصات الويب (ChatGPT/Venice...).",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            val selectedName = agents.firstOrNull { it.id == webAgentId }?.name ?: "بدون وكيل"
                            OutlinedButton(onClick = { showWebAgentPicker = true }) {
                                Text("اختيار: $selectedName")
                            }

                            TextButton(onClick = onOpenAgents) { Text("إدارة/استيراد Agents") }
                        }
                    }

                    SaveButton(onClick = ::saveAll)
                    if (saved) SavedCard()
                }

                else -> {
                    // ضع تبويباتك كما هي من ملفك السابق
                }
            }
        }
    }

    if (showWebAgentPicker) {
        AgentPickerDialog(
            title = "Web Agent (مستقل)",
            agents = agents,
            selectedId = webAgentId.ifBlank { null },
            onDismiss = { showWebAgentPicker = false },
            onSelect = { id ->
                webAgentId = id ?: ""
                saved = false
                showWebAgentPicker = false
            },
            extraTopContent = {
                TextButton(onClick = {
                    showWebAgentPicker = false
                    onOpenAgents()
                }) { Text("فتح شاشة Agents") }
            }
        )
    }
}

// ===== helpers (انسخ Helpers من ملفك السابق كما هي) =====
private data class ProviderMeta(
    val info: String,
    val keyLabel: String,
    val keyPlaceholder: String,
    val manualModelField: Boolean = false
)

@Composable private fun TabColumn(content: @Composable ColumnScope.() -> Unit) { /* نفس كودك */ }
@Composable private fun SaveButton(onClick: () -> Unit) { /* نفس كودك */ }
@Composable private fun SectionTitle(title: String, subtitle: String) { /* نفس كودك */ }
@Composable private fun SavedCard() { /* نفس كودك */ }
@Composable private fun KeyField(label: String, value: String, onValueChange: (String) -> Unit, showKey: Boolean, placeholder: String) { /* نفس كودك */ }
