package com.example.aichat.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.aichat.data.local.AiSettings
import com.example.aichat.data.model.Message
import com.example.aichat.ui.viewmodel.ChatViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    settings: AiSettings,
    onBack: () -> Unit
) {
    val messages      by viewModel.messages.collectAsState()
    val isLoading     by viewModel.isLoading.collectAsState()
    val error         by viewModel.error.collectAsState()
    val isImageMode   by viewModel.isImageGenerationMode.collectAsState()

    var inputText     by remember { mutableStateOf("") }
    val listState     = rememberLazyListState()
    val scope         = rememberCoroutineScope()
    val context       = LocalContext.current

    fun copyToClipboard(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("msg", text))
    }

    // ✅ تمرير للأسفل عند كل تغيير في الرسائل أو التحميل
    LaunchedEffect(messages.size, isLoading) {
        val total = messages.size + if (isLoading) 1 else 0
        if (total > 0) {
            scope.launch {
                listState.animateScrollToItem(total - 1)
            }
        }
    }

    Scaffold(
        // ✅ مهم: يجعل Scaffold يحترم الـ IME (الكيبورد)
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text       = if (isImageMode) "🎨 توليد صور" else "💬 محادثة",
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text  = settings.getActiveModel().take(30),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "رجوع")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.toggleImageGenerationMode() }) {
                        Icon(
                            imageVector = if (isImageMode)
                                Icons.Filled.Chat else Icons.Filled.Image,
                            contentDescription = "تبديل الوضع",
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
                // ✅ هذا يرفع المحتوى عند ظهور الكيبورد
                .windowInsetsPadding(WindowInsets.ime)
        ) {

            // ✅ قائمة الرسائل تأخذ كل المساحة المتاحة
            LazyColumn(
                state               = listState,
                modifier            = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding      = PaddingValues(vertical = 8.dp)
            ) {

                // ✅ الرسائل المحفوظة
                items(
                    items = messages,
                    key   = { it.id }
                ) { message ->
                    MessageBubble(
                        message = message,
                        onCopy  = { copyToClipboard(it) }
                    )
                }

                // ✅ مؤشر التفكير يظهر بعد رسالة المستخدم مباشرة
                if (isLoading) {
                    item(key = "thinking") {
                        ThinkingBubble()
                    }
                }
            }

            // ✅ رسالة الخطأ
            error?.let { err ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .combinedClickable(
                            onClick    = {},
                            onLongClick = { copyToClipboard(err) }
                        ),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier          = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text     = err,
                            color    = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f),
                            style    = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.width(8.dp))
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

            // ✅ حقل الإدخال في الأسفل دائماً
            InputBar(
                inputText   = inputText,
                isLoading   = isLoading,
                isImageMode = isImageMode,
                onTextChange = { inputText = it },
                onSend = {
                    if (!isLoading && inputText.isNotBlank()) {
                        viewModel.sendMessage(inputText.trim())
                        inputText = ""
                    }
                }
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
    onTextChange: (String) -> Unit,
    onSend: () -> Unit
) {
    Row(
        modifier          = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        OutlinedTextField(
            value         = inputText,
            onValueChange = onTextChange,
            modifier      = Modifier.weight(1f),
            placeholder   = {
                Text(
                    if (isImageMode) "صف الصورة التي تريدها..."
                    else "اكتب رسالتك..."
                )
            },
            maxLines = 5,
            shape    = RoundedCornerShape(24.dp),
            enabled  = !isLoading
        )

        Spacer(Modifier.width(8.dp))

        FloatingActionButton(
            onClick        = onSend,
            modifier       = Modifier.size(52.dp),
            containerColor = if (isLoading || inputText.isBlank())
                MaterialTheme.colorScheme.surfaceVariant
            else
                MaterialTheme.colorScheme.primary
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier    = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color       = MaterialTheme.colorScheme.primary
                )
            } else {
                Icon(
                    Icons.Filled.Send,
                    contentDescription = "إرسال",
                    tint = if (inputText.isBlank())
                        MaterialTheme.colorScheme.onSurfaceVariant
                    else
                        MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    }
}

// ============================================================
// فقاعة التفكير
// ============================================================

@Composable
private fun ThinkingBubble() {
    Row(
        modifier          = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Start
    ) {
        Card(
            shape  = RoundedCornerShape(
                topStart    = 4.dp,
                topEnd      = 16.dp,
                bottomStart = 16.dp,
                bottomEnd   = 16.dp
            ),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Row(
                modifier          = Modifier.padding(
                    horizontal = 16.dp,
                    vertical   = 12.dp
                ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    modifier    = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color       = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text  = "جاري التفكير...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
    val isUser       = message.role == "user"
    var showCopied   by remember { mutableStateOf(false) }

    LaunchedEffect(showCopied) {
        if (showCopied) {
            kotlinx.coroutines.delay(2000)
            showCopied = false
        }
    }

    Column(
        modifier            = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {

        Card(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .combinedClickable(
                    onClick     = {},
                    onLongClick = {
                        onCopy(message.content)
                        showCopied = true
                    }
                ),
            shape = RoundedCornerShape(
                topStart    = if (isUser) 16.dp else 4.dp,
                topEnd      = if (isUser) 4.dp  else 16.dp,
                bottomStart = 16.dp,
                bottomEnd   = 16.dp
            ),
            colors = CardDefaults.cardColors(
                containerColor = if (isUser)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {

                Text(
                    text  = message.content,
                    color = if (isUser)
                        MaterialTheme.colorScheme.onPrimary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )

                // ✅ تأكيد النسخ
                if (showCopied) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text  = "✅ تم النسخ",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isUser)
                            MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                        else
                            MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // ✅ تلميح صغير
        Text(
            text     = if (isUser) "اضغط مطولاً للنسخ" else "اضغط مطولاً للنسخ",
            style    = MaterialTheme.typography.labelSmall,
            color    = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
        )
    }
}
