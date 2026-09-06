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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
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
import androidx.compose.runtime.getValue
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AiSettings,
    onBack: () -> Unit
) {
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
    var customUrl       by remember { mutableStateOf(settings.customUrl) }
    var customKey       by remember { mutableStateOf(settings.customKey) }
    var customModel     by remember { mutableStateOf(settings.customModel) }
    var imageProvider   by remember { mutableStateOf(settings.imageProvider) }
    var imageModel      by remember { mutableStateOf(settings.imageModel) }
    var showKeys        by remember { mutableStateOf(false) }
    var saved           by remember { mutableStateOf(false) }

    val providers = listOf(
        "gemini"     to "Google Gemini ⭐",
        "openrouter" to "OpenRouter",
        "openai"     to "OpenAI",
        "mistral"    to "Mistral AI",
        "groq"       to "Groq",
        "custom"     to "Custom API"
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("الإعدادات") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Filled.ArrowBack,
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
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            // ============================================================
            // قسم المزود الرئيسي
            // ============================================================

            Text(
                "مزود المحادثة:",
                fontWeight = FontWeight.Bold
            )

            providers.forEach { (key, name) ->
                Card(
                    onClick = { provider = key; saved = false },
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
                            onClick  = { provider = key; saved = false }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            name,
                            fontWeight = if (provider == key)
                                FontWeight.Bold
                            else
                                FontWeight.Normal
                        )
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // ============================================================
            // إظهار/إخفاء المفاتيح
            // ============================================================

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "مفاتيح API:",
                    fontWeight = FontWeight.Bold
                )
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

            // ============================================================
            // إعدادات المزود المختار
            // ============================================================

            when (provider) {

                "gemini" -> {
                    InfoCard(
                        "احصل على مفتاح مجاني من:\naistudio.google.com/apikey"
                    )
                    KeyField(
                        label         = "Gemini API Key",
                        value         = geminiKey,
                        onValueChange = { geminiKey = it; saved = false },
                        showKey       = showKeys,
                        placeholder   = "AIza..."
                    )
                    ModelDropdown(
                        label    = "النموذج",
                        models   = listOf(
                            "gemini-3.6-flash"      to "Gemini 3.6 Flash ⭐",
                            "gemini-3.7-flash"      to "Gemini 3.7 Flash",
                            "gemini-3.8-flash"      to "Gemini 3.8 Flash",
                            "gemini-3.5-flash"      to "Gemini 3.5 Flash",
                            "gemini-3.5-flash-lite" to "Gemini 3.5 Flash-Lite",
                            "gemini-2.5-flash"      to "Gemini 2.5 Flash",
                            "gemini-2.5-flash-lite" to "Gemini 2.5 Flash-Lite",
                            "gemini-2.5-pro"        to "Gemini 2.5 Pro",
                            "gemini-flash-latest"   to "Gemini Flash Latest",
                            "gemini-pro-latest"     to "Gemini Pro Latest"
                        ),
                        selected = geminiModel,
                        onSelect = { geminiModel = it; saved = false }
                    )
                }

                "openrouter" -> {
                    InfoCard(
                        "احصل على مفتاح من: openrouter.ai/keys\nبعض النماذج مجانية"
                    )
                    KeyField(
                        label         = "OpenRouter API Key",
                        value         = openrouterKey,
                        onValueChange = { openrouterKey = it; saved = false },
                        showKey       = showKeys,
                        placeholder   = "sk-or-..."
                    )
                    ModelDropdown(
                        label    = "النموذج",
                        models   = listOf(
                            "google/gemini-2.5-flash"                  to "Gemini 2.5 Flash ⭐",
                            "google/gemini-2.5-flash:free"             to "Gemini 2.5 Flash (مجاني)",
                            "google/gemini-2.0-flash-exp:free"         to "Gemini 2.0 Flash (مجاني)",
                            "anthropic/claude-3.5-sonnet"              to "Claude 3.5 Sonnet",
                            "anthropic/claude-opus-4"                  to "Claude Opus 4",
                            "meta-llama/llama-3.2-90b-vision-instruct" to "Llama 3.2 90B Vision",
                            "qwen/qwen-2.5-vl-72b-instruct:free"       to "Qwen 2.5 VL 72B (مجاني)",
                            "mistralai/pixtral-large-2411"             to "Pixtral Large"
                        ),
                        selected = openrouterModel,
                        onSelect = { openrouterModel = it; saved = false }
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
                    ModelDropdown(
                        label    = "النموذج",
                        models   = listOf(
                            "gpt-4o"      to "GPT-4o ⭐",
                            "gpt-4o-mini" to "GPT-4o Mini",
                            "gpt-4-turbo" to "GPT-4 Turbo",
                            "o1-mini"     to "o1 Mini",
                            "o3-mini"     to "o3 Mini"
                        ),
                        selected = openaiModel,
                        onSelect = { openaiModel = it; saved = false }
                    )
                }

                "mistral" -> {
                    InfoCard("احصل على مفتاح من: console.mistral.ai")
                    KeyField(
                        label         = "Mistral API Key",
                        value         = mistralKey,
                        onValueChange = { mistralKey = it; saved = false },
                        showKey       = showKeys,
                        placeholder   = "key..."
                    )
                    ModelDropdown(
                        label    = "النموذج",
                        models   = listOf(
                            "pixtral-large-2411"   to "Pixtral Large ⭐ (Vision)",
                            "pixtral-12b-2409"     to "Pixtral 12B (Vision)",
                            "mistral-large-latest" to "Mistral Large",
                            "mistral-small-latest" to "Mistral Small"
                        ),
                        selected = mistralModel,
                        onSelect = { mistralModel = it; saved = false }
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
                    OutlinedTextField(
                        value         = groqModel,
                        onValueChange = { groqModel = it; saved = false },
                        modifier      = Modifier.fillMaxWidth(),
                        label         = { Text("اسم النموذج") },
                        placeholder   = { Text("llama-3.3-70b-versatile") },
                        singleLine    = true,
                        shape         = RoundedCornerShape(12.dp)
                    )
                }

                "custom" -> {
                    OutlinedTextField(
                        value         = customUrl,
                        onValueChange = { customUrl = it; saved = false },
                        modifier      = Modifier.fillMaxWidth(),
                        label         = { Text("Server URL") },
                        placeholder   = {
                            Text("https://api.example.com/v1/chat/completions")
                        },
                        singleLine    = true,
                        shape         = RoundedCornerShape(12.dp)
                    )
                    KeyField(
                        label         = "API Key",
                        value         = customKey,
                        onValueChange = { customKey = it; saved = false },
                        showKey       = showKeys,
                        placeholder   = "key..."
                    )
                    OutlinedTextField(
                        value         = customModel,
                        onValueChange = { customModel = it; saved = false },
                        modifier      = Modifier.fillMaxWidth(),
                        label         = { Text("اسم النموذج") },
                        placeholder   = { Text("gpt-4o-mini") },
                        singleLine    = true,
                        shape         = RoundedCornerShape(12.dp)
                    )
                }
            }

            // ============================================================
            // قسم توليد الصور
            // ============================================================

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            Text(
                "🎨 إعدادات توليد الصور:",
                fontWeight = FontWeight.Bold
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape    = RoundedCornerShape(12.dp),
                colors   = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                        .copy(alpha = 0.5f)
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
                        "openrouter" to "OpenRouter ⭐ (نماذج مجانية متاحة)",
                        "openai"     to "OpenAI DALL-E (مدفوع)"
                    ).forEach { (key, name) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier          = Modifier.padding(vertical = 2.dp)
                        ) {
                            RadioButton(
                                selected = imageProvider == key,
                                onClick  = {
                                    imageProvider = key
                                    // تغيير النموذج الافتراضي عند تغيير المزود
                                    imageModel = if (key == "openai") {
                                        "dall-e-3"
                                    } else {
                                        "black-forest-labs/flux-schnell:free"
                                    }
                                    saved = false
                                }
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                name,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }

            ModelDropdown(
                label    = "نموذج توليد الصور",
                models   = if (imageProvider == "openai") {
                    listOf(
                        "dall-e-3" to "DALL-E 3 ⭐ (الأفضل)",
                        "dall-e-2" to "DALL-E 2"
                    )
                } else {
                    listOf(
                        "black-forest-labs/flux-schnell:free"       to "FLUX Schnell (مجاني) ⭐",
                        "black-forest-labs/flux-1-schnell:free"     to "FLUX 1 Schnell (مجاني)",
                        "stabilityai/stable-diffusion-xl-base-1.0"  to "Stable Diffusion XL",
                        "openai/dall-e-3"                           to "DALL-E 3 عبر OpenRouter"
                    )
                },
                selected = imageModel,
                onSelect = { imageModel = it; saved = false }
            )

            InfoCard(
                if (imageProvider == "openrouter")
                    "يستخدم نفس مفتاح OpenRouter الموجود أعلاه"
                else
                    "يستخدم نفس مفتاح OpenAI الموجود أعلاه"
            )

            // ============================================================
            // زر الحفظ
            // ============================================================

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
                    settings.customUrl       = customUrl
                    settings.customKey       = customKey
                    settings.customModel     = customModel
                    settings.imageProvider   = imageProvider
                    settings.imageModel      = imageModel
                    saved = true
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Filled.Save, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    "حفظ الإعدادات",
                    fontWeight = FontWeight.Bold
                )
            }

            // رسالة الحفظ
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
                            color = MaterialTheme.colorScheme.primary,
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
// Composables مساعدة
// ============================================================

@Composable
private fun InfoCard(text: String) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
                .copy(alpha = 0.5f)
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
        visualTransformation = if (showKey)
            VisualTransformation.None
        else
            PasswordVisualTransformation(),
        shape = RoundedCornerShape(12.dp)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelDropdown(
    label: String,
    models: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded         = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value         = models.find { it.first == selected }?.second ?: selected,
            onValueChange = {},
            readOnly      = true,
            modifier      = Modifier
                .fillMaxWidth()
                .menuAnchor(),
            trailingIcon  = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded)
            },
            shape = RoundedCornerShape(12.dp),
            label = { Text(label) }
        )
        ExposedDropdownMenu(
            expanded         = expanded,
            onDismissRequest = { expanded = false }
        ) {
            models.forEach { (id, name) ->
                DropdownMenuItem(
                    text    = { Text(name) },
                    onClick = { onSelect(id); expanded = false }
                )
            }
        }
    }
}
