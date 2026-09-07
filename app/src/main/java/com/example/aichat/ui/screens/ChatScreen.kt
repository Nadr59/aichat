package com.example.aichat.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.aichat.data.local.AiSettings
import com.example.aichat.data.model.Message
import com.example.aichat.ui.viewmodel.ChatViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    settings: AiSettings,
    onBack: () -> Unit
) {
    val messages      by viewModel.messages.collectAsState()
    val isLoading     by viewModel.isLoading.collectAsState()
    val error         by viewModel.error.collectAsState()
    val selectedImage by viewModel.selectedImageBase64.collectAsState()
    val isImageMode   by viewModel.isImageGenerationMode.collectAsState()

    var inputText      by remember { mutableStateOf("") }
    var showAttachMenu by remember { mutableStateOf(false) }
    var showModelMenu  by remember { mutableStateOf(false) }

    val listState        = rememberLazyListState()
    val snackbarState    = remember { SnackbarHostState() }
    val scope            = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current

    // النموذج الحالي
    var currentModel by remember {
        mutableStateOf(settings.getActiveModel())
    }

    // قائمة النماذج حسب المزود
    val modelsList = remember(settings.provider) {
        getModelsForProvider(settings.provider)
    }

    // ============================================================
    // Launchers
    // ============================================================

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { viewModel.selectImage(it) } }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicturePreview()
    ) { bitmap -> bitmap?.let { viewModel.selectBitmap(it) } }

    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { viewModel.selectFile(it) } }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (isImageMode) "🎨 توليد الصور"
                        else "💬 المحادثة",
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "رجوع")
                    }
                },
                actions = {
                    // زر اختيار النموذج
                    IconButton(onClick = { showModelMenu = true }) {
                        Icon(
                            Icons.Filled.SmartToy,
                            contentDescription = "النموذج",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    // زر التبديل بين المحادثة وتوليد الصور
                    IconButton(onClick = { viewModel.toggleImageGenerationMode() }) {
                        Icon(
                            if (isImageMode) Icons.Filled.Chat
                            else Icons.Filled.AutoAwesome,
                            contentDescription = null,
                            tint = if (isImageMode)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurface
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
                .imePadding()
        ) {

            // شريط النموذج الحالي
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                        .copy(alpha = 0.4f)
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier          = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.SmartToy,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint     = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text  = currentModel.ifBlank { "لم يتم اختيار نموذج" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            }

            // شريط وضع توليد الصور
            if (isImageMode) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        "🎨 اكتب وصفاً للصورة التي تريدها",
                        modifier = Modifier.padding(10.dp),
                        style    = MaterialTheme.typography.bodySmall,
                        color    = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            // قائمة الرسائل
            LazyColumn(
                state   = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                item { Spacer(Modifier.height(8.dp)) }

                items(items = messages, key = { it.id }) { message ->
                    MessageBubble(
                        message = message,
                        onCopy  = { text ->
                            clipboardManager.setText(AnnotatedString(text))
                            scope.launch {
                                snackbarState.showSnackbar("تم النسخ ✓")
                            }
                        }
                    )
                }

                if (isLoading) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                            horizontalArrangement = Arrangement.Start,
                            verticalAlignment     = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier    = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (isImageMode) "جاري توليد الصورة..."
                                else "جاري الرد...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }
                }

                error?.let { errorMsg ->
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors   = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier          = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text     = errorMsg,
                                    modifier = Modifier.weight(1f),
                                    color    = MaterialTheme.colorScheme.onErrorContainer,
                                    style    = MaterialTheme.typography.bodySmall
                                )
                                IconButton(
                                    onClick  = { viewModel.clearError() },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = "إغلاق",
                                        tint = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }
                        }
                    }
                }

                item { Spacer(Modifier.height(8.dp)) }
            }

            // معاينة الصورة المختارة
            if (selectedImage != null) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 4.dp)
                ) {
                    AsyncImage(
                        model              = android.util.Base64.decode(
                            selectedImage,
                            android.util.Base64.NO_WRAP
                        ),
                        contentDescription = "صورة مختارة",
                        modifier           = Modifier
                            .height(80.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale       = ContentScale.Fit
                    )
                    IconButton(
                        onClick  = { viewModel.clearSelectedImage() },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(24.dp)
                            .background(
                                MaterialTheme.colorScheme.error,
                                CircleShape
                            )
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "إزالة",
                            tint     = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            // شريط الإدخال
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                if (!isImageMode) {
                    IconButton(
                        onClick  = { showAttachMenu = true },
                        modifier = Modifier.padding(bottom = 4.dp)
                    ) {
                        Icon(
                            Icons.Filled.AttachFile,
                            contentDescription = "إرفاق",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                OutlinedTextField(
                    value         = inputText,
                    onValueChange = { inputText = it },
                    modifier      = Modifier.weight(1f),
                    placeholder   = {
                        Text(
                            if (isImageMode) "صف الصورة التي تريدها..."
                            else "اكتب رسالتك..."
                        )
                    },
                    shape    = RoundedCornerShape(24.dp),
                    maxLines = 5
                )

                Spacer(Modifier.width(8.dp))

                IconButton(
                    onClick  = {
                        if (!isLoading &&
                            (inputText.isNotBlank() || selectedImage != null)
                        ) {
                            viewModel.sendMessage(inputText)
                            inputText = ""
                        }
                    },
                    modifier = Modifier
                        .padding(bottom = 4.dp)
                        .background(
                            color = if (isLoading)
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                            else
                                MaterialTheme.colorScheme.primary,
                            shape = CircleShape
                        )
                ) {
                    Icon(
                        Icons.Filled.Send,
                        contentDescription = "إرسال",
                        tint = Color.White
                    )
                }
            }
        }
    }

    // ============================================================
    // قائمة الإرفاق
    // ============================================================

    if (showAttachMenu) {
        ModalBottomSheet(
            onDismissRequest = { showAttachMenu = false },
            sheetState       = rememberModalBottomSheetState()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "إرفاق",
                    fontWeight = FontWeight.Bold,
                    style      = MaterialTheme.typography.titleMedium,
                    modifier   = Modifier.padding(bottom = 8.dp)
                )
                AttachOption(
                    icon    = Icons.Filled.Image,
                    label   = "صورة من المعرض",
                    onClick = {
                        showAttachMenu = false
                        galleryLauncher.launch("image/*")
                    }
                )
                AttachOption(
                    icon    = Icons.Filled.Camera,
                    label   = "التقاط صورة بالكاميرا",
                    onClick = {
                        showAttachMenu = false
                        cameraLauncher.launch(null)
                    }
                )
                AttachOption(
                    icon    = Icons.Filled.InsertDriveFile,
                    label   = "ملف",
                    onClick = {
                        showAttachMenu = false
                        fileLauncher.launch("*/*")
                    }
                )
            }
        }
    }

    // ============================================================
    // قائمة النماذج
    // ============================================================

    if (showModelMenu) {
        ModalBottomSheet(
            onDismissRequest = { showModelMenu = false },
            sheetState       = rememberModalBottomSheetState()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "اختر النموذج",
                    fontWeight = FontWeight.Bold,
                    style      = MaterialTheme.typography.titleMedium
                )

                Text(
                    "المزود: ${settings.provider}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )

                Spacer(Modifier.height(8.dp))

                if (modelsList.isEmpty()) {
                    Text(
                        "لا توجد نماذج محفوظة لهذا المزود.\nأدخل النموذج يدوياً من الإعدادات.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                } else {
                    modelsList.forEach { (modelId, modelName) ->
                        Card(
                            onClick = {
                                // حفظ النموذج المختار
                                when (settings.provider) {
                                    "gemini"     -> settings.geminiModel     = modelId
                                    "openrouter" -> settings.openrouterModel = modelId
                                    "openai"     -> settings.openaiModel     = modelId
                                    "mistral"    -> settings.mistralModel    = modelId
                                    "groq"       -> settings.groqModel       = modelId
                                    "custom"     -> settings.customModel     = modelId
                                }
                                currentModel = modelId
                                showModelMenu = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape    = RoundedCornerShape(12.dp),
                            colors   = CardDefaults.cardColors(
                                containerColor = if (currentModel == modelId)
                                    MaterialTheme.colorScheme.primaryContainer
                                else
                                    MaterialTheme.colorScheme.surfaceVariant
                                        .copy(alpha = 0.5f)
                            )
                        ) {
                            Row(
                                modifier          = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text       = modelName,
                                        fontWeight = if (currentModel == modelId)
                                            FontWeight.Bold else FontWeight.Normal,
                                        style      = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        text  = modelId,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                            .copy(alpha = 0.5f)
                                    )
                                }
                                if (currentModel == modelId) {
                                    Icon(
                                        Icons.Filled.Chat,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

// ============================================================
// قائمة النماذج حسب المزود
// ============================================================

private fun getModelsForProvider(
    provider: String
): List<Pair<String, String>> {

    return when (provider.lowercase().trim()) {

        "gemini" -> listOf(
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
        )

        "openrouter" -> listOf(
            "google/gemini-2.5-flash"                  to "Gemini 2.5 Flash ⭐",
            "google/gemini-2.5-flash:free"             to "Gemini 2.5 Flash (مجاني)",
            "google/gemini-2.0-flash-exp:free"         to "Gemini 2.0 Flash (مجاني)",
            "anthropic/claude-3.5-sonnet"              to "Claude 3.5 Sonnet",
            "anthropic/claude-opus-4"                  to "Claude Opus 4",
            "meta-llama/llama-3.2-90b-vision-instruct" to "Llama 3.2 90B Vision",
            "qwen/qwen-2.5-vl-72b-instruct:free"       to "Qwen 2.5 VL 72B (مجاني)",
            "mistralai/pixtral-large-2411"             to "Pixtral Large"
        )

        "openai" -> listOf(
            "gpt-4o"      to "GPT-4o ⭐",
            "gpt-4o-mini" to "GPT-4o Mini",
            "gpt-4-turbo" to "GPT-4 Turbo",
            "o1-mini"     to "o1 Mini",
            "o3-mini"     to "o3 Mini"
        )

        "mistral" -> listOf(
            "pixtral-large-2411"   to "Pixtral Large ⭐ (Vision)",
            "pixtral-12b-2409"     to "Pixtral 12B (Vision)",
            "mistral-large-latest" to "Mistral Large",
            "mistral-small-latest" to "Mistral Small"
        )

        "groq" -> listOf(
            "llama-3.3-70b-versatile"    to "Llama 3.3 70B",
            "llama-3.1-8b-instant"       to "Llama 3.1 8B",
            "mixtral-8x7b-32768"         to "Mixtral 8x7B",
            "gemma2-9b-it"               to "Gemma 2 9B"
        )

        else -> emptyList()
    }
}

// ============================================================
// فقاعة الرسالة
// ============================================================

@Composable
private fun MessageBubble(
    message: Message,
    onCopy: (String) -> Unit
) {
    val isUser = message.role == "user"

    Row(
        modifier              = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier            = Modifier.widthIn(max = 300.dp),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
        ) {
            // صورة مرفقة
            message.imageBase64?.let { base64 ->
                if (message.generatedImageUrl == null) {
                    AsyncImage(
                        model              = android.util.Base64.decode(
                            base64,
                            android.util.Base64.NO_WRAP
                        ),
                        contentDescription = "صورة",
                        modifier           = Modifier
                            .height(150.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .padding(bottom = 4.dp),
                        contentScale       = ContentScale.Fit
                    )
                }
            }

            // صورة مولّدة
            message.generatedImageUrl?.let { url ->
                AsyncImage(
                    model              = url,
                    contentDescription = "صورة مولّدة",
                    modifier           = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .padding(bottom = 4.dp),
                    contentScale       = ContentScale.FillWidth
                )
            }

            // نص الرسالة
            if (message.content.isNotBlank() &&
                message.generatedImageUrl == null
            ) {
                Card(
                    shape = RoundedCornerShape(
                        topStart    = if (isUser) 16.dp else 4.dp,
                        topEnd      = if (isUser) 4.dp else 16.dp,
                        bottomStart = 16.dp,
                        bottomEnd   = 16.dp
                    ),
                    colors = CardDefaults.cardColors(
                        // رمادي فاتح للمستخدم — بدون لون للذكاء
                        containerColor = if (isUser)
                            MaterialTheme.colorScheme.surfaceVariant
                        else
                            Color.Transparent
                    ),
                    elevation = CardDefaults.cardElevation(
                        defaultElevation = if (isUser) 1.dp else 0.dp
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(
                            horizontal = 12.dp,
                            vertical   = 8.dp
                        )
                    ) {
                        Text(
                            text  = message.content,
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodyMedium
                        )

                        // زر النسخ
                        Row(
                            modifier              = Modifier.fillMaxWidth(),
                            horizontalArrangement = if (isUser)
                                Arrangement.Start else Arrangement.End
                        ) {
                            IconButton(
                                onClick  = { onCopy(message.content) },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    Icons.Filled.ContentCopy,
                                    contentDescription = "نسخ",
                                    modifier = Modifier.size(14.dp),
                                    tint     = MaterialTheme.colorScheme.onSurface
                                        .copy(alpha = 0.35f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ============================================================
// خيار الإرفاق
// ============================================================

@Composable
private fun AttachOption(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Card(
        onClick  = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(12.dp),
        colors   = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
                .copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier          = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(12.dp))
            Text(label)
        }
    }
}
