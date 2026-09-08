package com.example.aichat.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    val messages          by viewModel.messages.collectAsState()
    val isLoading         by viewModel.isLoading.collectAsState()
    val error             by viewModel.error.collectAsState()
    val isImageMode       by viewModel.isImageGenerationMode.collectAsState()
    val selectedImage     by viewModel.selectedImageBase64.collectAsState()

    var inputText         by remember { mutableStateOf("") }
    val listState         = rememberLazyListState()
    val scope             = rememberCoroutineScope()
    val context           = LocalContext.current

    // ✅ نسخ النص
    fun copyToClipboard(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("message", text))
    }

    // ✅ التمرير للأسفل عند وصول رسالة جديدة
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = if (isImageMode) "🎨 توليد صور" else "💬 محادثة",
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = settings.getActiveModel().take(30),
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
                    // ✅ زر تبديل وضع الصور
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
        },
        bottomBar = {
            Column {
                // ✅ رسالة الخطأ مع إمكانية النسخ
                error?.let { err ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .combinedClickable(
                                onClick = {},
                                onLongClick = {
                                    copyToClipboard(err)
                                }
                            ),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text     = err,
                                color    = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.weight(1f),
                                style    = MaterialTheme.typography.bodySmall
                            )
                            IconButton(
                                onClick = { viewModel.clearError() },
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

                // ✅ حقل الإدخال
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
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
                        maxLines = 4,
                        shape    = RoundedCornerShape(24.dp)
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    // ✅ زر الإرسال
                    FloatingActionButton(
                        onClick = {
                            if (!isLoading && inputText.isNotBlank()) {
                                viewModel.sendMessage(inputText.trim())
                                inputText = ""
                            }
                        },
                        modifier          = Modifier.size(56.dp),
                        containerColor    = if (isLoading)
                            MaterialTheme.colorScheme.surfaceVariant
                        else
                            MaterialTheme.colorScheme.primary
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier  = Modifier.size(24.dp),
                                color     = MaterialTheme.colorScheme.primary,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Filled.Send, contentDescription = "إرسال")
                        }
                    }
                }
            }
        }
    ) { padding ->

        LazyColumn(
            state    = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding      = PaddingValues(vertical = 8.dp)
        ) {
            items(
                items = messages,
                key   = { it.id }
            ) { message ->
                MessageBubble(
                    message = message,
                    onCopy  = { copyToClipboard(it) }
                )
            }

            // ✅ مؤشر التحميل
            if (isLoading) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.Start
                    ) {
                        Card(
                            shape  = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier    = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text  = "جاري التفكير...",
                                    style = MaterialTheme.typography.bodySmall
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
// فقاعة الرسالة مع النسخ
// ============================================================

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: Message,
    onCopy: (String) -> Unit
) {
    val isUser        = message.role == "user"
    var showCopyHint  by remember { mutableStateOf(false) }

    // إخفاء تلميح النسخ بعد ثانيتين
    LaunchedEffect(showCopyHint) {
        if (showCopyHint) {
            kotlinx.coroutines.delay(2000)
            showCopyHint = false
        }
    }

    Column(
        modifier              = Modifier.fillMaxWidth(),
        horizontalAlignment   = if (isUser) Alignment.End else Alignment.Start
    ) {
        Card(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .combinedClickable(
                    onClick = {},
                    onLongClick = {
                        // ✅ نسخ عند الضغط المطول
                        onCopy(message.content)
                        showCopyHint = true
                    }
                ),
            shape  = RoundedCornerShape(
                topStart     = if (isUser) 16.dp else 4.dp,
                topEnd       = if (isUser) 4.dp else 16.dp,
                bottomStart  = 16.dp,
                bottomEnd    = 16.dp
            ),
            colors = CardDefaults.cardColors(
                containerColor = if (isUser)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {

                // ✅ نص الرسالة
                Text(
                    text  = message.content,
                    color = if (isUser)
                        MaterialTheme.colorScheme.onPrimary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )

                // ✅ تلميح النسخ
                if (showCopyHint) {
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

        // ✅ تلميح الضغط المطول
        Text(
            text     = "اضغط مطولاً للنسخ",
            style    = MaterialTheme.typography.labelSmall,
            color    = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
        )
    }
}
