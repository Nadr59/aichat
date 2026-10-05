package com.example.aichat.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.aichat.data.local.AiSettings
import com.example.aichat.data.repository.ModelCatalogRepository

@Composable
fun SettingsScreen(
    settings: AiSettings,
    onBack: () -> Unit,
    onOpenMemory: () -> Unit,
    onOpenWebPlatforms: () -> Unit
) {
    // ============================================================
    // التبويبات
    // ============================================================

    val tabs = listOf(
        "🤖 المحادثة",
        "🧠 الذاكرة",
        "🌐 الويب",
        "🎯 الدقة",
        "⚙️ عام"
    )

    var selectedTab by remember {
        mutableStateOf(0)
    }

    // ============================================================
    // Provider
    // ============================================================

    var provider by remember {
        mutableStateOf(settings.provider)
    }

    // ============================================================
    // Gemini
    // ============================================================

    var geminiKey by remember {
        mutableStateOf(settings.geminiKey)
    }

    var geminiModel by remember {
        mutableStateOf(settings.geminiModel)
    }

    // ============================================================
    // OpenRouter
    // ============================================================

    var openrouterKey by remember {
        mutableStateOf(settings.openrouterKey)
    }

    var openrouterModel by remember {
        mutableStateOf(settings.openrouterModel)
    }

    // ============================================================
    // OpenAI
    // ============================================================

    var openaiKey by remember {
        mutableStateOf(settings.openaiKey)
    }

    var openaiModel by remember {
        mutableStateOf(settings.openaiModel)
    }

    // ============================================================
    // Mistral
    // ============================================================

    var mistralKey by remember {
        mutableStateOf(settings.mistralKey)
    }

    var mistralModel by remember {
        mutableStateOf(settings.mistralModel)
    }

    // ============================================================
    // Groq
    // ============================================================

    var groqKey by remember {
        mutableStateOf(settings.groqKey)
    }

    var groqModel by remember {
        mutableStateOf(settings.groqModel)
    }

    // ============================================================
    // NVIDIA
    // ============================================================

    var nvidiaKey by remember {
        mutableStateOf(settings.nvidiaKey)
    }

    var nvidiaModel by remember {
        mutableStateOf(settings.nvidiaModel)
    }

    // ============================================================
    // Hugging Face
    // ============================================================

    var huggingfaceKey by remember {
        mutableStateOf(settings.huggingfaceKey)
    }

    var huggingfaceModel by remember {
        mutableStateOf(settings.huggingfaceModel)
    }

    // ============================================================
    // Ollama
    // ============================================================

    var ollamaModel by remember {
        mutableStateOf(settings.ollamaModel)
    }

    // ============================================================
    // Custom
    // ============================================================

    var customUrl by remember {
        mutableStateOf(settings.customUrl)
    }

    var customKey by remember {
        mutableStateOf(settings.customKey)
    }

    var customModel by remember {
        mutableStateOf(settings.customModel)
    }

    // ============================================================
    // Memory
    // ============================================================

    var autoSave by remember {
        mutableStateOf(settings.autoSave)
    }

    // ============================================================
    // Memory Curator
    // ============================================================

    var memoryCuratorEnabled by remember {
        mutableStateOf(settings.memoryCuratorEnabled)
    }

    var memoryCuratorModel by remember {
        mutableStateOf(settings.memoryCuratorModel)
    }

    var memoryCuratorProvider by remember {
        mutableStateOf(settings.memoryCuratorProvider)
    }

    var memoryCuratorOllamaModel by remember {
        mutableStateOf(settings.memoryCuratorOllamaModel)
    }

    var mediatorIdentityText by remember {
        mutableStateOf(settings.mediatorIdentityText)
    }

    // ============================================================
    // Accuracy
    // ============================================================

    var accuracyPromptEnabled by remember {
        mutableStateOf(settings.accuracyPromptEnabled)
    }

    var customSystemInstruction by remember {
        mutableStateOf(settings.customSystemInstruction)
    }

    // ============================================================
    // UI
    // ============================================================

    var showKeys by remember {
        mutableStateOf(false)
    }

    var saved by remember {
        mutableStateOf(false)
    }

    var refreshTrigger by remember {
        mutableStateOf(0)
    }

    val modelCatalogRepository = remember {
        ModelCatalogRepository(settings)
    }

    var models by remember {
        mutableStateOf<List<String>>(emptyList())
    }

    // ============================================================
    // تحميل النماذج
    // ============================================================

    LaunchedEffect(
        provider,
        refreshTrigger
    ) {
        models = runCatching {
            modelCatalogRepository.getModels(provider)
        }.getOrDefault(emptyList())
    }

    // ============================================================
    // حفظ الإعدادات
    // ============================================================

    fun saveSettings() {

        settings.provider = provider

        settings.geminiKey = geminiKey
        settings.geminiModel = geminiModel

        settings.openrouterKey = openrouterKey
        settings.openrouterModel = openrouterModel

        settings.openaiKey = openaiKey
        settings.openaiModel = openaiModel

        settings.mistralKey = mistralKey
        settings.mistralModel = mistralModel

        settings.groqKey = groqKey
        settings.groqModel = groqModel

        settings.nvidiaKey = nvidiaKey
        settings.nvidiaModel = nvidiaModel

        settings.huggingfaceKey = huggingfaceKey
        settings.huggingfaceModel = huggingfaceModel

        settings.ollamaModel = ollamaModel

        settings.customUrl = customUrl
        settings.customKey = customKey
        settings.customModel = customModel

        settings.autoSave = autoSave

        settings.memoryCuratorEnabled =
            memoryCuratorEnabled

        settings.memoryCuratorModel =
            memoryCuratorModel

        settings.memoryCuratorProvider =
            memoryCuratorProvider

        settings.memoryCuratorOllamaModel =
            memoryCuratorOllamaModel

        settings.mediatorIdentityText =
            mediatorIdentityText

        settings.accuracyPromptEnabled =
            accuracyPromptEnabled

        settings.customSystemInstruction =
            customSystemInstruction

        saved = true
    }

    Scaffold(

        topBar = {

            TopAppBar(

                title = {
                    Text("الإعدادات")
                },

                navigationIcon = {

                    IconButton(
                        onClick = onBack
                    ) {

                        Icon(
                            imageVector =
                                Icons.Filled.ArrowBack,
                            contentDescription =
                                "رجوع"
                        )
                    }
                }
            )
        },

        bottomBar = {

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(
                        horizontal = 16.dp,
                        vertical = 8.dp
                    )
            ) {

                if (saved) {

                    Text(
                        text = "✓ تم حفظ الإعدادات",
                        color =
                            MaterialTheme.colorScheme.primary,
                        modifier =
                            Modifier.padding(
                                bottom = 6.dp
                            )
                    )
                }

                Button(
                    onClick = {
                        saveSettings()
                    },
                    modifier =
                        Modifier.fillMaxWidth()
                ) {

                    Icon(
                        imageVector =
                            Icons.Filled.Save,
                        contentDescription =
                            null
                    )

                    Spacer(
                        modifier =
                            Modifier.width(8.dp)
                    )

                    Text("حفظ الإعدادات")
                }
            }
        }

    ) { padding ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {

            // ========================================================
            // Tabs
            // ========================================================

            ScrollableTabRow(
                selectedTabIndex = selectedTab,
                edgePadding = 8.dp
            ) {

                tabs.forEachIndexed { index, title ->

                    Tab(
                        selected =
                            selectedTab == index,

                        onClick = {
                            selectedTab = index
                            saved = false
                        },

                        text = {
                            Text(title)
                        }
                    )
                }
            }

            // ========================================================
            // محتوى التبويب
            // ========================================================

            when (selectedTab) {

                // ====================================================
                // 🤖 المحادثة
                // ====================================================

                0 -> {

                    ConversationSettingsTab(

                        provider = provider,

                        onProviderChange = {
                            provider = it
                            saved = false
                        },

                        geminiKey = geminiKey,
                        onGeminiKeyChange = {
                            geminiKey = it
                            saved = false
                        },

                        geminiModel = geminiModel,
                        onGeminiModelChange = {
                            geminiModel = it
                            saved = false
                        },

                        openrouterKey = openrouterKey,
                        onOpenrouterKeyChange = {
                            openrouterKey = it
                            saved = false
                        },

                        openrouterModel = openrouterModel,
                        onOpenrouterModelChange = {
                            openrouterModel = it
                            saved = false
                        },

                        openaiKey = openaiKey,
                        onOpenaiKeyChange = {
                            openaiKey = it
                            saved = false
                        },

                        openaiModel = openaiModel,
                        onOpenaiModelChange = {
                            openaiModel = it
                            saved = false
                        },

                        mistralKey = mistralKey,
                        onMistralKeyChange = {
                            mistralKey = it
                            saved = false
                        },

                        mistralModel = mistralModel,
                        onMistralModelChange = {
                            mistralModel = it
                            saved = false
                        },

                        groqKey = groqKey,
                        onGroqKeyChange = {
                            groqKey = it
                            saved = false
                        },

                        groqModel = groqModel,
                        onGroqModelChange = {
                            groqModel = it
                            saved = false
                        },

                        nvidiaKey = nvidiaKey,
                        onNvidiaKeyChange = {
                            nvidiaKey = it
                            saved = false
                        },

                        nvidiaModel = nvidiaModel,
                        onNvidiaModelChange = {
                            nvidiaModel = it
                            saved = false
                        },

                        huggingfaceKey = huggingfaceKey,
                        onHuggingfaceKeyChange = {
                            huggingfaceKey = it
                            saved = false
                        },

                        huggingfaceModel = huggingfaceModel,
                        onHuggingfaceModelChange = {
                            huggingfaceModel = it
                            saved = false
                        },

                        ollamaModel = ollamaModel,
                        onOllamaModelChange = {
                            ollamaModel = it
                            saved = false
                        },

                        customUrl = customUrl,
                        onCustomUrlChange = {
                            customUrl = it
                            saved = false
                        },

                        customKey = customKey,
                        onCustomKeyChange = {
                            customKey = it
                            saved = false
                        },

                        customModel = customModel,
                        onCustomModelChange = {
                            customModel = it
                            saved = false
                        },

                        models = models,

                        onRefresh = {
                            refreshTrigger++
                        },

                        showKeys = showKeys,

                        onToggleKeys = {
                            showKeys = !showKeys
                        }
                    )
                }

                // ====================================================
                // 🧠 الذاكرة
                // ====================================================

                1 -> {

                    MemorySettingsTab(

                        autoSave = autoSave,

                        onAutoSaveChange = {
                            autoSave = it
                            saved = false
                        },

                        onOpenMemory = onOpenMemory,

                        memoryCuratorEnabled =
                            memoryCuratorEnabled,

                        onMemoryCuratorEnabledChange = {
                            memoryCuratorEnabled = it
                            saved = false
                        },

                        memoryCuratorProvider =
                            memoryCuratorProvider,

                        onMemoryCuratorProviderChange = {
                            memoryCuratorProvider = it
                            saved = false
                        },

                        memoryCuratorModel =
                            memoryCuratorModel,

                        onMemoryCuratorModelChange = {
                            memoryCuratorModel = it
                            saved = false
                        },

                        memoryCuratorOllamaModel =
                            memoryCuratorOllamaModel,

                        onMemoryCuratorOllamaModelChange = {
                            memoryCuratorOllamaModel = it
                            saved = false
                        },

                        mediatorIdentityText =
                            mediatorIdentityText,

                        onMediatorIdentityTextChange = {
                            mediatorIdentityText = it
                            saved = false
                        }
                    )
                }

                // ====================================================
                // 🌐 الويب
                // ====================================================

                2 -> {

                    WebSettingsTab(
                        onOpenWebPlatforms =
                            onOpenWebPlatforms
                    )
                }

                // ====================================================
                // 🎯 الدقة
                // ====================================================

                3 -> {

                    AccuracySettingsTab(

                        accuracyPromptEnabled =
                            accuracyPromptEnabled,

                        onAccuracyPromptEnabledChange = {
                            accuracyPromptEnabled = it
                            saved = false
                        },

                        customSystemInstruction =
                            customSystemInstruction,

                        onCustomSystemInstructionChange = {
                            customSystemInstruction = it
                            saved = false
                        }
                    )
                }

                // ====================================================
                // ⚙️ عام
                // ====================================================

                4 -> {

                    GeneralSettingsTab()
                }
            }
        }
    }
}

// ====================================================================
// 🤖 المحادثة
// ====================================================================

@Composable
private fun ConversationSettingsTab(
    provider: String,
    onProviderChange: (String) -> Unit,

    geminiKey: String,
    onGeminiKeyChange: (String) -> Unit,
    geminiModel: String,
    onGeminiModelChange: (String) -> Unit,

    openrouterKey: String,
    onOpenrouterKeyChange: (String) -> Unit,
    openrouterModel: String,
    onOpenrouterModelChange: (String) -> Unit,

    openaiKey: String,
    onOpenaiKeyChange: (String) -> Unit,
    openaiModel: String,
    onOpenaiModelChange: (String) -> Unit,

    mistralKey: String,
    onMistralKeyChange: (String) -> Unit,
    mistralModel: String,
    onMistralModelChange: (String) -> Unit,

    groqKey: String,
    onGroqKeyChange: (String) -> Unit,
    groqModel: String,
    onGroqModelChange: (String) -> Unit,

    nvidiaKey: String,
    onNvidiaKeyChange: (String) -> Unit,
    nvidiaModel: String,
    onNvidiaModelChange: (String) -> Unit,

    huggingfaceKey: String,
    onHuggingfaceKeyChange: (String) -> Unit,
    huggingfaceModel: String,
    onHuggingfaceModelChange: (String) -> Unit,

    ollamaModel: String,
    onOllamaModelChange: (String) -> Unit,

    customUrl: String,
    onCustomUrlChange: (String) -> Unit,
    customKey: String,
    onCustomKeyChange: (String) -> Unit,
    customModel: String,
    onCustomModelChange: (String) -> Unit,

    models: List<String>,
    onRefresh: () -> Unit,

    showKeys: Boolean,
    onToggleKeys: () -> Unit
) {

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp)
    ) {

        Text(
            text = "🤖 إعدادات المحادثة",
            style = MaterialTheme.typography.headlineSmall
        )

        Spacer(
            modifier = Modifier.height(16.dp)
        )

        SectionCard(
            title = "مزود الذكاء الاصطناعي"
        ) {

            ProviderButton(
                selected = provider == "gemini",
                text = "Gemini",
                onClick = {
                    onProviderChange("gemini")
                }
            )

            ProviderButton(
                selected = provider == "openrouter",
                text = "OpenRouter",
                onClick = {
                    onProviderChange("openrouter")
                }
            )

            ProviderButton(
                selected = provider == "openai",
                text = "OpenAI",
                onClick = {
                    onProviderChange("openai")
                }
            )

            ProviderButton(
                selected = provider == "mistral",
                text = "Mistral",
                onClick = {
                    onProviderChange("mistral")
                }
            )

            ProviderButton(
                selected = provider == "groq",
                text = "Groq",
                onClick = {
                    onProviderChange("groq")
                }
            )

            ProviderButton(
                selected = provider == "nvidia",
                text = "NVIDIA",
                onClick = {
                    onProviderChange("nvidia")
                }
            )

            ProviderButton(
                selected = provider == "huggingface",
                text = "Hugging Face",
                onClick = {
                    onProviderChange("huggingface")
                }
            )

            ProviderButton(
                selected = provider == "ollama",
                text = "Ollama",
                onClick = {
                    onProviderChange("ollama")
                }
            )

            ProviderButton(
                selected = provider == "custom",
                text = "Custom",
                onClick = {
                    onProviderChange("custom")
                }
            )
        }

        Spacer(
            modifier = Modifier.height(16.dp)
        )

        when (provider) {

            "gemini" -> {

                ProviderFields(
                    key = geminiKey,
                    onKeyChange = onGeminiKeyChange,
                    model = geminiModel,
                    onModelChange = onGeminiModelChange,
                    models = models,
                    onRefresh = onRefresh,
                    showKeys = showKeys,
                    onToggleKeys = onToggleKeys
                )
            }

            "openrouter" -> {

                ProviderFields(
                    key = openrouterKey,
                    onKeyChange = onOpenrouterKeyChange,
                    model = openrouterModel,
                    onModelChange = onOpenrouterModelChange,
                    models = models,
                    onRefresh = onRefresh,
                    showKeys = showKeys,
                    onToggleKeys = onToggleKeys
                )
            }

            "openai" -> {

                ProviderFields(
                    key = openaiKey,
                    onKeyChange = onOpenaiKeyChange,
                    model = openaiModel,
                    onModelChange = onOpenaiModelChange,
                    models = models,
                    onRefresh = onRefresh,
                    showKeys = showKeys,
                    onToggleKeys = onToggleKeys
                )
            }

            "mistral" -> {

                ProviderFields(
                    key = mistralKey,
                    onKeyChange = onMistralKeyChange,
                    model = mistralModel,
                    onModelChange = onMistralModelChange,
                    models = models,
                    onRefresh = onRefresh,
                    showKeys = showKeys,
                    onToggleKeys = onToggleKeys
                )
            }

            "groq" -> {

                ProviderFields(
                    key = groqKey,
                    onKeyChange = onGroqKeyChange,
                    model = groqModel,
                    onModelChange = onGroqModelChange,
                    models = models,
                    onRefresh = onRefresh,
                    showKeys = showKeys,
                    onToggleKeys = onToggleKeys
                )
            }

            "nvidia" -> {

                ProviderFields(
                    key = nvidiaKey,
                    onKeyChange = onNvidiaKeyChange,
                    model = nvidiaModel,
                    onModelChange = onNvidiaModelChange,
                    models = models,
                    onRefresh = onRefresh,
                    showKeys = showKeys,
                    onToggleKeys = onToggleKeys
                )
            }

            "huggingface" -> {

                ProviderFields(
                    key = huggingfaceKey,
                    onKeyChange = onHuggingfaceKeyChange,
                    model = huggingfaceModel,
                    onModelChange = onHuggingfaceModelChange,
                    models = models,
                    onRefresh = onRefresh,
                    showKeys = showKeys,
                    onToggleKeys = onToggleKeys
                )
            }

            "ollama" -> {

                OutlinedTextField(
                    value = ollamaModel,
                    onValueChange = onOllamaModelChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = {
                        Text("نموذج Ollama")
                    },
                    singleLine = true
                )
            }

            "custom" -> {

                OutlinedTextField(
                    value = customUrl,
                    onValueChange = onCustomUrlChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = {
                        Text("رابط API")
                    },
                    singleLine = true
                )

                Spacer(
                    modifier = Modifier.height(12.dp)
                )

                OutlinedTextField(
                    value = customKey,
                    onValueChange = onCustomKeyChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = {
                        Text("API Key")
                    },
                    singleLine = true,
                    visualTransformation =
                        if (showKeys) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                    trailingIcon = {

                        IconButton(
                            onClick = onToggleKeys
                        ) {

                            Icon(
                                imageVector =
                                    if (showKeys) {
                                        Icons.Filled.VisibilityOff
                                    } else {
                                        Icons.Filled.Visibility
                                    },
                                contentDescription =
                                    null
                            )
                        }
                    }
                )

                Spacer(
                    modifier = Modifier.height(12.dp)
                )

                OutlinedTextField(
                    value = customModel,
                    onValueChange = onCustomModelChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = {
                        Text("النموذج")
                    },
                    singleLine = true
                )
            }
        }

        Spacer(
            modifier = Modifier.height(24.dp)
        )
    }
}

// ====================================================================
// 🧠 الذاكرة
// ====================================================================

@Composable
private fun MemorySettingsTab(
    autoSave: Boolean,
    onAutoSaveChange: (Boolean) -> Unit,
    onOpenMemory: () -> Unit,

    memoryCuratorEnabled: Boolean,
    onMemoryCuratorEnabledChange: (Boolean) -> Unit,

    memoryCuratorProvider: String,
    onMemoryCuratorProviderChange: (String) -> Unit,

    memoryCuratorModel: String,
    onMemoryCuratorModelChange: (String) -> Unit,

    memoryCuratorOllamaModel: String,
    onMemoryCuratorOllamaModelChange: (String) -> Unit,

    mediatorIdentityText: String,
    onMediatorIdentityTextChange: (String) -> Unit
) {

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp)
    ) {

        Text(
            text = "🧠 الذاكرة المشتركة",
            style = MaterialTheme.typography.headlineSmall
        )

        Spacer(
            modifier = Modifier.height(16.dp)
        )

        // ============================================================
        // الحفظ التلقائي
        // ============================================================

        Card(
            modifier = Modifier.fillMaxWidth()
        ) {

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),

                verticalAlignment =
                    Alignment.CenterVertically
            ) {

                Column(
                    modifier =
                        Modifier.weight(1f)
                ) {

                    Text(
                        text =
                            "💾 الحفظ التلقائي للردود",

                        style =
                            MaterialTheme.typography.titleMedium
                    )

                    Spacer(
                        modifier =
                            Modifier.height(4.dp)
                    )

                    Text(
                        text =
                            "حفظ ردود المساعد تلقائيًا في الذاكرة المشتركة",

                        style =
                            MaterialTheme.typography.bodyMedium
                    )
                }

                Switch(
                    checked = autoSave,

                    onCheckedChange =
                        onAutoSaveChange
                )
            }
        }

        Spacer(
            modifier = Modifier.height(12.dp)
        )

        // ============================================================
        // فتح شاشة الذاكرة
        // ============================================================

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    onOpenMemory()
                }
        ) {

            Column(
                modifier = Modifier.padding(20.dp)
            ) {

                Text(
                    text = "🧠 إدارة الذاكرة",
                    style =
                        MaterialTheme.typography.titleLarge
                )

                Spacer(
                    modifier = Modifier.height(6.dp)
                )

                Text(
                    text =
                        "عرض وإدارة الذكريات المشتركة المحفوظة",
                    style =
                        MaterialTheme.typography.bodyMedium
                )
            }
        }

        Spacer(
            modifier = Modifier.height(16.dp)
        )

        // ============================================================
        // Memory Curator
        // ============================================================

        SectionCard(
            title = "🧠 وسيط الذاكرة الذكي"
        ) {

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment =
                    Alignment.CenterVertically
            ) {

                Column(
                    modifier =
                        Modifier.weight(1f)
                ) {

                    Text(
                        text = "تفعيل الوسيط الذكي",
                        style =
                            MaterialTheme.typography.titleMedium
                    )

                    Text(
                        text =
                            "تحليل الذاكرة قبل تمريرها إلى النموذج الرئيسي",
                        style =
                            MaterialTheme.typography.bodyMedium
                    )
                }

                Switch(
                    checked =
                        memoryCuratorEnabled,

                    onCheckedChange =
                        onMemoryCuratorEnabledChange
                )
            }

            Spacer(
                modifier = Modifier.height(16.dp)
            )

            Text(
                text = "مزود الوسيط",
                style =
                    MaterialTheme.typography.titleMedium
            )

            Spacer(
                modifier = Modifier.height(8.dp)
            )

            ProviderButton(
                selected =
                    memoryCuratorProvider == "gemini",
                text = "Gemini",
                onClick = {
                    onMemoryCuratorProviderChange(
                        "gemini"
                    )
                }
            )

            ProviderButton(
                selected =
                    memoryCuratorProvider == "ollama",
                text = "Ollama",
                onClick = {
                    onMemoryCuratorProviderChange(
                        "ollama"
                    )
                }
            )

            ProviderButton(
                selected =
                    memoryCuratorProvider == "custom",
                text = "Custom",
                onClick = {
                    onMemoryCuratorProviderChange(
                        "custom"
                    )
                }
            )

            Spacer(
                modifier = Modifier.height(12.dp)
            )

            if (memoryCuratorProvider == "gemini") {

                OutlinedTextField(
                    value =
                        memoryCuratorModel,

                    onValueChange =
                        onMemoryCuratorModelChange,

                    modifier =
                        Modifier.fillMaxWidth(),

                    label = {
                        Text("نموذج Gemini للوسيط")
                    },

                    singleLine = true
                )
            }

            if (memoryCuratorProvider == "ollama") {

                OutlinedTextField(
                    value =
                        memoryCuratorOllamaModel,

                    onValueChange =
                        onMemoryCuratorOllamaModelChange,

                    modifier =
                        Modifier.fillMaxWidth(),

                    label = {
                        Text("نموذج Ollama للوسيط")
                    },

                    singleLine = true
                )
            }

            if (memoryCuratorProvider == "custom") {

                Text(
                    text =
                        "سيستخدم الوسيط إعدادات Custom الموجودة في تبويب المحادثة.",
                    style =
                        MaterialTheme.typography.bodyMedium
                )
            }

            Spacer(
                modifier = Modifier.height(16.dp)
            )

            OutlinedTextField(
                value =
                    mediatorIdentityText,

                onValueChange =
                    onMediatorIdentityTextChange,

                modifier =
                    Modifier.fillMaxWidth(),

                label = {
                    Text("هوية / أسلوب الوسيط")
                },

                minLines = 3
            )
        }

        Spacer(
            modifier = Modifier.height(24.dp)
        )
    }
}

// ====================================================================
// 🌐 الويب
// ====================================================================

@Composable
private fun WebSettingsTab(
    onOpenWebPlatforms: () -> Unit
) {

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(
                rememberScrollState()
            )
            .padding(16.dp)
    ) {

        Text(
            text = "🌐 الويب",
            style =
                MaterialTheme.typography.headlineSmall
        )

        Spacer(
            modifier = Modifier.height(16.dp)
        )

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    onOpenWebPlatforms()
                }
        ) {

            Column(
                modifier = Modifier.padding(20.dp)
            ) {

                Text(
                    text = "🌐 منصات الويب",
                    style =
                        MaterialTheme.typography.titleLarge
                )

                Spacer(
                    modifier = Modifier.height(6.dp)
                )

                Text(
                    text =
                        "إدارة منصات الويب وإضافة أو تعطيل أو حذف المنصات",
                    style =
                        MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

// ====================================================================
// 🎯 الدقة
// ====================================================================

@Composable
private fun AccuracySettingsTab(
    accuracyPromptEnabled: Boolean,
    onAccuracyPromptEnabledChange: (Boolean) -> Unit,

    customSystemInstruction: String,
    onCustomSystemInstructionChange: (String) -> Unit
) {

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(
                rememberScrollState()
            )
            .padding(16.dp)
    ) {

        Text(
            text = "🎯 الدقة",
            style =
                MaterialTheme.typography.headlineSmall
        )

        Spacer(
            modifier = Modifier.height(16.dp)
        )

        Card(
            modifier = Modifier.fillMaxWidth()
        ) {

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),

                verticalAlignment =
                    Alignment.CenterVertically
            ) {

                Column(
                    modifier =
                        Modifier.weight(1f)
                ) {

                    Text(
                        text =
                            "تفعيل وثيقة الدقة",

                        style =
                            MaterialTheme.typography.titleMedium
                    )

                    Text(
                        text =
                            "استخدام قواعد الدقة مع المحادثة",

                        style =
                            MaterialTheme.typography.bodyMedium
                    )
                }

                Switch(
                    checked =
                        accuracyPromptEnabled,

                    onCheckedChange =
                        onAccuracyPromptEnabledChange
                )
            }
        }

        Spacer(
            modifier = Modifier.height(16.dp)
        )

        OutlinedTextField(
            value =
                customSystemInstruction,

            onValueChange =
                onCustomSystemInstructionChange,

            modifier =
                Modifier.fillMaxWidth(),

            label = {
                Text("تعليمات النظام المخصصة")
            },

            minLines = 6
        )

        Spacer(
            modifier = Modifier.height(24.dp)
        )
    }
}

// ====================================================================
// ⚙️ عام
// ====================================================================

@Composable
private fun GeneralSettingsTab() {

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(
                rememberScrollState()
            )
            .padding(16.dp)
    ) {

        Text(
            text = "⚙️ عام",
            style =
                MaterialTheme.typography.headlineSmall
        )

        Spacer(
            modifier = Modifier.height(16.dp)
        )

        Card(
            modifier = Modifier.fillMaxWidth()
        ) {

            Column(
                modifier = Modifier.padding(20.dp)
            ) {

                Text(
                    text = "إعدادات عامة",
                    style =
                        MaterialTheme.typography.titleLarge
                )

                Spacer(
                    modifier = Modifier.height(8.dp)
                )

                Text(
                    text =
                        "سيتم إضافة الإعدادات العامة هنا لاحقًا دون التأثير على إعدادات المحادثة أو الذاكرة.",
                    style =
                        MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

// ====================================================================
// عناصر مساعدة
// ====================================================================

@Composable
private fun SectionCard(
    title: String,
    content: @Composable Column.() -> Unit
) {

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {

        Column(
            modifier = Modifier.padding(16.dp)
        ) {

            Text(
                text = title,
                style =
                    MaterialTheme.typography.titleLarge
            )

            Spacer(
                modifier = Modifier.height(12.dp)
            )

            content()
        }
    }
}

@Composable
private fun ProviderButton(
    selected: Boolean,
    text: String,
    onClick: () -> Unit
) {

    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {

        Text(
            text =
                if (selected) {
                    "✓ $text"
                } else {
                    text
                }
        )
    }

    Spacer(
        modifier = Modifier.height(6.dp)
    )
}

@Composable
private fun ProviderFields(
    key: String,
    onKeyChange: (String) -> Unit,

    model: String,
    onModelChange: (String) -> Unit,

    models: List<String>,
    onRefresh: () -> Unit,

    showKeys: Boolean,
    onToggleKeys: () -> Unit
) {

    OutlinedTextField(
        value = key,
        onValueChange = onKeyChange,
        modifier = Modifier.fillMaxWidth(),
        label = {
            Text("API Key")
        },
        singleLine = true,
        visualTransformation =
            if (showKeys) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
        trailingIcon = {

            IconButton(
                onClick = onToggleKeys
            ) {

                Icon(
                    imageVector =
                        if (showKeys) {
                            Icons.Filled.VisibilityOff
                        } else {
                            Icons.Filled.Visibility
                        },
                    contentDescription = null
                )
            }
        }
    )

    Spacer(
        modifier = Modifier.height(12.dp)
    )

    DynamicModelSelector(
        currentModel = model,
        onModelChange = onModelChange,
        models = models,
        onRefresh = onRefresh
    )
}

@Composable
private fun DynamicModelSelector(
    currentModel: String,
    onModelChange: (String) -> Unit,
    models: List<String>,
    onRefresh: () -> Unit
) {

    Column {

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment =
                Alignment.CenterVertically
        ) {

            OutlinedTextField(
                value = currentModel,
                onValueChange = onModelChange,
                modifier =
                    Modifier.weight(1f),
                label = {
                    Text("النموذج")
                },
                singleLine = true
            )

            Spacer(
                modifier = Modifier.width(8.dp)
            )

            IconButton(
                onClick = onRefresh
            ) {

                Icon(
                    imageVector =
                        Icons.Filled.Refresh,
                    contentDescription =
                        "تحديث النماذج"
                )
            }
        }

        if (models.isNotEmpty()) {

            Spacer(
                modifier = Modifier.height(8.dp)
            )

            Text(
                text =
                    "النماذج المتاحة: ${models.size}",

                style =
                    MaterialTheme.typography.bodySmall
            )

            Spacer(
                modifier = Modifier.height(4.dp)
            )

            models.take(10).forEach { model ->

                Text(
                    text = model,

                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onModelChange(model)
                        }
                        .padding(
                            vertical = 8.dp,
                            horizontal = 4.dp
                        ),

                    style =
                        MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}
