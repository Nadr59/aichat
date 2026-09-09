package com.example.aichat.ui.screens

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.aichat.data.local.AiSettings
import com.example.aichat.data.model.ModelInfo
import com.example.aichat.repository.ModelCatalogRepository
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: AiSettings,
    onBack: () -> Unit
) {
    // ============================================================
    // State
    // ============================================================

    var provider        by remember { mutableStateOf(settings.provider) }
    var geminiKey       by remember { mutableStateOf(settings.geminiKey) }
    var geminiModel     by remember { mutableStateOf(settings.geminiModel) }
    var openrouterKey   by remember { mutableStateOf(settings.openrouterKey) }
    var openrouterModel by remember { mutableStateOf(settings.openrouterModel) }
    var openaiKey       by remember { mutableStateOf(settings.openaiKey) }
    var openaiModel     by remember { mutableStateOf(settings.openaiModel) }
    var mistralKey      by remember { mutableStateOf(settings.mistralKey) }
    var mistralModel    by remember { mutableStateOf(settings.mistralModel) }
    var groqKey         by remember { mutableStateOf(settings.groqKey) }
    var groqModel       by remember { mutableStateOf(settings.groqModel) }
    var huggingfaceKey   by remember { mutableStateOf(settings.huggingfaceKey) }
    var huggingfaceModel by remember { mutableStateOf(settings.huggingfaceModel) }
    var hordeKey        by remember { mutableStateOf(settings.hordeKey) }
    var hordeTextModel  by remember { mutableStateOf(settings.hordeTextModel) }
    var hordeImageModel by remember { mutableStateOf(settings.hordeImageModel) }
    var customUrl       by remember { mutableStateOf(settings.customUrl) }
    var customKey       by remember { mutableStateOf(settings.customKey) }
    var customModel     by remember { mutableStateOf(settings.customModel) }
    var imageProvider   by remember { mutableStateOf(settings.imageProvider) }
    var imageModel      by remember { mutableStateOf(settings.imageModel) }
    var customImageUrl  by remember { mutableStateOf(settings.customImageUrl) }
    var customImageKey  by remember { mutableStateOf(settings.customImageKey) }
    var showKeys        by remember { mutableStateOf(false) }
    var saved           by remember { mutableStateOf(false) }

    // ============================================================
    // نظام الكتالوج الديناميكي
    // ============================================================

    val context    = LocalContext.current
    val scope      = rememberCoroutineScope()
    val catalog    = remember { ModelCatalogRepository(settings) }

    var models         by remember { mutableStateOf<List<ModelInfo>>(emptyList()) }
    var loadingModels  by remember { mutableStateOf(false) }
    var modelsError    by remember { mutableStateOf<String?>(null) }
    var refreshTrigger by remember { mutableIntStateOf(0) }
    

    // فلاتر
    var onlyFree        by remember { mutableStateOf(false) }
    var onlyRecommended by remember { mutableStateOf(false) }
    var onlyVision      by remember { mutableStateOf(false) }

    // المفتاح الحالي حسب المزود
    val currentKey = when (provider) {
        "gemini"     -> geminiKey
        "openrouter" -> openrouterKey
        "openai"     -> openaiKey
        "mistral"    -> mistralKey
        "groq"       -> groqKey
        "huggingface"  -> huggingfaceKey
        "horde"      -> hordeKey
        else         -> ""
    }

    // النموذج الحالي حسب المزود
    val currentModel = when (provider) {
        "gemini"     -> geminiModel
        "openrouter" -> openrouterModel
        "openai"     -> openaiModel
        "mistral"    -> mistralModel
        "groq"       -> groqModel
        "huggingface"  -> huggingfaceModel
        "horde"      -> hordeTextModel
        else         -> customModel
    }

    // تحميل النماذج عند تغيير المزود أو الضغط على تحديث
    // ✅ الحل: مرر المفتاح الحالي من المتغير مباشرة
LaunchedEffect(provider, refreshTrigger) {

    if (provider == "custom") {
        models = emptyList()
        return@LaunchedEffect
    }

    loadingModels = true
    modelsError   = null
    models        = emptyList()

    val keyToUse = when (provider) {
        "gemini"      -> geminiKey
        "openrouter"  -> openrouterKey
        "openai"      -> openaiKey
        "mistral"     -> mistralKey
        "groq"        -> groqKey
        "horde"       -> hordeKey
        "huggingface" -> huggingfaceKey
        else          -> ""
    }

    // ✅ HF يحمل القائمة حتى بدون مفتاح
    val needsKey = provider != "huggingface" && provider != "horde"

    if (needsKey && keyToUse.isBlank()) {
        modelsError   = "أدخل API Key أولاً ثم اضغط تحديث 🔄"
        loadingModels = false
        return@LaunchedEffect
    }

    try {
        models = catalog.getModels(
            provider  = provider,
            apiKey    = keyToUse,
            forImages = false
        )
    } catch (e: Exception) {
        modelsError = e.message ?: "تعذر تحميل النماذج"
    } finally {
        loadingModels = false
    }
}

    // القائمة مع إضافة النموذج المحفوظ إذا لم يكن موجوداً
    val modelsWithSaved = remember(models, currentModel) {
        if (currentModel.isBlank() || models.any { it.id == currentModel }) {
            models
        } else {
            listOf(
                ModelInfo(
                    id          = currentModel,
                    name        = "$currentModel 💾",
                    provider    = provider,
                    isFree      = currentModel.contains(":free"),
                    recommended = false
                )
            ) + models
        }
    }

    // تطبيق الفلاتر
    val filteredModels = modelsWithSaved
        .filter { !onlyFree        || it.isFree }
        .filter { !onlyRecommended || it.recommended }
        .filter { !onlyVision      || it.supportsVision }

    // ============================================================
    // قائمة المزودين
    // ============================================================

    val providers = listOf(
        "gemini"     to "Google Gemini ⭐",
        "openrouter" to "OpenRouter",
        "openai"     to "OpenAI",
        "mistral"    to "Mistral AI",
        "groq"       to "Groq",
        "horde"      to "AI Horde (مجاني تماماً)",
        "huggingface"  to "Hugging Face 🤗 (مجاني)",  
        "custom"     to "Custom API"
    )

    // ============================================================
    // UI
    // ============================================================

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("الإعدادات") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "رجوع")
                    }
                }
            )
        }
    ) { padding ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            // ---- مزود المحادثة ----
            Text("مزود المحادثة:", fontWeight = FontWeight.Bold)

            providers.forEach { (key, name) ->
                Card(
                    onClick = {
                        if (provider != key) {
                            provider        = key
                            saved           = false
                            onlyFree        = false
                            onlyRecommended = false
                            onlyVision      = false
                            models          = emptyList()
                            modelsError     = null
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (provider == key)
                            MaterialTheme.colorScheme.primaryContainer
                        else
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = provider == key,
                            onClick  = null
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            name,
                            fontWeight = if (provider == key)
                                FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // ---- إظهار/إخفاء المفاتيح ----
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("مفاتيح API:", fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { showKeys = !showKeys }) {
                    Icon(
                        if (showKeys) Icons.Filled.VisibilityOff
                        else Icons.Filled.Visibility,
                        contentDescription = null
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(if (showKeys) "إخفاء" else "إظهار")
                }
            }

            // ---- إعدادات المزود ----
            when (provider) {

                "gemini" -> {
                    InfoCard("احصل على مفتاح مجاني من:\naistudio.google.com/apikey")
                    KeyField(
                        label         = "Gemini API Key",
                        value         = geminiKey,
                        onValueChange = { geminiKey = it; saved = false },
                        showKey       = showKeys,
                        placeholder   = "AIza..."
                    )
                    DynamicModelSelector(
                        models          = filteredModels,
                        selected        = geminiModel,
                        loading         = loadingModels,
                        error           = modelsError,
                        onlyFree        = onlyFree,
                        onlyRecommended = onlyRecommended,
                        onlyVision      = onlyVision,
                        onFreeChange        = { onlyFree = it },
                        onRecommendedChange = { onlyRecommended = it },
                        onVisionChange      = { onlyVision = it },
                        onSelect        = { geminiModel = it; saved = false },
                        onRefresh       = { refreshTrigger++ }
                    )
                }

                "openrouter" -> {
                    InfoCard("احصل على مفتاح من: openrouter.ai/keys\nبعض النماذج مجانية تماماً")
                    KeyField(
                        label         = "OpenRouter API Key",
                        value         = openrouterKey,
                        onValueChange = { openrouterKey = it; saved = false },
                        showKey       = showKeys,
                        placeholder   = "sk-or-..."
                    )
                    DynamicModelSelector(
                        models          = filteredModels,
                        selected        = openrouterModel,
                        loading         = loadingModels,
                        error           = modelsError,
                        onlyFree        = onlyFree,
                        onlyRecommended = onlyRecommended,
                        onlyVision      = onlyVision,
                        onFreeChange        = { onlyFree = it },
                        onRecommendedChange = { onlyRecommended = it },
                        onVisionChange      = { onlyVision = it },
                        onSelect        = { openrouterModel = it; saved = false },
                        onRefresh       = { refreshTrigger++ }
                    )
                    OutlinedTextField(
                        value         = openrouterModel,
                        onValueChange = { openrouterModel = it; saved = false },
                        modifier      = Modifier.fillMaxWidth(),
                        label         = { Text("أو اكتب اسم النموذج يدوياً") },
                        singleLine    = true,
                        shape         = RoundedCornerShape(12.dp)
                    )
                }

                "openai" -> {
                    InfoCard("احصل على مفتاح من: platform.openai.com")
                    KeyField(
                        label         = "OpenAI API Key",
                        value         = openaiKey,
                        onValueChange = { openaiKey = it; saved = false },
                        showKey       = showKeys,
                        placeholder   = "sk-..."
                    )
                    DynamicModelSelector(
                        models          = filteredModels,
                        selected        = openaiModel,
                        loading         = loadingModels,
                        error           = modelsError,
                        onlyFree        = onlyFree,
                        onlyRecommended = onlyRecommended,
                        onlyVision      = onlyVision,
                        onFreeChange        = { onlyFree = it },
                        onRecommendedChange = { onlyRecommended = it },
                        onVisionChange      = { onlyVision = it },
                        onSelect        = { openaiModel = it; saved = false },
                        onRefresh       = { refreshTrigger++ }
                    )
                }

                "mistral" -> {
                    InfoCard(
                        "احصل على مفتاح من: console.mistral.ai\n" +
                        "✅ pixtral-12b-2409 موصى به للمجاني"
                    )
                    KeyField(
                        label         = "Mistral API Key",
                        value         = mistralKey,
                        onValueChange = { mistralKey = it; saved = false },
                        showKey       = showKeys,
                        placeholder   = "key..."
                    )
                    DynamicModelSelector(
                        models          = filteredModels,
                        selected        = mistralModel,
                        loading         = loadingModels,
                        error           = modelsError,
                        onlyFree        = onlyFree,
                        onlyRecommended = onlyRecommended,
                        onlyVision      = onlyVision,
                        onFreeChange        = { onlyFree = it },
                        onRecommendedChange = { onlyRecommended = it },
                        onVisionChange      = { onlyVision = it },
                        onSelect        = { mistralModel = it; saved = false },
                        onRefresh       = { refreshTrigger++ }
                    )
                }

                "groq" -> {
                    InfoCard("احصل على مفتاح من: console.groq.com")
                    KeyField(
                        label         = "Groq API Key",
                        value         = groqKey,
                        onValueChange = { groqKey = it; saved = false },
                        showKey       = showKeys,
                        placeholder   = "gsk_..."
                    )
                    DynamicModelSelector(
                        models          = filteredModels,
                        selected        = groqModel,
                        loading         = loadingModels,
                        error           = modelsError,
                        onlyFree        = onlyFree,
                        onlyRecommended = onlyRecommended,
                        onlyVision      = onlyVision,
                        onFreeChange        = { onlyFree = it },
                        onRecommendedChange = { onlyRecommended = it },
                        onVisionChange      = { onlyVision = it },
                        onSelect        = { groqModel = it; saved = false },
                        onRefresh       = { refreshTrigger++ }
                    )
                    OutlinedTextField(
                        value         = groqModel,
                        onValueChange = { groqModel = it; saved = false },
                        modifier      = Modifier.fillMaxWidth(),
                        label         = { Text("أو اكتب اسم النموذج يدوياً") },
                        singleLine    = true,
                        shape         = RoundedCornerShape(12.dp)
                    )
                }
                "huggingface" -> {
    InfoCard(
        "🤗 Hugging Face Inference API\n" +
        "احصل على توكن مجاني من: huggingface.co/settings/tokens\n" +
        "⚠️ النموذج قد يحتاج دقيقة للتحميل أول مرة"
    )
    KeyField(
        label         = "HF Token",
        value         = huggingfaceKey,
        onValueChange = { huggingfaceKey = it; saved = false },
        showKey       = showKeys,
        placeholder   = "hf_..."
    )
    DynamicModelSelector(
        models          = filteredModels,
        selected        = huggingfaceModel,
        loading         = loadingModels,
        error           = modelsError,
        onlyFree        = onlyFree,
        onlyRecommended = onlyRecommended,
        onlyVision      = onlyVision,
        onFreeChange        = { onlyFree = it },
        onRecommendedChange = { onlyRecommended = it },
        onVisionChange      = { onlyVision = it },
        onSelect        = { huggingfaceModel = it; saved = false },
        onRefresh       = { refreshTrigger++ }
    )
    OutlinedTextField(
        value         = huggingfaceModel,
        onValueChange = { huggingfaceModel = it; saved = false },
        modifier      = Modifier.fillMaxWidth(),
        label         = { Text("أو اكتب اسم النموذج يدوياً") },
        placeholder   = { Text("username/model-name") },
        singleLine    = true,
        shape         = RoundedCornerShape(12.dp)
    )
                }

                "horde" -> {
                    InfoCard(
                        "AI Horde شبكة موزعة مجانية تماماً\n" +
                        "المفتاح الافتراضي 0000000000 يعمل بدون تسجيل"
                    )
                    KeyField(
                        label         = "Horde API Key",
                        value         = hordeKey,
                        onValueChange = { hordeKey = it; saved = false },
                        showKey       = showKeys,
                        placeholder   = "0000000000"
                    )
                    DynamicModelSelector(
                        models          = filteredModels,
                        selected        = hordeTextModel,
                        loading         = loadingModels,
                        error           = modelsError,
                        onlyFree        = onlyFree,
                        onlyRecommended = onlyRecommended,
                        onlyVision      = onlyVision,
                        onFreeChange        = { onlyFree = it },
                        onRecommendedChange = { onlyRecommended = it },
                        onVisionChange      = { onlyVision = it },
                        onSelect        = { hordeTextModel = it; saved = false },
                        onRefresh       = { refreshTrigger++ }
                    )
                    OutlinedTextField(
                        value         = hordeTextModel,
                        onValueChange = { hordeTextModel = it; saved = false },
                        modifier      = Modifier.fillMaxWidth(),
                        label         = { Text("أو اكتب اسم النموذج يدوياً") },
                        singleLine    = true,
                        shape         = RoundedCornerShape(12.dp)
                    )
                }

                "custom" -> {
                    InfoCard(
                        "API متوافق مع OpenAI\n" +
                        "مثال NVIDIA: https://integrate.api.nvidia.com/v1/chat/completions"
                    )
                    OutlinedTextField(
                        value         = customUrl,
                        onValueChange = { customUrl = it; saved = false },
                        modifier      = Modifier.fillMaxWidth(),
                        label         = { Text("Server URL") },
                        placeholder   = { Text("https://api.example.com/v1/chat/completions") },
                        singleLine    = true,
                        shape         = RoundedCornerShape(12.dp)
                    )
                    KeyField(
                        label         = "API Key",
                        value         = customKey,
                        onValueChange = { customKey = it; saved = false },
                        showKey       = showKeys,
                        placeholder   = "key... (اتركه فارغاً إذا لم يكن مطلوباً)"
                    )
                    OutlinedTextField(
                        value         = customModel,
                        onValueChange = { customModel = it; saved = false },
                        modifier      = Modifier.fillMaxWidth(),
                        label         = { Text("اسم النموذج") },
                        placeholder   = { Text("nvidia/nemotron-3.5-lightning-30b-a3b") },
                        singleLine    = true,
                        shape         = RoundedCornerShape(12.dp)
                    )
                }
            }

            // ---- قسم توليد الصور ----
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            Text("🎨 إعدادات توليد الصور:", fontWeight = FontWeight.Bold)

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape    = RoundedCornerShape(12.dp),
                colors   = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "مزود توليد الصور:",
                        style      = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))

                    listOf(
                        "openrouter" to "OpenRouter ⭐ (نماذج مجانية)",
                        "openai"     to "OpenAI DALL-E",
                        "horde"      to "AI Horde (مجاني تماماً)",
                        "custom"     to "Custom Image API"
                    ).forEach { (key, name) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier          = Modifier.padding(vertical = 2.dp)
                        ) {
                            RadioButton(
                                selected = imageProvider == key,
                                onClick  = {
                                    imageProvider = key
                                    imageModel    = when (key) {
                                        "openai" -> "dall-e-3"
                                        "horde"  -> hordeImageModel
                                        "custom" -> ""
                                        else     -> "black-forest-labs/flux-schnell:free"
                                    }
                                    saved = false
                                }
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(name, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            when (imageProvider) {
                "openrouter" -> {
                    InfoCard("يستخدم نفس مفتاح OpenRouter")
                    OutlinedTextField(
                        value         = imageModel,
                        onValueChange = { imageModel = it; saved = false },
                        modifier      = Modifier.fillMaxWidth(),
                        label         = { Text("نموذج توليد الصور") },
                        placeholder   = { Text("black-forest-labs/flux-schnell:free") },
                        singleLine    = true,
                        shape         = RoundedCornerShape(12.dp)
                    )
                }
                
                "horde" -> {
                    InfoCard("يستخدم نفس مفتاح Horde - قد يستغرق دقائق")
                    OutlinedTextField(
                        value         = hordeImageModel,
                        onValueChange = { hordeImageModel = it; saved = false },
                        modifier      = Modifier.fillMaxWidth(),
                        label         = { Text("نموذج الصور") },
                        placeholder   = { Text("Stable Diffusion XL") },
                        singleLine    = true,
                        shape         = RoundedCornerShape(12.dp)
                    )
                }
                "openai" -> {
                    InfoCard("يستخدم نفس مفتاح OpenAI")
                    OutlinedTextField(
                        value         = imageModel,
                        onValueChange = { imageModel = it; saved = false },
                        modifier      = Modifier.fillMaxWidth(),
                        label         = { Text("النموذج") },
                        placeholder   = { Text("dall-e-3") },
                        singleLine    = true,
                        shape         = RoundedCornerShape(12.dp)
                    )
                }
                "custom" -> {
                    InfoCard("متوافق مع OpenAI Images API")
                    OutlinedTextField(
                        value         = customImageUrl,
                        onValueChange = { customImageUrl = it; saved = false },
                        modifier      = Modifier.fillMaxWidth(),
                        label         = { Text("Image API URL") },
                        placeholder   = { Text("https://api.example.com/v1/images/generations") },
                        singleLine    = true,
                        shape         = RoundedCornerShape(12.dp)
                    )
                    KeyField(
                        label         = "Image API Key",
                        value         = customImageKey,
                        onValueChange = { customImageKey = it; saved = false },
                        showKey       = showKeys,
                        placeholder   = "key..."
                    )
                    OutlinedTextField(
                        value         = imageModel,
                        onValueChange = { imageModel = it; saved = false },
                        modifier      = Modifier.fillMaxWidth(),
                        label         = { Text("اسم النموذج") },
                        placeholder   = { Text("dall-e-3") },
                        singleLine    = true,
                        shape         = RoundedCornerShape(12.dp)
                    )
                }
            }

            // ---- زر الحفظ ----
            Spacer(Modifier.height(8.dp))

            Button(
                onClick = {
                    settings.provider        = provider
                    settings.geminiKey       = geminiKey
                    settings.geminiModel     = geminiModel
                    settings.openrouterKey   = openrouterKey
                    settings.openrouterModel = openrouterModel
                    settings.openaiKey       = openaiKey
                    settings.openaiModel     = openaiModel
                    settings.mistralKey      = mistralKey
                    settings.mistralModel    = mistralModel
                    settings.groqKey         = groqKey
                    settings.groqModel       = groqModel
                    settings.hordeKey        = hordeKey
                    settings.hordeTextModel  = hordeTextModel
                    settings.hordeImageModel = hordeImageModel
                    settings.customUrl       = customUrl
                    settings.customKey       = customKey
                    settings.customModel     = customModel
                    settings.imageProvider   = imageProvider
                    settings.imageModel      = imageModel
                    settings.customImageUrl  = customImageUrl
                    settings.customImageKey  = customImageKey
                    settings.huggingfaceKey   = huggingfaceKey
settings.huggingfaceModel = huggingfaceModel
                    saved = true
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Filled.Save, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("حفظ الإعدادات", fontWeight = FontWeight.Bold)
            }

            if (saved) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "تم الحفظ بنجاح!",
                            color      = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

// ============================================================
// DynamicModelSelector
// ============================================================

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

        // ---- عنوان + زر تحديث ----
        Row(
            modifier          = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("النموذج", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))

            if (loading) {
                CircularProgressIndicator(
                    modifier    = Modifier.size(20.dp),
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "جاري التحميل...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Filled.Refresh, contentDescription = "تحديث النماذج")
                }
                Text(
                    "${models.size} نموذج",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ---- فلاتر ----
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(
                selected = onlyFree,
                onClick  = { onFreeChange(!onlyFree) },
                label    = { Text("🟢 مجاني") }
            )
            FilterChip(
                selected = onlyRecommended,
                onClick  = { onRecommendedChange(!onlyRecommended) },
                label    = { Text("⭐ موصى به") }
            )
            FilterChip(
                selected = onlyVision,
                onClick  = { onVisionChange(!onlyVision) },
                label    = { Text("🖼️ يدعم الصور") }
            )
        }

        // ---- رسالة الخطأ ----
        if (!error.isNullOrBlank()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors   = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text     = "⚠️ $error\nسيتم الاحتفاظ بالنموذج المحفوظ",
                    modifier = Modifier.padding(12.dp),
                    style    = MaterialTheme.typography.bodySmall,
                    color    = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }

        // ---- القائمة المنسدلة ----
        ExposedDropdownMenuBox(
            expanded         = expanded,
            onExpandedChange = { expanded = !expanded }
        ) {
            OutlinedTextField(
                value         = selectedInfo?.let { modelLabel(it) } ?: selected.ifBlank { "اختر نموذجاً" },
                onValueChange = {},
                readOnly      = true,
                modifier      = Modifier
                    .fillMaxWidth()
                    .menuAnchor(),
                trailingIcon  = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                shape         = RoundedCornerShape(12.dp),
                label         = { Text("النموذج المختار") }
            )

            ExposedDropdownMenu(
                expanded         = expanded,
                onDismissRequest = { expanded = false }
            ) {
                if (models.isEmpty()) {
                    DropdownMenuItem(
                        text    = {
                            Text(
                                if (loading) "جاري التحميل..."
                                else "اضغط 🔄 لتحميل النماذج"
                            )
                        },
                        onClick = { expanded = false }
                    )
                } else {
                    models.forEach { model ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(
                                        text       = modelLabel(model),
                                        fontWeight = if (model.recommended)
                                            FontWeight.Bold else FontWeight.Normal
                                    )
                                    if (model.contextLength > 0) {
                                        Text(
                                            text  = "${model.contextLength / 1000}K context",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
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

private fun modelLabel(model: ModelInfo): String = buildString {
    if (model.name.contains("💾")) { append(model.name); return@buildString }
    if (model.isFree)        append("🟢 ")
    if (model.recommended)   append("⭐ ")
    append(model.name)
    if (model.supportsVision) append(" 🖼️")
}

// ============================================================
// Composables مساعدة
// ============================================================

@Composable
private fun InfoCard(text: String) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Text(
            text     = text,
            modifier = Modifier.padding(12.dp),
            style    = MaterialTheme.typography.bodySmall,
            color    = MaterialTheme.colorScheme.onSecondaryContainer
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
        value                = value,
        onValueChange        = onValueChange,
        modifier             = Modifier.fillMaxWidth(),
        label                = { Text(label) },
        placeholder          = { Text(placeholder) },
        singleLine           = true,
        visualTransformation = if (showKey) VisualTransformation.None
                               else PasswordVisualTransformation(),
        shape = RoundedCornerShape(12.dp)
    )
}
