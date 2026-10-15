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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: AiSettings,
    onBack: () -> Unit,
    onOpenMemory: () -> Unit,
    onOpenWebPlatforms: () -> Unit
) {
    var tab by remember { mutableIntStateOf(0) }

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
        }
        saved = true
    }

    fun saveAccuracyOnly() {
        settings.accuracyPromptEnabled = accuracyEnabled
        settings.customSystemInstruction = customInstruction
        settings.autoSave = autoSave
        saved = true
    }

    // -------- dynamic models loading --------
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

    val providerMeta = remember {
        mapOf(
            "gemini" to ProviderMeta(
                info = "Google Gemini\n\nاحصل على مفتاح API من Google AI Studio.",
                keyLabel = "Gemini API Key",
                keyPlaceholder = "أدخل مفتاح Gemini API"
            ),
            "openrouter" to ProviderMeta(
                info = "OpenRouter\n\nيوفر الوصول إلى عدد كبير من النماذج من خلال API واحد.",
                keyLabel = "OpenRouter API Key",
                keyPlaceholder = "أدخل مفتاح OpenRouter API",
                manualModelField = true
            ),
            "openai" to ProviderMeta(
                info = "OpenAI\n\nأدخل مفتاح OpenAI API ثم حمّل النماذج المتاحة.",
                keyLabel = "OpenAI API Key",
                keyPlaceholder = "أدخل مفتاح OpenAI API"
            ),
            "mistral" to ProviderMeta(
                info = "Mistral AI\n\nيمكن الحصول على مفتاح API من console.mistral.ai.\nيدعم المشروع نماذج Mistral المتاحة من الكتالوج.",
                keyLabel = "Mistral API Key",
                keyPlaceholder = "أدخل مفتاح Mistral API"
            ),
            "groq" to ProviderMeta(
                info = "Groq\n\nيتميز بسرعة الاستجابة، ويمكن تحميل النماذج المتاحة مباشرة.",
                keyLabel = "Groq API Key",
                keyPlaceholder = "أدخل مفتاح Groq API",
                manualModelField = true
            ),
            "nvidia" to ProviderMeta(
                info = "NVIDIA NIM\n\nيمكن تحميل النماذج المتاحة من NVIDIA NIM.",
                keyLabel = "NVIDIA API Key",
                keyPlaceholder = "أدخل مفتاح NVIDIA API",
                manualModelField = true
            ),
            "huggingface" to ProviderMeta(
                info = "Hugging Face 🤗\n\nيمكن تحميل النماذج المتاحة من Hugging Face.",
                keyLabel = "Hugging Face API Key",
                keyPlaceholder = "أدخل مفتاح Hugging Face إن وجد",
                manualModelField = true
            )
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("الإعدادات") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "رجوع")
                    }
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
                0 -> TabColumn {
                    SectionTitle("🤖 مزود الذكاء الاصطناعي", "اختر المزود والنموذج المستخدم للمحادثة.")

                    RadioCard(
                        options = providers,
                        selected = provider,
                        onSelect = ::selectProvider
                    )

                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "🔑 مفتاح API",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { showKeys = !showKeys }) {
                            Icon(
                                if (showKeys) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = null
                            )
                        }
                    }

                    when (provider) {
                        "ollama" -> {
                            InfoCard(
                                "Ollama 🦙 (محلي)\n\n" +
                                    "يعمل النموذج محليًا على الجهاز أو الشبكة المحلية.\n\n" +
                                    "العنوان الافتراضي:\n127.0.0.1:11434\n\n" +
                                    "في Termux شغّل:\nollama serve\n\n" +
                                    "ثم حمّل نموذجًا مثل:\nollama pull qwen2.5:1.5b\n\n" +
                                    "لا يحتاج Ollama إلى API Key أو اتصال مباشر بالإنترنت أثناء الاستخدام."
                            )
                            OutlinedTextField(
                                value = modelsState["ollama"].orEmpty(),
                                onValueChange = { modelsState["ollama"] = it; saved = false },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("نموذج Ollama") },
                                singleLine = true,
                                leadingIcon = { Icon(Icons.Filled.Computer, null) },
                                shape = RoundedCornerShape(12.dp)
                            )
                            Text(
                                "نماذج مقترحة: qwen2.5:0.5b • qwen2.5:1.5b • qwen2.5:3b • gemma2:2b",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        "custom" -> {
                            InfoCard("Custom API\n\nاستخدم خادم API متوافقًا مع واجهة المشروع.")
                            OutlinedTextField(
                                value = customUrl,
                                onValueChange = { customUrl = it; saved = false },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Server URL") },
                                placeholder = { Text("https://example.com") },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp)
                            )
                            KeyField(
                                label = "API Key",
                                value = keys["custom"].orEmpty(),
                                onValueChange = { keys["custom"] = it; saved = false },
                                showKey = showKeys,
                                placeholder = "مفتاح API"
                            )
                            OutlinedTextField(
                                value = modelsState["custom"].orEmpty(),
                                onValueChange = { modelsState["custom"] = it; saved = false },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Model") },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp)
                            )
                        }

                        else -> {
                            val meta = providerMeta[provider]
                            if (meta != null) {
                                InfoCard(meta.info)

                                KeyField(
                                    label = meta.keyLabel,
                                    value = keys[provider].orEmpty(),
                                    onValueChange = { keys[provider] = it; saved = false },
                                    showKey = showKeys,
                                    placeholder = meta.keyPlaceholder
                                )

                                DynamicModelSelector(
                                    models = filteredModels,
                                    selected = modelsState[provider].orEmpty(),
                                    loading = loadingModels,
                                    error = modelsError,
                                    onlyFree = onlyFree,
                                    onlyRecommended = onlyRecommended,
                                    onlyVision = onlyVision,
                                    onFreeChange = { onlyFree = it },
                                    onRecommendedChange = { onlyRecommended = it },
                                    onVisionChange = { onlyVision = it },
                                    onSelect = { modelsState[provider] = it; saved = false },
                                    onRefresh = { refreshTrigger++ }
                                )

                                if (meta.manualModelField) {
                                    OutlinedTextField(
                                        value = modelsState[provider].orEmpty(),
                                        onValueChange = { modelsState[provider] = it; saved = false },
                                        modifier = Modifier.fillMaxWidth(),
                                        label = { Text("النموذج يدويًا") },
                                        singleLine = true,
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                }
                            }
                        }
                    }

                    HorizontalDivider()
                    SaveButton(onClick = ::saveAll)
                    if (saved) SavedCard()
                }

                1 -> TabColumn {
                    SectionTitle("🧠 الذاكرة", "إدارة الذكريات والحفظ التلقائي ووسيط الذاكرة.")

                    Card(
                        Modifier.fillMaxWidth().clickable(onClick = onOpenMemory),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Memory, null, modifier = Modifier.size(32.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("الذكريات", fontWeight = FontWeight.Bold)
                                Text("عرض وتعديل وحذف الذكريات المحفوظة.", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }

                    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("💾 الحفظ التلقائي للردود", fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "عند التفعيل، يمكن لـ AiChat حفظ الردود المهمة تلقائيًا في الذاكرة بدل الضغط على زر الحفظ كل مرة.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(checked = autoSave, onCheckedChange = { autoSave = it; saved = false })
                        }
                    }

                    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("🧠 وسيط الذاكرة الذكي", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                                    Text("يحلل الذاكرة قبل إرسالها إلى النموذج الرئيسي.", style = MaterialTheme.typography.bodySmall)
                                }
                                Switch(
                                    checked = memoryCuratorEnabled,
                                    onCheckedChange = { memoryCuratorEnabled = it; saved = false }
                                )
                            }

                            Text(
                                "يمكن للوسيط اقتراح النقاط المهمة من الذاكرة. ويمكن كتابة هوية الوسيط إذا أردت تحديد دوره وطريقة تحليله.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            if (memoryCuratorEnabled) {
                                Text("مزود الوسيط", fontWeight = FontWeight.Bold)
                                Text(
                                    "يستخدم الوسيط نموذج المزوّد المختار في إعدادات المزوّد أعلاه (تبويب 🤖 المحادثة). عند تغيير النموذج هناك، سيستخدم الوسيط النموذج الجديد تلقائيًا.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                val curatorOptions = listOf(
                                    "gemini" to "Google Gemini ⭐",
                                    "groq" to "Groq",
                                    "mistral" to "Mistral AI",
                                    "ollama" to "Ollama 🦙 (محلي)",
                                    "custom" to "Custom API"
                                )
                                RadioCard(
                                    options = curatorOptions,
                                    selected = memoryCuratorProvider,
                                    onSelect = ::selectCuratorProvider,
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                )

                                when (memoryCuratorProvider) {
                                    "gemini" -> InfoCard(
                                        "سيستخدم الوسيط نموذج Gemini نفسه المستخدم في المحادثة" +
                                            (modelsState["gemini"].orEmpty().takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "") +
                                            " ومفتاح API الخاص به."
                                    )

                                    "groq" -> InfoCard(
                                        "سيستخدم الوسيط نموذج Groq نفسه المستخدم في المحادثة" +
                                            (modelsState["groq"].orEmpty().takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "") +
                                            " ومفتاح API الخاص به."
                                    )

                                    "mistral" -> InfoCard(
                                        "سيستخدم الوسيط نموذج Mistral نفسه المستخدم في المحادثة" +
                                            (modelsState["mistral"].orEmpty().takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "") +
                                            " ومفتاح API الخاص به."
                                    )

                                    "ollama" -> InfoCard(
                                        "سيستخدم الوسيط نموذج Ollama نفسه المستخدم في المحادثة" +
                                            (modelsState["ollama"].orEmpty().takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "") +
                                            ".\n\nمتطلبات Ollama:\n\n127.0.0.1:11434\n\nفي Termux:\nollama serve\n\nثم:\nollama pull qwen2.5:1.5b\n\nلا يحتاج API Key ولا اتصالًا بالإنترنت أثناء الاستخدام."
                                    )

                                    "custom" -> {
                                        InfoCard(
                                            "سيستخدم الوسيط إعدادات Custom نفسها الموجودة في تبويب المحادثة" +
                                                (modelsState["custom"].orEmpty().takeIf { it.isNotBlank() }?.let { " (النموذج: $it)" } ?: "") +
                                                "."
                                        )
                                        if (customUrl.isBlank() || modelsState["custom"].orEmpty().isBlank()) {
                                            Card(
                                                Modifier.fillMaxWidth(),
                                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                                                shape = RoundedCornerShape(8.dp)
                                            ) {
                                                Text(
                                                    "⚠️ يجب تحديد Server URL والنموذج في إعدادات Custom.",
                                                    modifier = Modifier.padding(12.dp),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onErrorContainer
                                                )
                                            }
                                        }
                                    }
                                }

                                OutlinedTextField(
                                    value = mediatorIdentityText,
                                    onValueChange = { mediatorIdentityText = it; saved = false },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text("هوية وسيط الذاكرة — اختياري") },
                                    placeholder = { Text("مثلاً: أنت وسيط متخصص في تحليل الذاكرة...") },
                                    minLines = 3,
                                    shape = RoundedCornerShape(12.dp)
                                )
                            }
                        }
                    }

                    SaveButton(onClick = ::saveAll)
                    if (saved) SavedCard()
                }

                2 -> TabColumn {
                    SectionTitle("🌐 الويب", "إدارة منصات الويب التي يتعامل معها AiChat.")

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

                    InfoCard("هذه الصفحة تفتح شاشة إدارة المنصات الموجودة في المشروع. لن يتم تكرار قائمة المنصات هنا.")
                }

                3 -> TabColumn {
                    SectionTitle("🎯 الدقة", "قواعد إضافية لتحسين دقة وموثوقية الإجابات.")

                    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("🎯 تفعيل وثيقة الدقة", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                                    Text("إضافة قواعد الدقة والموثوقية إلى تعليمات النموذج.", style = MaterialTheme.typography.bodySmall)
                                }
                                Switch(
                                    checked = accuracyEnabled,
                                    onCheckedChange = {
                                        accuracyEnabled = it
                                        settings.accuracyPromptEnabled = it // نفس السلوك السابق
                                        saved = false
                                    }
                                )
                            }

                            OutlinedTextField(
                                value = customInstruction,
                                onValueChange = { customInstruction = it; saved = false },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("تعليمات إضافية مخصصة") },
                                placeholder = { Text("أضف تعليمات خاصة تريد تطبيقها على الإجابات...") },
                                minLines = 5,
                                shape = RoundedCornerShape(12.dp)
                            )
                        }
                    }

                    SaveButton(onClick = ::saveAccuracyOnly)
                    if (saved) SavedCard()
                }

                else -> TabColumn {
                    SectionTitle("⚙️ عام", "إعدادات عامة ستتم إضافتها هنا مستقبلًا.")
                    InfoCard(
                        "لا توجد إعدادات عامة إضافية حاليًا.\n\n" +
                            "تم ترك هذا التبويب جاهزًا للتوسعة دون تغيير بنية المشروع."
                    )
                }
            }
        }
    }
}

// ---------------- helpers ----------------

private data class ProviderMeta(
    val info: String,
    val keyLabel: String,
    val keyPlaceholder: String,
    val manualModelField: Boolean = false
)

@Composable
private fun TabColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = content
    )
}

@Composable
private fun SaveButton(onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Filled.Save, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text("حفظ الإعدادات")
    }
}

@Composable
private fun RadioCard(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    containerColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.surface
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            options.forEach { (key, label) ->
                Row(
                    Modifier.fillMaxWidth().clickable { onSelect(key) }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = selected == key, onClick = { onSelect(key) })
                    Text(label, modifier = Modifier.padding(start = 4.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
    var expanded by remember { mutableStateOf(false) }
    val selectedInfo = models.firstOrNull { it.id == selected }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("النموذج", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (loading) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("جاري التحميل...", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, contentDescription = "تحديث النماذج") }
                Text("${models.size} نموذج", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = onlyFree, onClick = { onFreeChange(!onlyFree) }, label = { Text("🟢 مجاني") })
            FilterChip(selected = onlyRecommended, onClick = { onRecommendedChange(!onlyRecommended) }, label = { Text("⭐ موصى به") })
            FilterChip(selected = onlyVision, onClick = { onVisionChange(!onlyVision) }, label = { Text("🖼️ يدعم الصور") })
        }

        if (!error.isNullOrBlank()) {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    "⚠️ $error\nسيتم الاحتفاظ بالنموذج المحفوظ.",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }

        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
            OutlinedTextField(
                value = selectedInfo?.let(::modelLabel) ?: selected.ifBlank { "اختر نموذجاً" },
                onValueChange = {},
                readOnly = true,
                modifier = Modifier.fillMaxWidth().menuAnchor(),
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                shape = RoundedCornerShape(12.dp),
                label = { Text("النموذج المختار") }
            )

            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                if (models.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text(if (loading) "جاري التحميل..." else "اضغط 🔄 لتحميل النماذج") },
                        onClick = { expanded = false }
                    )
                } else {
                    models.forEach { m ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(modelLabel(m), fontWeight = if (m.recommended) FontWeight.Bold else FontWeight.Normal)
                                    if (m.contextLength > 0) {
                                        Text(
                                            formatContextLength(m.contextLength),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            },
                            onClick = { onSelect(m.id); expanded = false }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SavedCard() {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("تم حفظ الإعدادات بنجاح.")
        }
    }
}

private fun modelLabel(model: ModelInfo): String = buildString {
    if (model.name.contains("💾")) {
        append(model.name); return@buildString
    }
    if (model.isFree) append("🟢 ")
    if (model.recommended) append("⭐ ")
    append(model.name)
    if (model.supportsVision) append(" 🖼️")
}

private fun formatContextLength(contextLength: Long): String = when {
    contextLength >= 1_000_000 -> "${contextLength / 1_000_000}M context"
    contextLength >= 1_000 -> "${contextLength / 1_000}K context"
    else -> "$contextLength context"
}

@Composable
private fun InfoCard(text: String) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}

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
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        singleLine = true,
        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
        shape = RoundedCornerShape(12.dp)
    )
}
