package com.example.aichat.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff

import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

import com.example.aichat.data.local.AiSettings
import com.example.aichat.data.model.ModelInfo
import com.example.aichat.repository.ModelCatalogRepository

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalLayoutApi::class
)
@Composable
fun SettingsScreen(
    settings: AiSettings,
    onBack: () -> Unit,
    onOpenMemory: () -> Unit,
    onOpenWebPlatforms: () -> Unit
) {
    /*
     * ============================================================
     * AiChat — Settings
     *
     * مهم:
     * هذه الشاشة تحافظ على آلية تحميل النماذج الديناميكية الأصلية.
     * لا توجد قائمة نماذج ثابتة بديلة.
     * ============================================================
     */

    var selectedTab by remember { mutableIntStateOf(0) }

    // ------------------------------------------------------------
    // Provider
    // ------------------------------------------------------------

    var provider by remember {
        mutableStateOf(settings.provider)
    }

    // ------------------------------------------------------------
    // Provider API keys / models
    // ------------------------------------------------------------

    var geminiKey by remember {
        mutableStateOf(settings.geminiKey)
    }
    var geminiModel by remember {
        mutableStateOf(settings.geminiModel)
    }

    var openrouterKey by remember {
        mutableStateOf(settings.openrouterKey)
    }
    var openrouterModel by remember {
        mutableStateOf(settings.openrouterModel)
    }

    var openaiKey by remember {
        mutableStateOf(settings.openaiKey)
    }
    var openaiModel by remember {
        mutableStateOf(settings.openaiModel)
    }

    var mistralKey by remember {
        mutableStateOf(settings.mistralKey)
    }
    var mistralModel by remember {
        mutableStateOf(settings.mistralModel)
    }

    var groqKey by remember {
        mutableStateOf(settings.groqKey)
    }
    var groqModel by remember {
        mutableStateOf(settings.groqModel)
    }

    var nvidiaKey by remember {
        mutableStateOf(settings.nvidiaKey)
    }
    var nvidiaModel by remember {
        mutableStateOf(settings.nvidiaModel)
    }

    var huggingfaceKey by remember {
        mutableStateOf(settings.huggingfaceKey)
    }
    var huggingfaceModel by remember {
        mutableStateOf(settings.huggingfaceModel)
    }

    var ollamaModel by remember {
        mutableStateOf(settings.ollamaModel)
    }

    var customUrl by remember {
        mutableStateOf(settings.customUrl)
    }
    var customKey by remember {
        mutableStateOf(settings.customKey)
    }
    var customModel by remember {
        mutableStateOf(settings.customModel)
    }

    // ------------------------------------------------------------
    // Memory Curator
    // ------------------------------------------------------------

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

    // ------------------------------------------------------------
    // Accuracy
    // ------------------------------------------------------------

    var accuracyEnabled by remember {
        mutableStateOf(settings.accuracyPromptEnabled)
    }

    var customInstruction by remember {
        mutableStateOf(settings.customSystemInstruction)
    }

    // ------------------------------------------------------------
    // Auto Save
    // ------------------------------------------------------------

    var autoSave by remember {
        mutableStateOf(settings.autoSave)
    }

    // ------------------------------------------------------------
    // UI state
    // ------------------------------------------------------------

    var showKeys by remember {
        mutableStateOf(false)
    }

    var saved by remember {
        mutableStateOf(false)
    }

    // ------------------------------------------------------------
    // Dynamic model catalog
    // ------------------------------------------------------------

    val catalog = remember {
        ModelCatalogRepository(settings)
    }

    var models by remember {
        mutableStateOf<List<ModelInfo>>(emptyList())
    }

    var loadingModels by remember {
        mutableStateOf(false)
    }

    var modelsError by remember {
        mutableStateOf<String?>(null)
    }

    var refreshTrigger by remember {
        mutableIntStateOf(0)
    }

    var onlyFree by remember {
        mutableStateOf(false)
    }

    var onlyRecommended by remember {
        mutableStateOf(false)
    }

    var onlyVision by remember {
        mutableStateOf(false)
    }

    // ------------------------------------------------------------
    // Current model
    // ------------------------------------------------------------

    val currentModel = when (provider) {
        "gemini" -> geminiModel
        "openrouter" -> openrouterModel
        "openai" -> openaiModel
        "mistral" -> mistralModel
        "groq" -> groqModel
        "nvidia" -> nvidiaModel
        "huggingface" -> huggingfaceModel
        "ollama" -> ollamaModel
        "custom" -> customModel
        else -> ""
    }

    // ------------------------------------------------------------
    // Dynamic model loading — ORIGINAL LOGIC
    // ------------------------------------------------------------

    LaunchedEffect(provider, refreshTrigger) {

        if (provider == "custom" || provider == "ollama") {
            models = emptyList()
            loadingModels = false
            modelsError = null
            return@LaunchedEffect
        }

        loadingModels = true
        modelsError = null
        models = emptyList()

        val keyToUse = when (provider) {
            "gemini" -> geminiKey
            "openrouter" -> openrouterKey
            "openai" -> openaiKey
            "mistral" -> mistralKey
            "groq" -> groqKey
            "nvidia" -> nvidiaKey
            "huggingface" -> huggingfaceKey
            else -> ""
        }

        val needsKey = provider != "huggingface"

        if (needsKey && keyToUse.isBlank()) {
            modelsError =
                "أدخل API Key أولاً ثم اضغط تحديث 🔄"
            loadingModels = false
            return@LaunchedEffect
        }

        try {
            models = catalog.getModels(
                provider = provider,
                apiKey = keyToUse
            )
        } catch (e: Exception) {
            modelsError =
                e.message ?: "تعذر تحميل النماذج"
        } finally {
            loadingModels = false
        }
    }

    // ------------------------------------------------------------
    // Keep saved model visible even when catalog changes/fails
    // ------------------------------------------------------------

    val modelsWithSaved = remember(
        models,
        currentModel
    ) {
        if (
            currentModel.isBlank() ||
            models.any { it.id == currentModel }
        ) {
            models
        } else {
            listOf(
                ModelInfo(
                    id = currentModel,
                    name = "$currentModel 💾",
                    provider = provider,
                    isFree = currentModel.contains(":free"),
                    recommended = false
                )
            ) + models
        }
    }

    // ------------------------------------------------------------
    // Filters
    // ------------------------------------------------------------

    val filteredModels = modelsWithSaved
        .filter {
            !onlyFree || it.isFree
        }
        .filter {
            !onlyRecommended || it.recommended
        }
        .filter {
            !onlyVision || it.supportsVision
        }

    // ------------------------------------------------------------
    // Providers
    // ------------------------------------------------------------

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

    // ------------------------------------------------------------
    // Scaffold
    // ------------------------------------------------------------

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
                            imageVector = Icons.Filled.ArrowBack,
                            contentDescription = "رجوع"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor =
                        MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {

            // ====================================================
            // Tabs
            // ====================================================

            ScrollableTabRow(
                selectedTabIndex = selectedTab,
                edgePadding = 8.dp
            ) {

                Tab(
                    selected = selectedTab == 0,
                    onClick = {
                        selectedTab = 0
                    },
                    text = {
                        Text("🤖 المحادثة")
                    }
                )

                Tab(
                    selected = selectedTab == 1,
                    onClick = {
                        selectedTab = 1
                    },
                    text = {
                        Text("🧠 الذاكرة")
                    }
                )

                Tab(
                    selected = selectedTab == 2,
                    onClick = {
                        selectedTab = 2
                    },
                    text = {
                        Text("🌐 الويب")
                    }
                )

                Tab(
                    selected = selectedTab == 3,
                    onClick = {
                        selectedTab = 3
                    },
                    text = {
                        Text("🎯 الدقة")
                    }
                )

                Tab(
                    selected = selectedTab == 4,
                    onClick = {
                        selectedTab = 4
                    },
                    text = {
                        Text("⚙️ عام")
                    }
                )
            }

            // ====================================================
            // Tab content
            // ====================================================

            when (selectedTab) {

                // =================================================
                // 0 — CHAT
                // =================================================

                0 -> {

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(
                                rememberScrollState()
                            )
                            .padding(16.dp),
                        verticalArrangement =
                            Arrangement.spacedBy(16.dp)
                    ) {

                        SectionTitle(
                            title = "🤖 مزود الذكاء الاصطناعي",
                            subtitle =
                                "اختر المزود والنموذج المستخدم للمحادثة."
                        )

                        // -----------------------------------------
                        // Provider selection
                        // -----------------------------------------

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {

                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement =
                                    Arrangement.spacedBy(4.dp)
                            ) {

                                providers.forEach { (key, label) ->

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {

                                                if (provider != key) {

                                                    provider = key
                                                    saved = false

                                                    onlyFree = false
                                                    onlyRecommended = false
                                                    onlyVision = false

                                                    models = emptyList()
                                                    modelsError = null
                                                }
                                            }
                                            .padding(
                                                vertical = 4.dp
                                            ),
                                        verticalAlignment =
                                            Alignment.CenterVertically
                                    ) {

                                        RadioButton(
                                            selected =
                                                provider == key,
                                            onClick = {

                                                if (provider != key) {

                                                    provider = key
                                                    saved = false

                                                    onlyFree = false
                                                    onlyRecommended = false
                                                    onlyVision = false

                                                    models = emptyList()
                                                    modelsError = null
                                                }
                                            }
                                        )

                                        Text(
                                            text = label,
                                            modifier =
                                                Modifier.padding(
                                                    start = 4.dp
                                                )
                                        )
                                    }
                                }
                            }
                        }

                        // -----------------------------------------
                        // API key
                        // -----------------------------------------

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment =
                                Alignment.CenterVertically
                        ) {

                            Text(
                                text = "🔑 مفتاح API",
                                style =
                                    MaterialTheme.typography.titleMedium,
                                fontWeight =
                                    FontWeight.Bold,
                                modifier =
                                    Modifier.weight(1f)
                            )

                            IconButton(
                                onClick = {
                                    showKeys = !showKeys
                                }
                            ) {
                                Icon(
                                    imageVector =
                                        if (showKeys)
                                            Icons.Filled.VisibilityOff
                                        else
                                            Icons.Filled.Visibility,
                                    contentDescription =
                                        if (showKeys)
                                            "إخفاء المفاتيح"
                                        else
                                            "إظهار المفاتيح"
                                )
                            }
                        }

                        // -----------------------------------------
                        // Provider-specific settings
                        // -----------------------------------------

                        when (provider) {

                            // =====================================
                            // GEMINI
                            // =====================================

                            "gemini" -> {

                                InfoCard(
                                    "Google Gemini\n\n" +
                                            "احصل على مفتاح API من Google AI Studio."
                                )

                                KeyField(
                                    label = "Gemini API Key",
                                    value = geminiKey,
                                    onValueChange = {
                                        geminiKey = it
                                        saved = false
                                    },
                                    showKey = showKeys,
                                    placeholder = "أدخل مفتاح Gemini API"
                                )

                                DynamicModelSelector(
                                    models = filteredModels,
                                    selected = geminiModel,
                                    loading = loadingModels,
                                    error = modelsError,
                                    onlyFree = onlyFree,
                                    onlyRecommended =
                                        onlyRecommended,
                                    onlyVision = onlyVision,
                                    onFreeChange = {
                                        onlyFree = it
                                    },
                                    onRecommendedChange = {
                                        onlyRecommended = it
                                    },
                                    onVisionChange = {
                                        onlyVision = it
                                    },
                                    onSelect = {
                                        geminiModel = it
                                        saved = false
                                    },
                                    onRefresh = {
                                        refreshTrigger++
                                    }
                                )
                            }

                            // =====================================
                            // OPENROUTER
                            // =====================================

                            "openrouter" -> {

                                InfoCard(
                                    "OpenRouter\n\n" +
                                            "يوفر الوصول إلى عدد كبير من النماذج من خلال API واحد."
                                )

                                KeyField(
                                    label = "OpenRouter API Key",
                                    value = openrouterKey,
                                    onValueChange = {
                                        openrouterKey = it
                                        saved = false
                                    },
                                    showKey = showKeys,
                                    placeholder =
                                        "أدخل مفتاح OpenRouter API"
                                )

                                DynamicModelSelector(
                                    models = filteredModels,
                                    selected = openrouterModel,
                                    loading = loadingModels,
                                    error = modelsError,
                                    onlyFree = onlyFree,
                                    onlyRecommended =
                                        onlyRecommended,
                                    onlyVision = onlyVision,
                                    onFreeChange = {
                                        onlyFree = it
                                    },
                                    onRecommendedChange = {
                                        onlyRecommended = it
                                    },
                                    onVisionChange = {
                                        onlyVision = it
                                    },
                                    onSelect = {
                                        openrouterModel = it
                                        saved = false
                                    },
                                    onRefresh = {
                                        refreshTrigger++
                                    }
                                )

                                OutlinedTextField(
                                    value = openrouterModel,
                                    onValueChange = {
                                        openrouterModel = it
                                        saved = false
                                    },
                                    modifier =
                                        Modifier.fillMaxWidth(),
                                    label = {
                                        Text("النموذج يدويًا")
                                    },
                                    singleLine = true,
                                    shape =
                                        RoundedCornerShape(12.dp)
                                )
                            }

                            // =====================================
                            // OPENAI
                            // =====================================

                            "openai" -> {

                                InfoCard(
                                    "OpenAI\n\n" +
                                            "أدخل مفتاح OpenAI API ثم حمّل النماذج المتاحة."
                                )

                                KeyField(
                                    label = "OpenAI API Key",
                                    value = openaiKey,
                                    onValueChange = {
                                        openaiKey = it
                                        saved = false
                                    },
                                    showKey = showKeys,
                                    placeholder =
                                        "أدخل مفتاح OpenAI API"
                                )

                                DynamicModelSelector(
                                    models = filteredModels,
                                    selected = openaiModel,
                                    loading = loadingModels,
                                    error = modelsError,
                                    onlyFree = onlyFree,
                                    onlyRecommended =
                                        onlyRecommended,
                                    onlyVision = onlyVision,
                                    onFreeChange = {
                                        onlyFree = it
                                    },
                                    onRecommendedChange = {
                                        onlyRecommended = it
                                    },
                                    onVisionChange = {
                                        onlyVision = it
                                    },
                                    onSelect = {
                                        openaiModel = it
                                        saved = false
                                    },
                                    onRefresh = {
                                        refreshTrigger++
                                    }
                                )
                            }

                            // =====================================
                            // MISTRAL
                            // =====================================

                            "mistral" -> {

                                InfoCard(
                                    "Mistral AI\n\n" +
                                            "يمكن الحصول على مفتاح API من console.mistral.ai.\n" +
                                            "يدعم المشروع نماذج Mistral المتاحة من الكتالوج."
                                )

                                KeyField(
                                    label = "Mistral API Key",
                                    value = mistralKey,
                                    onValueChange = {
                                        mistralKey = it
                                        saved = false
                                    },
                                    showKey = showKeys,
                                    placeholder =
                                        "أدخل مفتاح Mistral API"
                                )

                                DynamicModelSelector(
                                    models = filteredModels,
                                    selected = mistralModel,
                                    loading = loadingModels,
                                    error = modelsError,
                                    onlyFree = onlyFree,
                                    onlyRecommended =
                                        onlyRecommended,
                                    onlyVision = onlyVision,
                                    onFreeChange = {
                                        onlyFree = it
                                    },
                                    onRecommendedChange = {
                                        onlyRecommended = it
                                    },
                                    onVisionChange = {
                                        onlyVision = it
                                    },
                                    onSelect = {
                                        mistralModel = it
                                        saved = false
                                    },
                                    onRefresh = {
                                        refreshTrigger++
                                    }
                                )
                            }

                            // =====================================
                            // GROQ
                            // =====================================

                            "groq" -> {

                                InfoCard(
                                    "Groq\n\n" +
                                            "يتميز بسرعة الاستجابة، ويمكن تحميل النماذج المتاحة مباشرة."
                                )

                                KeyField(
                                    label = "Groq API Key",
                                    value = groqKey,
                                    onValueChange = {
                                        groqKey = it
                                        saved = false
                                    },
                                    showKey = showKeys,
                                    placeholder =
                                        "أدخل مفتاح Groq API"
                                )

                                DynamicModelSelector(
                                    models = filteredModels,
                                    selected = groqModel,
                                    loading = loadingModels,
                                    error = modelsError,
                                    onlyFree = onlyFree,
                                    onlyRecommended =
                                        onlyRecommended,
                                    onlyVision = onlyVision,
                                    onFreeChange = {
                                        onlyFree = it
                                    },
                                    onRecommendedChange = {
                                        onlyRecommended = it
                                    },
                                    onVisionChange = {
                                        onlyVision = it
                                    },
                                    onSelect = {
                                        groqModel = it
                                        saved = false
                                    },
                                    onRefresh = {
                                        refreshTrigger++
                                    }
                                )

                                OutlinedTextField(
                                    value = groqModel,
                                    onValueChange = {
                                        groqModel = it
                                        saved = false
                                    },
                                    modifier =
                                        Modifier.fillMaxWidth(),
                                    label = {
                                        Text("النموذج يدويًا")
                                    },
                                    singleLine = true,
                                    shape =
                                        RoundedCornerShape(12.dp)
                                )
                            }

                            // =====================================
                            // NVIDIA
                            // =====================================

                            "nvidia" -> {

                                InfoCard(
                                    "NVIDIA NIM\n\n" +
                                            "يمكن تحميل النماذج المتاحة من NVIDIA NIM."
                                )

                                KeyField(
                                    label = "NVIDIA API Key",
                                    value = nvidiaKey,
                                    onValueChange = {
                                        nvidiaKey = it
                                        saved = false
                                    },
                                    showKey = showKeys,
                                    placeholder =
                                        "أدخل مفتاح NVIDIA API"
                                )

                                DynamicModelSelector(
                                    models = filteredModels,
                                    selected = nvidiaModel,
                                    loading = loadingModels,
                                    error = modelsError,
                                    onlyFree = onlyFree,
                                    onlyRecommended =
                                        onlyRecommended,
                                    onlyVision = onlyVision,
                                    onFreeChange = {
                                        onlyFree = it
                                    },
                                    onRecommendedChange = {
                                        onlyRecommended = it
                                    },
                                    onVisionChange = {
                                        onlyVision = it
                                    },
                                    onSelect = {
                                        nvidiaModel = it
                                        saved = false
                                    },
                                    onRefresh = {
                                        refreshTrigger++
                                    }
                                )

                                OutlinedTextField(
                                    value = nvidiaModel,
                                    onValueChange = {
                                        nvidiaModel = it
                                        saved = false
                                    },
                                    modifier =
                                        Modifier.fillMaxWidth(),
                                    label = {
                                        Text("النموذج يدويًا")
                                    },
                                    singleLine = true,
                                    shape =
                                        RoundedCornerShape(12.dp)
                                )
                            }

                            // =====================================
                            // HUGGING FACE
                            // =====================================

                            "huggingface" -> {

                                InfoCard(
                                    "Hugging Face 🤗\n\n" +
                                            "يمكن تحميل النماذج المتاحة من Hugging Face."
                                )

                                KeyField(
                                    label = "Hugging Face API Key",
                                    value = huggingfaceKey,
                                    onValueChange = {
                                        huggingfaceKey = it
                                        saved = false
                                    },
                                    showKey = showKeys,
                                    placeholder =
                                        "أدخل مفتاح Hugging Face إن وجد"
                                )

                                DynamicModelSelector(
                                    models = filteredModels,
                                    selected = huggingfaceModel,
                                    loading = loadingModels,
                                    error = modelsError,
                                    onlyFree = onlyFree,
                                    onlyRecommended =
                                        onlyRecommended,
                                    onlyVision = onlyVision,
                                    onFreeChange = {
                                        onlyFree = it
                                    },
                                    onRecommendedChange = {
                                        onlyRecommended = it
                                    },
                                    onVisionChange = {
                                        onlyVision = it
                                    },
                                    onSelect = {
                                        huggingfaceModel = it
                                        saved = false
                                    },
                                    onRefresh = {
                                        refreshTrigger++
                                    }
                                )

                                OutlinedTextField(
                                    value = huggingfaceModel,
                                    onValueChange = {
                                        huggingfaceModel = it
                                        saved = false
                                    },
                                    modifier =
                                        Modifier.fillMaxWidth(),
                                    label = {
                                        Text("النموذج يدويًا")
                                    },
                                    singleLine = true,
                                    shape =
                                        RoundedCornerShape(12.dp)
                                )
                            }

                            // =====================================
                            // OLLAMA
                            // =====================================

                            "ollama" -> {

                                InfoCard(
                                    "Ollama 🦙 (محلي)\n\n" +
                                            "يعمل النموذج محليًا على الجهاز أو الشبكة المحلية.\n\n" +
                                            "العنوان الافتراضي:\n" +
                                            "127.0.0.1:11434\n\n" +
                                            "في Termux شغّل:\n" +
                                            "ollama serve\n\n" +
                                            "ثم حمّل نموذجًا مثل:\n" +
                                            "ollama pull qwen2.5:1.5b\n\n" +
                                            "لا يحتاج Ollama إلى API Key أو اتصال مباشر بالإنترنت أثناء الاستخدام."
                                )

                                OutlinedTextField(
                                    value = ollamaModel,
                                    onValueChange = {
                                        ollamaModel = it
                                        saved = false
                                    },
                                    modifier =
                                        Modifier.fillMaxWidth(),
                                    label = {
                                        Text("نموذج Ollama")
                                    },
                                    singleLine = true,
                                    leadingIcon = {
                                        Icon(
                                            imageVector =
                                                Icons.Filled.Computer,
                                            contentDescription = null
                                        )
                                    },
                                    shape =
                                        RoundedCornerShape(12.dp)
                                )

                                Text(
                                    text = "نماذج مقترحة: qwen2.5:0.5b • qwen2.5:1.5b • qwen2.5:3b • gemma2:2b",
                                    style =
                                        MaterialTheme.typography.bodySmall,
                                    color =
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            // =====================================
                            // CUSTOM
                            // =====================================

                            "custom" -> {

                                InfoCard(
                                    "Custom API\n\n" +
                                            "استخدم خادم API متوافقًا مع واجهة المشروع."
                                )

                                OutlinedTextField(
                                    value = customUrl,
                                    onValueChange = {
                                        customUrl = it
                                        saved = false
                                    },
                                    modifier =
                                        Modifier.fillMaxWidth(),
                                    label = {
                                        Text("Server URL")
                                    },
                                    singleLine = true,
                                    placeholder = {
                                        Text("https://example.com")
                                    },
                                    shape =
                                        RoundedCornerShape(12.dp)
                                )

                                KeyField(
                                    label = "API Key",
                                    value = customKey,
                                    onValueChange = {
                                        customKey = it
                                        saved = false
                                    },
                                    showKey = showKeys,
                                    placeholder =
                                        "مفتاح API"
                                )

                                OutlinedTextField(
                                    value = customModel,
                                    onValueChange = {
                                        customModel = it
                                        saved = false
                                    },
                                    modifier =
                                        Modifier.fillMaxWidth(),
                                    label = {
                                        Text("Model")
                                    },
                                    singleLine = true,
                                    shape =
                                        RoundedCornerShape(12.dp)
                                )
                            }
                        }

                        HorizontalDivider()

                        // -----------------------------------------
                        // Save
                        // -----------------------------------------

                        Button(
                            onClick = {

                                settings.provider = provider

                                settings.geminiKey = geminiKey
                                settings.geminiModel = geminiModel

                                settings.openrouterKey =
                                    openrouterKey
                                settings.openrouterModel =
                                    openrouterModel

                                settings.openaiKey = openaiKey
                                settings.openaiModel =
                                    openaiModel

                                settings.mistralKey =
                                    mistralKey
                                settings.mistralModel =
                                    mistralModel

                                settings.groqKey = groqKey
                                settings.groqModel = groqModel

                                settings.nvidiaKey = nvidiaKey
                                settings.nvidiaModel =
                                    nvidiaModel

                                settings.huggingfaceKey =
                                    huggingfaceKey
                                settings.huggingfaceModel =
                                    huggingfaceModel

                                settings.ollamaModel =
                                    ollamaModel

                                settings.customUrl = customUrl
                                settings.customKey = customKey
                                settings.customModel =
                                    customModel

                                settings.accuracyPromptEnabled =
                                    accuracyEnabled
                                settings.customSystemInstruction =
                                    customInstruction

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

                                settings.autoSave = autoSave

                                saved = true
                            },
                            modifier =
                                Modifier.fillMaxWidth()
                        ) {

                            Icon(
                                imageVector =
                                    Icons.Filled.Save,
                                contentDescription = null
                            )

                            Spacer(
                                Modifier.width(8.dp)
                            )

                            Text("حفظ الإعدادات")
                        }

                        if (saved) {

                            SavedCard()
                        }
                    }
                }

                // =================================================
                // 1 — MEMORY
                // =================================================

                1 -> {

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(
                                rememberScrollState()
                            )
                            .padding(16.dp),
                        verticalArrangement =
                            Arrangement.spacedBy(16.dp)
                    ) {

                        SectionTitle(
                            title = "🧠 الذاكرة",
                            subtitle =
                                "إدارة الذكريات والحفظ التلقائي ووسيط الذاكرة."
                        )

                        // -----------------------------------------
                        // Memory management
                        // -----------------------------------------

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onOpenMemory()
                                },
                            shape =
                                RoundedCornerShape(12.dp),
                            colors =
                                CardDefaults.cardColors(
                                    containerColor =
                                        MaterialTheme
                                            .colorScheme
                                            .secondaryContainer
                                )
                        ) {

                            Row(
                                modifier =
                                    Modifier.padding(16.dp),
                                verticalAlignment =
                                    Alignment.CenterVertically
                            ) {

                                Icon(
                                    imageVector =
                                        Icons.Filled.Memory,
                                    contentDescription = null,
                                    modifier =
                                        Modifier.size(32.dp)
                                )

                                Spacer(
                                    Modifier.width(12.dp)
                                )

                                Column(
                                    modifier =
                                        Modifier.weight(1f)
                                ) {

                                    Text(
                                        text = "الذكريات",
                                        fontWeight =
                                            FontWeight.Bold
                                    )

                                    Text(
                                        text =
                                            "عرض وتعديل وحذف الذكريات المحفوظة.",
                                        style =
                                            MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }

                        // -----------------------------------------
                        // Auto Save
                        // -----------------------------------------

                        Card(
                            modifier =
                                Modifier.fillMaxWidth(),
                            shape =
                                RoundedCornerShape(12.dp)
                        ) {

                            Row(
                                modifier =
                                    Modifier
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
                                        fontWeight =
                                            FontWeight.Bold
                                    )

                                    Spacer(
                                        Modifier.height(4.dp)
                                    )

                                    Text(
                                        text =
                                            "عند التفعيل، يمكن لـ AiChat حفظ الردود المهمة تلقائيًا في الذاكرة بدل الضغط على زر الحفظ كل مرة.",
                                        style =
                                            MaterialTheme.typography.bodySmall,
                                        color =
                                            MaterialTheme.colorScheme
                                                .onSurfaceVariant
                                    )
                                }

                                Switch(
                                    checked = autoSave,
                                    onCheckedChange = {
                                        autoSave = it
                                        saved = false
                                    }
                                )
                            }
                        }

                        // -----------------------------------------
                        // Memory Curator
                        // -----------------------------------------

                        Card(
                            modifier =
                                Modifier.fillMaxWidth(),
                            shape =
                                RoundedCornerShape(12.dp)
                        ) {

                            Column(
                                modifier =
                                    Modifier.padding(16.dp),
                                verticalArrangement =
                                    Arrangement.spacedBy(12.dp)
                            ) {

                                Row(
                                    modifier =
                                        Modifier.fillMaxWidth(),
                                    verticalAlignment =
                                        Alignment.CenterVertically
                                ) {

                                    Column(
                                        modifier =
                                            Modifier.weight(1f)
                                    ) {

                                        Text(
                                            text =
                                                "🧠 وسيط الذاكرة الذكي",
                                            fontWeight =
                                                FontWeight.Bold,
                                            style =
                                                MaterialTheme.typography
                                                    .titleMedium
                                        )

                                        Text(
                                            text =
                                                "يحلل الذاكرة قبل إرسالها إلى النموذج الرئيسي.",
                                            style =
                                                MaterialTheme.typography
                                                    .bodySmall
                                        )
                                    }

                                    Switch(
                                        checked =
                                            memoryCuratorEnabled,
                                        onCheckedChange = {
                                            memoryCuratorEnabled = it
                                            saved = false
                                        }
                                    )
                                }

                                Text(
                                    text =
                                        "يمكن للوسيط اقتراح النقاط المهمة من الذاكرة. ويمكن كتابة هوية الوسيط إذا أردت تحديد دوره وطريقة تحليله.",
                                    style =
                                        MaterialTheme.typography.bodySmall,
                                    color =
                                        MaterialTheme.colorScheme
                                            .onSurfaceVariant
                                )

                                if (memoryCuratorEnabled) {

                                    Text(
                                        text =
                                            "مزود الوسيط",
                                        fontWeight =
                                            FontWeight.Bold
                                    )

                                    Row(
                                        modifier =
                                            Modifier.fillMaxWidth(),
                                        verticalAlignment =
                                            Alignment.CenterVertically
                                    ) {

                                        RadioButton(
                                            selected =
                                                memoryCuratorProvider ==
                                                        "gemini",
                                            onClick = {
                                                memoryCuratorProvider =
                                                    "gemini"
                                                saved = false
                                            }
                                        )

                                        Text("Gemini")

                                        Spacer(
                                            Modifier.width(12.dp)
                                        )

                                        RadioButton(
                                            selected =
                                                memoryCuratorProvider ==
                                                        "ollama",
                                            onClick = {
                                                memoryCuratorProvider =
                                                    "ollama"
                                                saved = false
                                            }
                                        )

                                        Text("Ollama")

                                        Spacer(
                                            Modifier.width(12.dp)
                                        )

                                        RadioButton(
                                            selected =
                                                memoryCuratorProvider ==
                                                        "custom",
                                            onClick = {
                                                memoryCuratorProvider =
                                                    "custom"
                                                saved = false
                                            }
                                        )

                                        Text("Custom")
                                    }

                                    when (memoryCuratorProvider) {

                                        // =========================
                                        // Curator Gemini
                                        // =========================

                                        "gemini" -> {

                                            OutlinedTextField(
                                                value =
                                                    memoryCuratorModel,
                                                onValueChange = {
                                                    memoryCuratorModel =
                                                        it
                                                    saved = false
                                                },
                                                modifier =
                                                    Modifier.fillMaxWidth(),
                                                label = {
                                                    Text(
                                                        "نموذج Gemini للوسيط"
                                                    )
                                                },
                                                singleLine = true,
                                                shape =
                                                    RoundedCornerShape(
                                                        12.dp
                                                    )
                                            )

                                            InfoCard(
                                                "سيستخدم الوسيط مفتاح Gemini الموجود في إعدادات المحادثة أعلاه."
                                            )
                                        }

                                        // =========================
                                        // Curator Ollama
                                        // =========================

                                        "ollama" -> {

                                            OutlinedTextField(
                                                value =
                                                    memoryCuratorOllamaModel,
                                                onValueChange = {
                                                    memoryCuratorOllamaModel =
                                                        it
                                                    saved = false
                                                },
                                                modifier =
                                                    Modifier.fillMaxWidth(),
                                                label = {
                                                    Text(
                                                        "نموذج Ollama للوسيط"
                                                    )
                                                },
                                                singleLine = true,
                                                leadingIcon = {
                                                    Icon(
                                                        imageVector =
                                                            Icons.Filled.Computer,
                                                        contentDescription =
                                                            null
                                                    )
                                                },
                                                shape =
                                                    RoundedCornerShape(
                                                        12.dp
                                                    )
                                            )

                                            InfoCard(
                                                "متطلبات Ollama:\n\n" +
                                                        "127.0.0.1:11434\n\n" +
                                                        "في Termux:\n" +
                                                        "ollama serve\n\n" +
                                                        "ثم:\n" +
                                                        "ollama pull qwen2.5:1.5b\n\n" +
                                                        "لا يحتاج API Key ولا اتصالًا بالإنترنت أثناء الاستخدام."
                                            )

                                            Text(
                                                text =
                                                    "نماذج مقترحة: qwen2.5:0.5b • qwen2.5:1.5b • qwen2.5:3b • gemma2:2b",
                                                style =
                                                    MaterialTheme.typography
                                                        .bodySmall
                                            )
                                        }

                                        // =========================
                                        // Curator Custom
                                        // =========================

                                        "custom" -> {

                                            InfoCard(
                                                "سيستخدم الوسيط إعدادات Custom الموجودة في تبويب المحادثة."
                                            )

                                            if (
                                                customUrl.isBlank() ||
                                                customModel.isBlank()
                                            ) {

                                                Card(
                                                    modifier =
                                                        Modifier.fillMaxWidth(),
                                                    colors =
                                                        CardDefaults
                                                            .cardColors(
                                                                containerColor =
                                                                    MaterialTheme
                                                                        .colorScheme
                                                                        .errorContainer
                                                            ),
                                                    shape =
                                                        RoundedCornerShape(
                                                            8.dp
                                                        )
                                                ) {

                                                    Text(
                                                        text =
                                                            "⚠️ يجب تحديد Server URL والنموذج في إعدادات Custom.",
                                                        modifier =
                                                            Modifier.padding(
                                                                12.dp
                                                            ),
                                                        style =
                                                            MaterialTheme
                                                                .typography
                                                                .bodySmall,
                                                        color =
                                                            MaterialTheme
                                                                .colorScheme
                                                                .onErrorContainer
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    OutlinedTextField(
                                        value =
                                            mediatorIdentityText,
                                        onValueChange = {
                                            mediatorIdentityText =
                                                it
                                            saved = false
                                        },
                                        modifier =
                                            Modifier.fillMaxWidth(),
                                        label = {
                                            Text(
                                                "هوية وسيط الذاكرة — اختياري"
                                            )
                                        },
                                        placeholder = {
                                            Text(
                                                "مثلاً: أنت وسيط متخصص في تحليل الذاكرة..."
                                            )
                                        },
                                        minLines = 3,
                                        shape =
                                            RoundedCornerShape(
                                                12.dp
                                            )
                                    )
                                }
                            }
                        }

                        // -----------------------------------------
                        // Save
                        // -----------------------------------------

                        Button(
                            onClick = {

                                settings.provider = provider

                                settings.geminiKey = geminiKey
                                settings.geminiModel = geminiModel

                                settings.openrouterKey =
                                    openrouterKey
                                settings.openrouterModel =
                                    openrouterModel

                                settings.openaiKey = openaiKey
                                settings.openaiModel =
                                    openaiModel

                                settings.mistralKey =
                                    mistralKey
                                settings.mistralModel =
                                    mistralModel

                                settings.groqKey = groqKey
                                settings.groqModel = groqModel

                                settings.nvidiaKey = nvidiaKey
                                settings.nvidiaModel =
                                    nvidiaModel

                                settings.huggingfaceKey =
                                    huggingfaceKey
                                settings.huggingfaceModel =
                                    huggingfaceModel

                                settings.ollamaModel =
                                    ollamaModel

                                settings.customUrl = customUrl
                                settings.customKey = customKey
                                settings.customModel =
                                    customModel

                                settings.accuracyPromptEnabled =
                                    accuracyEnabled
                                settings.customSystemInstruction =
                                    customInstruction

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

                                settings.autoSave = autoSave

                                saved = true
                            },
                            modifier =
                                Modifier.fillMaxWidth()
                        ) {

                            Icon(
                                imageVector =
                                    Icons.Filled.Save,
                                contentDescription = null
                            )

                            Spacer(
                                Modifier.width(8.dp)
                            )

                            Text("حفظ الإعدادات")
                        }

                        if (saved) {
                            SavedCard()
                        }
                    }
                }

                // =================================================
                // 2 — WEB
                // =================================================

                2 -> {

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(
                                rememberScrollState()
                            )
                            .padding(16.dp),
                        verticalArrangement =
                            Arrangement.spacedBy(16.dp)
                    ) {

                        SectionTitle(
                            title = "🌐 الويب",
                            subtitle =
                                "إدارة منصات الويب التي يتعامل معها AiChat."
                        )

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onOpenWebPlatforms()
                                },
                            shape =
                                RoundedCornerShape(12.dp),
                            colors =
                                CardDefaults.cardColors(
                                    containerColor =
                                        MaterialTheme
                                            .colorScheme
                                            .secondaryContainer
                                )
                        ) {

                            Row(
                                modifier =
                                    Modifier.padding(16.dp),
                                verticalAlignment =
                                    Alignment.CenterVertically
                            ) {

                                Text(
                                    text = "🌐",
                                    style =
                                        MaterialTheme.typography
                                            .headlineMedium
                                )

                                Spacer(
                                    Modifier.width(12.dp)
                                )

                                Column(
                                    modifier =
                                        Modifier.weight(1f)
                                ) {

                                    Text(
                                        text =
                                            "منصات الويب",
                                        fontWeight =
                                            FontWeight.Bold,
                                        style =
                                            MaterialTheme.typography
                                                .titleMedium
                                    )

                                    Text(
                                        text =
                                            "إدارة منصات الويب وإعداداتها.",
                                        style =
                                            MaterialTheme.typography
                                                .bodySmall
                                    )
                                }
                            }
                        }

                        InfoCard(
                            "هذه الصفحة تفتح شاشة إدارة المنصات الموجودة في المشروع. لن يتم تكرار قائمة المنصات هنا."
                        )
                    }
                }

                // =================================================
                // 3 — ACCURACY
                // =================================================

                3 -> {

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(
                                rememberScrollState()
                            )
                            .padding(16.dp),
                        verticalArrangement =
                            Arrangement.spacedBy(16.dp)
                    ) {

                        SectionTitle(
                            title = "🎯 الدقة",
                            subtitle =
                                "قواعد إضافية لتحسين دقة وموثوقية الإجابات."
                        )

                        Card(
                            modifier =
                                Modifier.fillMaxWidth(),
                            shape =
                                RoundedCornerShape(12.dp)
                        ) {

                            Column(
                                modifier =
                                    Modifier.padding(16.dp),
                                verticalArrangement =
                                    Arrangement.spacedBy(12.dp)
                            ) {

                                Row(
                                    modifier =
                                        Modifier.fillMaxWidth(),
                                    verticalAlignment =
                                        Alignment.CenterVertically
                                ) {

                                    Column(
                                        modifier =
                                            Modifier.weight(1f)
                                    ) {

                                        Text(
                                            text =
                                                "🎯 تفعيل وثيقة الدقة",
                                            fontWeight =
                                                FontWeight.Bold,
                                            style =
                                                MaterialTheme.typography
                                                    .titleMedium
                                        )

                                        Text(
                                            text =
                                                "إضافة قواعد الدقة والموثوقية إلى تعليمات النموذج.",
                                            style =
                                                MaterialTheme.typography
                                                    .bodySmall
                                        )
                                    }

                                    Switch(
                                        checked =
                                            accuracyEnabled,
                                        onCheckedChange = {

                                            accuracyEnabled = it

                                            settings
                                                .accuracyPromptEnabled =
                                                it

                                            saved = false
                                        }
                                    )
                                }

                                OutlinedTextField(
                                    value =
                                        customInstruction,
                                    onValueChange = {
                                        customInstruction = it
                                        saved = false
                                    },
                                    modifier =
                                        Modifier.fillMaxWidth(),
                                    label = {
                                        Text(
                                            "تعليمات إضافية مخصصة"
                                        )
                                    },
                                    placeholder = {
                                        Text(
                                            "أضف تعليمات خاصة تريد تطبيقها على الإجابات..."
                                        )
                                    },
                                    minLines = 5,
                                    shape =
                                        RoundedCornerShape(12.dp)
                                )
                            }
                        }

                        Button(
                            onClick = {

                                settings.accuracyPromptEnabled =
                                    accuracyEnabled

                                settings.customSystemInstruction =
                                    customInstruction

                                settings.autoSave = autoSave

                                saved = true
                            },
                            modifier =
                                Modifier.fillMaxWidth()
                        ) {

                            Icon(
                                imageVector =
                                    Icons.Filled.Save,
                                contentDescription = null
                            )

                            Spacer(
                                Modifier.width(8.dp)
                            )

                            Text("حفظ الإعدادات")
                        }

                        if (saved) {
                            SavedCard()
                        }
                    }
                }

                // =================================================
                // 4 — GENERAL
                // =================================================

                4 -> {

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(
                                rememberScrollState()
                            )
                            .padding(16.dp),
                        verticalArrangement =
                            Arrangement.spacedBy(16.dp)
                    ) {

                        SectionTitle(
                            title = "⚙️ عام",
                            subtitle =
                                "إعدادات عامة ستتم إضافتها هنا مستقبلًا."
                        )

                        InfoCard(
                            "لا توجد إعدادات عامة إضافية حاليًا.\n\n" +
                                    "تم ترك هذا التبويب جاهزًا للتوسعة دون تغيير بنية المشروع."
                        )
                    }
                }
            }
        }
    }
}

// =================================================================
// Dynamic Model Selector
// =================================================================

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalLayoutApi::class
)
@Composable
private fun DynamicModelSelector(
    models: List<ModelInfo>,
    selected: String,
    loading: Boolean,
    error: String?,
    onlyFree: Boolean,
    onlyRecommended: Boolean,
    onlyVision: Boolean,
    onFreeChange: (Boolean) -> Unit,
    onRecommendedChange: (Boolean) -> Unit,
    onVisionChange: (Boolean) -> Unit,
    onSelect: (String) -> Unit,
    onRefresh: () -> Unit
) {

    var expanded by remember {
        mutableStateOf(false)
    }

    val selectedInfo =
        models.firstOrNull {
            it.id == selected
        }

    Column(
        verticalArrangement =
            Arrangement.spacedBy(8.dp)
    ) {

        Row(
            modifier =
                Modifier.fillMaxWidth(),
            verticalAlignment =
                Alignment.CenterVertically
        ) {

            Text(
                text = "النموذج",
                fontWeight =
                    FontWeight.Bold,
                modifier =
                    Modifier.weight(1f)
            )

            if (loading) {

                CircularProgressIndicator(
                    modifier =
                        Modifier.size(20.dp),
                    strokeWidth = 2.dp
                )

                Spacer(
                    Modifier.width(8.dp)
                )

                Text(
                    text = "جاري التحميل...",
                    style =
                        MaterialTheme.typography.bodySmall,
                    color =
                        MaterialTheme.colorScheme
                            .onSurfaceVariant
                )

            } else {

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

                Text(
                    text =
                        "${models.size} نموذج",
                    style =
                        MaterialTheme.typography.bodySmall,
                    color =
                        MaterialTheme.colorScheme
                            .onSurfaceVariant
                )
            }
        }

        FlowRow(
            horizontalArrangement =
                Arrangement.spacedBy(6.dp)
        ) {

            FilterChip(
                selected = onlyFree,
                onClick = {
                    onFreeChange(!onlyFree)
                },
                label = {
                    Text("🟢 مجاني")
                }
            )

            FilterChip(
                selected =
                    onlyRecommended,
                onClick = {
                    onRecommendedChange(
                        !onlyRecommended
                    )
                },
                label = {
                    Text("⭐ موصى به")
                }
            )

            FilterChip(
                selected = onlyVision,
                onClick = {
                    onVisionChange(!onlyVision)
                },
                label = {
                    Text("🖼️ يدعم الصور")
                }
            )
        }

        if (!error.isNullOrBlank()) {

            Card(
                modifier =
                    Modifier.fillMaxWidth(),
                colors =
                    CardDefaults.cardColors(
                        containerColor =
                            MaterialTheme.colorScheme
                                .errorContainer
                    ),
                shape =
                    RoundedCornerShape(8.dp)
            ) {

                Text(
                    text =
                        "⚠️ $error\nسيتم الاحتفاظ بالنموذج المحفوظ.",
                    modifier =
                        Modifier.padding(12.dp),
                    style =
                        MaterialTheme.typography
                            .bodySmall,
                    color =
                        MaterialTheme.colorScheme
                            .onErrorContainer
                )
            }
        }

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = {
                expanded = !expanded
            }
        ) {

            OutlinedTextField(
                value =
                    selectedInfo?.let {
                        modelLabel(it)
                    } ?: selected.ifBlank {
                        "اختر نموذجاً"
                    },
                onValueChange = {},
                readOnly = true,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .menuAnchor(),
                trailingIcon = {
                    ExposedDropdownMenuDefaults
                        .TrailingIcon(
                            expanded = expanded
                        )
                },
                shape =
                    RoundedCornerShape(12.dp),
                label = {
                    Text("النموذج المختار")
                }
            )

            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = {
                    expanded = false
                }
            ) {

                if (models.isEmpty()) {

                    DropdownMenuItem(
                        text = {
                            Text(
                                if (loading)
                                    "جاري التحميل..."
                                else
                                    "اضغط 🔄 لتحميل النماذج"
                            )
                        },
                        onClick = {
                            expanded = false
                        }
                    )

                } else {

                    models.forEach { model ->

                        DropdownMenuItem(
                            text = {

                                Column {

                                    Text(
                                        text =
                                            modelLabel(model),
                                        fontWeight =
                                            if (
                                                model.recommended
                                            ) {
                                                FontWeight.Bold
                                            } else {
                                                FontWeight.Normal
                                            }
                                    )

                                    if (
                                        model.contextLength > 0
                                    ) {

                                        Text(
                                            text =
                                                formatContextLength(
                                                    model.contextLength
                                                ),
                                            style =
                                                MaterialTheme.typography
                                                    .labelSmall,
                                            color =
                                                MaterialTheme.colorScheme
                                                    .onSurfaceVariant
                                        )
                                    }
                                }
                            },
                            onClick = {

                                onSelect(model.id)

                                expanded = false
                            }
                        )
                    }
                }
            }
        }
    }
}

// =================================================================
// Section title
// =================================================================

@Composable
private fun SectionTitle(
    title: String,
    subtitle: String
) {

    Column(
        verticalArrangement =
            Arrangement.spacedBy(4.dp)
    ) {

        Text(
            text = title,
            style =
                MaterialTheme.typography
                    .headlineSmall,
            fontWeight =
                FontWeight.Bold
        )

        Text(
            text = subtitle,
            style =
                MaterialTheme.typography
                    .bodyMedium,
            color =
                MaterialTheme.colorScheme
                    .onSurfaceVariant
        )
    }
}

// =================================================================
// Saved card
// =================================================================

@Composable
private fun SavedCard() {

    Card(
        modifier =
            Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    MaterialTheme.colorScheme
                        .secondaryContainer
            ),
        shape =
            RoundedCornerShape(12.dp)
    ) {

        Row(
            modifier =
                Modifier.padding(12.dp),
            verticalAlignment =
                Alignment.CenterVertically
        ) {

            Icon(
                imageVector =
                    Icons.Filled.CheckCircle,
                contentDescription = null
            )

            Spacer(
                Modifier.width(8.dp)
            )

            Text(
                text =
                    "تم حفظ الإعدادات بنجاح."
            )
        }
    }
}

// =================================================================
// Model label
// =================================================================

private fun modelLabel(
    model: ModelInfo
): String = buildString {

    if (model.name.contains("💾")) {

        append(model.name)

        return@buildString
    }

    if (model.isFree) {
        append("🟢 ")
    }

    if (model.recommended) {
        append("⭐ ")
    }

    append(model.name)

    if (model.supportsVision) {
        append(" 🖼️")
    }
}

// =================================================================
// Context length
// =================================================================

private fun formatContextLength(
    contextLength: Long
): String {

    return when {

        contextLength >= 1_000_000 ->
            "${contextLength / 1_000_000}M context"

        contextLength >= 1_000 ->
            "${contextLength / 1_000}K context"

        else ->
            "$contextLength context"
    }
}

// =================================================================
// Info card
// =================================================================

@Composable
private fun InfoCard(
    text: String
) {

    Card(
        colors =
            CardDefaults.cardColors(
                containerColor =
                    MaterialTheme.colorScheme
                        .secondaryContainer
                        .copy(alpha = 0.5f)
            ),
        shape =
            RoundedCornerShape(12.dp),
        modifier =
            Modifier.fillMaxWidth()
    ) {

        Text(
            text = text,
            modifier =
                Modifier.padding(12.dp),
            style =
                MaterialTheme.typography
                    .bodySmall,
            color =
                MaterialTheme.colorScheme
                    .onSecondaryContainer
        )
    }
}

// =================================================================
// API key field
// =================================================================

@Composable
private fun KeyField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    showKey: Boolean,
    placeholder: String
) {

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier =
            Modifier.fillMaxWidth(),
        label = {
            Text(label)
        },
        placeholder = {
            Text(placeholder)
        },
        singleLine = true,
        visualTransformation =
            if (showKey) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
        shape =
            RoundedCornerShape(12.dp)
    )
}
