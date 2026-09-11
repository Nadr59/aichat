package com.example.aichat.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.example.aichat.data.local.AiSettings
import com.example.aichat.data.model.Message
import com.example.aichat.ui.viewmodel.ChatViewModel
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    settings: AiSettings,
    onBack: () -> Unit
) {
    val messages by viewModel.messages.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val isImageMode by viewModel.isImageGenerationMode.collectAsState()
    val selectedImage by viewModel.selectedImageBase64.collectAsState()
val customRequestCount by
    viewModel.customRequestCount.collectAsState()
    var inputText by remember {
        mutableStateOf("")
    }

    val listState = rememberLazyListState()
    val context = LocalContext.current

    // ------------------------------------------------------------
    // اختيار صورة
    // ------------------------------------------------------------

    val imagePicker =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetContent()
        ) { uri ->

            uri?.let {
                viewModel.selectImage(it)
            }
        }

    // ------------------------------------------------------------
    // نسخ النص
    // ------------------------------------------------------------

    fun copyToClipboard(text: String) {

        val clipboard =
            context.getSystemService(
                Context.CLIPBOARD_SERVICE
            ) as ClipboardManager

        clipboard.setPrimaryClip(
            ClipData.newPlainText(
                "message",
                text
            )
        )
    }

    // ------------------------------------------------------------
    // التمرير لآخر رسالة
    // ------------------------------------------------------------

    LaunchedEffect(
        messages.size,
        isLoading
    ) {

        val totalItems =
            messages.size +
                    if (isLoading) 1 else 0

        if (totalItems > 0) {

            listState.animateScrollToItem(
                totalItems - 1
            )
        }
    }

    Scaffold(

        topBar = {

            TopAppBar(

                title = {

                    Column {

                        Text(
                            text =
                                if (isImageMode)
                                    "توليد الصور"
                                else
                                    "المحادثة",

                            fontWeight =
                                FontWeight.SemiBold
                        )

                        Text(
                            text =
                                settings
                                    .getActiveModel()
                                    .take(35),

                            style =
                                MaterialTheme
                                    .typography
                                    .labelSmall,

                            color =
                                MaterialTheme
                                    .colorScheme
                                    .onSurfaceVariant
                        )
                    }
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
                },

                actions = {

                    IconButton(

                        onClick = {
                            viewModel
                                .toggleImageGenerationMode()
                        }

                    ) {

                        Icon(

                            imageVector =
                                if (isImageMode)
                                    Icons.Filled.Chat
                                else
                                    Icons.Filled.Image,

                            contentDescription =
                                if (isImageMode)
                                    "وضع المحادثة"
                                else
                                    "وضع الصور",

                            tint =
                                if (isImageMode)
                                    MaterialTheme
                                        .colorScheme
                                        .primary
                                else
                                    MaterialTheme
                                        .colorScheme
                                        .onSurface
                        )
                    }
                },

                colors =
                    TopAppBarDefaults
                        .topAppBarColors(
                            containerColor =
                                MaterialTheme
                                    .colorScheme
                                    .background
                        )
            )
        },

        bottomBar = {

            InputBar(

                inputText = inputText,

                isLoading = isLoading,

                isImageMode = isImageMode,

                hasSelectedImage =
                    selectedImage != null,

                onAttach = {
                    imagePicker.launch("image/*")
                },

                onRemoveImage = {
                    viewModel.clearSelectedImage()
                },

                onTextChange = {
                    inputText = it
                },

                onSend = {

                    if (
                        !isLoading &&
                        (
                            inputText.isNotBlank() ||
                                    selectedImage != null
                            )
                    ) {

                        viewModel.sendMessage(
                            inputText.trim()
                        )

                        inputText = ""
                    }
                }
            )
        }

    ) { padding ->

        Column(

            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
        ) {

            // ----------------------------------------------------
            // الرسائل
            // ----------------------------------------------------

            if (messages.isEmpty()) {

                EmptyChatView(
                    isImageMode = isImageMode
                )

            } else {

                LazyColumn(

                    state = listState,

                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxWidth(),

                    contentPadding =
                        PaddingValues(
                            horizontal = 12.dp,
                            vertical = 16.dp
                        ),

                    verticalArrangement =
                        Arrangement.spacedBy(14.dp)
                ) {

                    items(
                        items = messages,
                        key = {
                            it.id
                        }
                    ) { message ->

                        MessageBubble(

                            message = message,

                            onCopy = {
                                copyToClipboard(it)
                            }
                        )
                    }

                    if (isLoading) {

                        item(
                            key = "thinking"
                        ) {

                            ThinkingBubble()
                        }
                    }
                }
            }

            // ----------------------------------------------------
            // الخطأ
            // ----------------------------------------------------

            error?.let { err ->

                ErrorMessage(

                    error = err,

                    onDismiss = {
                        viewModel.clearError()
                    },

                    onCopy = {
                        copyToClipboard(err)
                    }
                )
            }
        }
    }
}

// ============================================================
// شاشة البداية
// ============================================================

@Composable
private fun EmptyChatView(
    isImageMode: Boolean
) {

    Box(
        modifier =
            Modifier.fillMaxSize(),

        contentAlignment =
            Alignment.Center
    ) {

        Column(
            horizontalAlignment =
                Alignment.CenterHorizontally
        ) {

            Surface(
                modifier =
                    Modifier.size(64.dp),

                shape = CircleShape,

                color =
                    MaterialTheme
                        .colorScheme
                        .primaryContainer
            ) {

                Box(
                    contentAlignment =
                        Alignment.Center
                ) {

                    Icon(
                        imageVector =
                            if (isImageMode)
                                Icons.Filled.Image
                            else
                                Icons.Filled.Chat,

                        contentDescription =
                            null,

                        modifier =
                            Modifier.size(30.dp),

                        tint =
                            MaterialTheme
                                .colorScheme
                                .onPrimaryContainer
                    )
                }
            }

            Spacer(
                Modifier.size(16.dp)
            )

            Text(
                text =
                    if (isImageMode)
                        "ماذا تريد أن تصنع؟"
                    else
                        "كيف يمكنني مساعدتك؟",

                style =
                    MaterialTheme
                        .typography
                        .titleLarge,

                fontWeight =
                    FontWeight.SemiBold
            )

            Spacer(
                Modifier.size(6.dp)
            )

            Text(
                text =
                    if (isImageMode)
                        "اكتب وصف الصورة التي تريد توليدها"
                    else
                        "اكتب رسالتك لبدء المحادثة",

                style =
                    MaterialTheme
                        .typography
                        .bodyMedium,

                color =
                    MaterialTheme
                        .colorScheme
                        .onSurfaceVariant
            )
        }
    }
}

// ============================================================
// شريط الإدخال
// ============================================================

@Composable
private fun InputBar(
    inputText: String,
    isLoading: Boolean,
    isImageMode: Boolean,
    hasSelectedImage: Boolean,
    onAttach: () -> Unit,
    onRemoveImage: () -> Unit,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit
) {

    Surface(

        modifier =
            Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding(),

        color =
            MaterialTheme
                .colorScheme
                .background
    ) {

        Column(

            modifier =
                Modifier.padding(
                    horizontal = 10.dp,
                    vertical = 8.dp
                )
        ) {

            // ----------------------------------------------------
            // معاينة الصورة
            // ----------------------------------------------------

            if (hasSelectedImage) {

                Surface(

                    modifier =
                        Modifier
                            .padding(
                                bottom = 6.dp
                            ),

                    shape =
                        RoundedCornerShape(12.dp),

                    color =
                        MaterialTheme
                            .colorScheme
                            .secondaryContainer
                ) {

                    Row(

                        modifier =
                            Modifier.padding(
                                horizontal = 10.dp,
                                vertical = 6.dp
                            ),

                        verticalAlignment =
                            Alignment.CenterVertically
                    ) {

                        Icon(
                            imageVector =
                                Icons.Filled.Image,

                            contentDescription =
                                null,

                            modifier =
                                Modifier.size(20.dp),

                            tint =
                                MaterialTheme
                                    .colorScheme
                                    .onSecondaryContainer
                        )

                        Spacer(
                            Modifier.width(8.dp)
                        )

                        Text(
                            text = "تم إرفاق صورة",

                            modifier =
                                Modifier.weight(1f),

                            style =
                                MaterialTheme
                                    .typography
                                    .bodySmall,

                            color =
                                MaterialTheme
                                    .colorScheme
                                    .onSecondaryContainer
                        )

                        IconButton(
                            onClick =
                                onRemoveImage,

                            modifier =
                                Modifier.size(28.dp)
                        ) {

                            Icon(
                                imageVector =
                                    Icons.Filled.Close,

                                contentDescription =
                                    "إزالة الصورة",

                                modifier =
                                    Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            // ----------------------------------------------------
            // الحقل الرئيسي
            // ----------------------------------------------------

            Row(

                modifier =
                    Modifier.fillMaxWidth(),

                verticalAlignment =
                    Alignment.Bottom
            ) {

                Surface(

                    modifier =
                        Modifier.weight(1f),

                    shape =
                        RoundedCornerShape(26.dp),

                    tonalElevation = 2.dp,

                    color =
                        MaterialTheme
                            .colorScheme
                            .surfaceVariant
                ) {

                    Row(

                        modifier =
                            Modifier.fillMaxWidth(),

                        verticalAlignment =
                            Alignment.Bottom
                    ) {

                        IconButton(

                            onClick = onAttach,

                            enabled = !isLoading
                        ) {

                            Icon(
                                imageVector =
                                    Icons.Filled.AttachFile,

                                contentDescription =
                                    "إرفاق صورة"
                            )
                        }

                        OutlinedTextField(

                            value = inputText,

                            onValueChange =
                                onTextChange,

                            modifier =
                                Modifier.weight(1f),

                            placeholder = {

                                Text(
                                    text =
                                        if (isImageMode)
                                            "صف الصورة..."
                                        else
                                            "اكتب رسالة..."
                                )
                            },

                            enabled =
                                !isLoading,

                            maxLines = 5,

                            shape =
                                RoundedCornerShape(26.dp),

                            colors =
                                androidx.compose.material3
                                    .OutlinedTextFieldDefaults
                                    .colors(
                                        unfocusedContainerColor =
                                            androidx.compose.ui.graphics
                                                .Color.Transparent,

                                        focusedContainerColor =
                                            androidx.compose.ui.graphics
                                                .Color.Transparent,

                                        unfocusedBorderColor =
                                            androidx.compose.ui.graphics
                                                .Color.Transparent,

                                        focusedBorderColor =
                                            androidx.compose.ui.graphics
                                                .Color.Transparent
                                    )
                        )
                    }
                }

                Spacer(
                    Modifier.width(8.dp)
                )

                val canSend =
                    !isLoading &&
                            (
                                inputText.isNotBlank() ||
                                        hasSelectedImage
                                )

                FloatingActionButton(

                    onClick = onSend,

                    modifier =
                        Modifier.size(50.dp),

                    shape = CircleShape,

                    containerColor =
                        if (canSend)
                            MaterialTheme
                                .colorScheme
                                .primary
                        else
                            MaterialTheme
                                .colorScheme
                                .surfaceVariant
                ) {

                    if (isLoading) {

                        CircularProgressIndicator(

                            modifier =
                                Modifier.size(21.dp),

                            strokeWidth = 2.dp,

                            color =
                                MaterialTheme
                                    .colorScheme
                                    .primary
                        )

                    } else {

                        Icon(

                            imageVector =
                                Icons.Filled.Send,

                            contentDescription =
                                "إرسال",

                            tint =
                                if (canSend)
                                    MaterialTheme
                                        .colorScheme
                                        .onPrimary
                                else
                                    MaterialTheme
                                        .colorScheme
                                        .onSurfaceVariant
                        )
                    }
                }
            }

            Text(

                text =
                    if (isImageMode)
                        "يمكنك إرفاق صورة أو كتابة وصف"
                    else
                        "اضغط مطولاً على الرسالة لنسخها",

                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(
                            top = 4.dp
                        ),

                style =
                    MaterialTheme
                        .typography
                        .labelSmall,

                color =
                    MaterialTheme
                        .colorScheme
                        .onSurfaceVariant.copy(
                            alpha = 0.55f
                        )
            )
        }
    }
}

// ============================================================
// فقاعة التفكير
// ============================================================

@Composable
private fun ThinkingBubble() {

    Row(
        modifier =
            Modifier.fillMaxWidth(),

        horizontalArrangement =
            Arrangement.Start
    ) {

        Surface(

            shape =
                RoundedCornerShape(
                    topStart = 6.dp,
                    topEnd = 18.dp,
                    bottomStart = 18.dp,
                    bottomEnd = 18.dp
                ),

            color =
                MaterialTheme
                    .colorScheme
                    .surfaceVariant
        ) {

            Row(

                modifier =
                    Modifier.padding(
                        horizontal = 16.dp,
                        vertical = 12.dp
                    ),

                verticalAlignment =
                    Alignment.CenterVertically
            ) {

                CircularProgressIndicator(

                    modifier =
                        Modifier.size(16.dp),

                    strokeWidth = 2.dp
                )

                Spacer(
                    Modifier.width(9.dp)
                )

                Text(
                    text = "جاري التفكير...",

                    style =
                        MaterialTheme
                            .typography
                            .bodySmall,

                    color =
                        MaterialTheme
                            .colorScheme
                            .onSurfaceVariant
                )
            }
        }
    }
}

// ============================================================
// فقاعة الرسالة
// ============================================================

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: Message,
    onCopy: (String) -> Unit
) {

    val isUser =
        message.role == "user"

    var showCopied by remember {
        mutableStateOf(false)
    }

    LaunchedEffect(showCopied) {

        if (showCopied) {

            delay(1500)

            showCopied = false
        }
    }

    Column(

        modifier =
            Modifier.fillMaxWidth(),

        horizontalAlignment =
            if (isUser)
                Alignment.End
            else
                Alignment.Start
    ) {

        Surface(

            modifier =
                Modifier
                    .widthIn(
                        max =
                            if (isUser)
                                320.dp
                            else
                                360.dp
                    )
                    .combinedClickable(

                        onClick = {},

                        onLongClick = {

                            onCopy(
                                message.content
                            )

                            showCopied = true
                        }
                    ),

            shape =
                RoundedCornerShape(
                    topStart =
                        if (isUser)
                            18.dp
                        else
                            6.dp,

                    topEnd =
                        if (isUser)
                            6.dp
                        else
                            18.dp,

                    bottomStart = 18.dp,

                    bottomEnd = 18.dp
                ),

            color =
                if (isUser)
                    MaterialTheme
                        .colorScheme
                        .primary
                else
                    MaterialTheme
                        .colorScheme
                        .surfaceVariant
        ) {

            Column(

                modifier =
                    Modifier.padding(
                        horizontal = 14.dp,
                        vertical = 11.dp
                    )
            ) {

                Text(

                    text = message.content,

                    style =
                        MaterialTheme
                            .typography
                            .bodyLarge,

                    color =
                        if (isUser)
                            MaterialTheme
                                .colorScheme
                                .onPrimary
                        else
                            MaterialTheme
                                .colorScheme
                                .onSurfaceVariant
                )

                if (showCopied) {

                    Spacer(
                        Modifier.size(5.dp)
                    )

                    Text(

                        text = "✓ تم النسخ",

                        style =
                            MaterialTheme
                                .typography
                                .labelSmall,

                        color =
                            if (isUser)
                                MaterialTheme
                                    .colorScheme
                                    .onPrimary.copy(
                                        alpha = 0.7f
                                    )
                            else
                                MaterialTheme
                                    .colorScheme
                                    .primary
                    )
                }
            }
        }
    }
}

// ============================================================
// رسالة الخطأ
// ============================================================

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ErrorMessage(
    error: String,
    onDismiss: () -> Unit,
    onCopy: () -> Unit
) {

    Card(

        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = 10.dp,
                    vertical = 4.dp
                )
                .combinedClickable(
                    onClick = {},
                    onLongClick = onCopy
                ),

        colors =
            CardDefaults.cardColors(
                containerColor =
                    MaterialTheme
                        .colorScheme
                        .errorContainer
            ),

        shape =
            RoundedCornerShape(14.dp)
    ) {

        Row(

            modifier =
                Modifier.padding(10.dp),

            verticalAlignment =
                Alignment.CenterVertically
        ) {

            Text(

                text = error,

                modifier =
                    Modifier.weight(1f),

                style =
                    MaterialTheme
                        .typography
                        .bodySmall,

                color =
                    MaterialTheme
                        .colorScheme
                        .onErrorContainer
            )

            IconButton(
                onClick = onDismiss
            ) {

                Icon(
                    imageVector =
                        Icons.Filled.Close,

                    contentDescription =
                        "إغلاق",

                    tint =
                        MaterialTheme
                            .colorScheme
                            .onErrorContainer
                )
            }
        }
    }
}
