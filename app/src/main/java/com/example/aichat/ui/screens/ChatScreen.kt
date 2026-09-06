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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.aichat.data.model.Message
import com.example.aichat.ui.viewmodel.ChatViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onBack: () -> Unit
) {
    val messages         by viewModel.messages.collectAsState()
    val isLoading        by viewModel.isLoading.collectAsState()
    val error            by viewModel.error.collectAsState()
    val selectedImage    by viewModel.selectedImageBase64.collectAsState()

    var inputText        by remember { mutableStateOf("") }
    val listState        = rememberLazyListState()

    // اختيار صورة من المعرض
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.selectImage(it) }
    }

    // التمرير إلى آخر رسالة
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "المحادثة",
                        fontWeight = FontWeight.Bold
                    )
                },
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
                .imePadding()
        ) {

            // قائمة الرسائل
            LazyColumn(
                state    = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item { Spacer(Modifier.height(8.dp)) }

                items(
                    items = messages,
                    key   = { it.id }
                ) { message ->
                    MessageBubble(message = message)
                }

                // مؤشر التحميل
                if (isLoading) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                            horizontalArrangement = Arrangement.Start
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "جاري الرد...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }
                }

                // رسالة الخطأ
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
                                modifier = Modifier.padding(12.dp),
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
                        model             = android.util.Base64.decode(
                            selectedImage, android.util.Base64.NO_WRAP
                        ),
                        contentDescription = "صورة مختارة",
                        modifier          = Modifier
                            .height(80.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale      = ContentScale.Fit
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
                            contentDescription = "إزالة الصورة",
                            tint     = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            // حقل الإدخال
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                // زر إرفاق صورة
                IconButton(
                    onClick  = { imagePicker.launch("image/*") },
                    modifier = Modifier.padding(bottom = 4.dp)
                ) {
                    Icon(
                        Icons.Filled.AttachFile,
                        contentDescription = "إرفاق صورة",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                // حقل النص
                OutlinedTextField(
                    value         = inputText,
                    onValueChange = { inputText = it },
                    modifier      = Modifier.weight(1f),
                    placeholder   = { Text("اكتب رسالتك...") },
                    shape         = RoundedCornerShape(24.dp),
                    maxLines      = 5
                )

                Spacer(Modifier.width(8.dp))

                // زر الإرسال
                IconButton(
                    onClick  = {
                        if (!isLoading) {
                            viewModel.sendMessage(inputText)
                            inputText = ""
                        }
                    },
                    modifier = Modifier
                        .padding(bottom = 4.dp)
                        .background(
                            color  = if (isLoading)
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                            else
                                MaterialTheme.colorScheme.primary,
                            shape  = CircleShape
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
}

@Composable
private fun MessageBubble(message: Message) {

    val isUser = message.role == "user"

    Row(
        modifier              = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier       = Modifier.widthIn(max = 300.dp),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
        ) {
            // صورة مرفقة إن وجدت
            message.imageBase64?.let { base64 ->
                AsyncImage(
                    model              = android.util.Base64.decode(
                        base64, android.util.Base64.NO_WRAP
                    ),
                    contentDescription = "صورة",
                    modifier           = Modifier
                        .height(150.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .padding(bottom = 4.dp),
                    contentScale       = ContentScale.Fit
                )
            }

            // نص الرسالة
            if (message.content.isNotBlank()) {
                Card(
                    shape  = RoundedCornerShape(
                        topStart    = if (isUser) 16.dp else 4.dp,
                        topEnd      = if (isUser) 4.dp else 16.dp,
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
                    Text(
                        text     = message.content,
                        modifier = Modifier.padding(
                            horizontal = 12.dp,
                            vertical   = 8.dp
                        ),
                        color    = if (isUser)
                            MaterialTheme.colorScheme.onPrimary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        style    = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}
