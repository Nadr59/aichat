package com.example.aichat.ui.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.aichat.data.local.ChatDatabase
import com.example.aichat.data.model.Conversation
import com.example.aichat.data.model.Message
import com.example.aichat.repository.ChatRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val db         = ChatDatabase.getInstance(application)
    private val dao        = db.chatDao()
    val repository         = ChatRepository(application)

    // ============================================================
    // Conversations
    // ============================================================

    val conversations = dao.getAllConversations()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _currentConversationId = MutableStateFlow<Long?>(null)
    val currentConversationId: StateFlow<Long?> = _currentConversationId.asStateFlow()

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    // ✅ تتبع Job الـ collect لإلغائه عند الحاجة
    private var messagesCollectJob: Job? = null

    // ============================================================
    // UI State
    // ============================================================

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _selectedImageBase64 = MutableStateFlow<String?>(null)
    val selectedImageBase64: StateFlow<String?> = _selectedImageBase64.asStateFlow()

    // ============================================================
    // وضع توليد الصور
    // ============================================================

    private val _isImageGenerationMode = MutableStateFlow(false)
    val isImageGenerationMode: StateFlow<Boolean> = _isImageGenerationMode.asStateFlow()

    fun toggleImageGenerationMode() {
        _isImageGenerationMode.value = !_isImageGenerationMode.value
        _error.value = null
    }

    // ============================================================
    // فتح محادثة
    // ============================================================

    fun openConversation(conversationId: Long) {
        _currentConversationId.value = conversationId

        // ✅ إلغاء الـ collect السابق قبل بدء جديد
        messagesCollectJob?.cancel()
        messagesCollectJob = viewModelScope.launch {
            dao.getMessages(conversationId).collect { msgs ->
                _messages.value = msgs
            }
        }
    }

    fun newConversation() {
        // ✅ إلغاء الـ collect عند بدء محادثة جديدة
        messagesCollectJob?.cancel()
        messagesCollectJob = null

        _currentConversationId.value = null
        _messages.value = emptyList()
        _selectedImageBase64.value = null
        _isImageGenerationMode.value = false
        _error.value = null
    }

    // ============================================================
    // إرسال رسالة - مع حماية من الطلبات المتعددة
    // ============================================================

    fun sendMessage(userText: String) {

        // ✅ منع الإرسال أثناء التحميل
        if (_isLoading.value) return

        if (userText.isBlank() && _selectedImageBase64.value == null) return

        if (_isImageGenerationMode.value) {
            generateImage(userText)
        } else {
            sendChatMessage(userText)
        }
    }

    // ============================================================
    // رسالة نصية
    // ============================================================

    = private fun sendChatMessage(userText: String) {

    if (_isLoading.value) return

    viewModelScope.launch {

        _isLoading.value = true
        _error.value = null

        try {
            val convId      = getOrCreateConversation(userText)
            val imageBase64 = _selectedImageBase64.value
            val historySnapshot = _messages.value.toList()

            dao.insertMessage(
                Message(
                    conversationId = convId,
                    role           = "user",
                    content        = userText,
                    imageBase64    = imageBase64,
                    messageType    = "text"
                )
            )

            _selectedImageBase64.value = null

            val response = repository.sendMessage(
                history     = historySnapshot,
                userMessage = userText,
                imageBase64 = imageBase64
            )

            dao.insertMessage(
                Message(
                    conversationId = convId,
                    role           = "assistant",
                    content        = response,
                    messageType    = "text"
                )
            )

            updateConversationTitle(convId)

        } catch (e: Exception) {
            // ✅ اعرض الخطأ الحقيقي كما هو بدون تصفية
            _error.value = e.message ?: "خطأ غير معروف"
        } finally {
            _isLoading.value = false
        }
    }
    }

    // ============================================================
    // توليد صورة
    // ============================================================

    private fun generateImage(prompt: String) {

        // ✅ منع طلب مزدوج
        if (_isLoading.value) return

        viewModelScope.launch {

            _isLoading.value = true
            _error.value = null

            try {
                val convId = getOrCreateConversation(prompt)

                dao.insertMessage(
                    Message(
                        conversationId = convId,
                        role           = "user",
                        content        = "🎨 $prompt",
                        messageType    = "image_generation"
                    )
                )

                val result = repository.generateImage(prompt)

                dao.insertMessage(
                    Message(
                        conversationId    = convId,
                        role              = "assistant",
                        content           = prompt,
                        generatedImageUrl = result.url,
                        imageBase64       = result.base64,
                        messageType       = "image_generation"
                    )
                )

                updateConversationTitle(convId)

            } catch (e: Exception) {
    // ✅ اعرض الخطأ الكامل مؤقتاً لنرى المشكلة
    _error.value = e.message ?: "خطأ غير معروف"
            
            } finally {
                _isLoading.value = false
            }
        }
    }

    // ============================================================
    // Helpers
    // ============================================================

    private suspend fun getOrCreateConversation(firstMessage: String): Long {
        return _currentConversationId.value ?: run {
            val title = firstMessage.take(50).ifBlank { "محادثة جديدة" }
            val id    = dao.insertConversation(Conversation(title = title))
            _currentConversationId.value = id
            openConversation(id)
            id
        }
    }

    private suspend fun updateConversationTitle(convId: Long) {
        val firstUserMessage = _messages.value
            .firstOrNull { it.role == "user" }
            ?.content
            ?.take(50)
            ?: "محادثة"

        dao.updateConversation(
            Conversation(
                id        = convId,
                title     = firstUserMessage,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    fun deleteConversation(conversation: Conversation) {
        viewModelScope.launch {
            dao.deleteMessages(conversation.id)
            dao.deleteConversation(conversation)
            if (_currentConversationId.value == conversation.id) {
                newConversation()
            }
        }
    }

    fun selectImage(uri: Uri) {
        viewModelScope.launch {
            try {
                val context = getApplication<Application>()
                val stream  = context.contentResolver.openInputStream(uri) ?: return@launch
                val bitmap  = BitmapFactory.decodeStream(stream)
                stream.close()

                val output = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output)

                _selectedImageBase64.value =
                    Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)

            } catch (e: Exception) {
                _error.value = "فشل تحميل الصورة: ${e.message}"
            }
        }
    }

    fun selectBitmap(bitmap: Bitmap) {
        viewModelScope.launch {
            try {
                val output = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output)
                _selectedImageBase64.value =
                    Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
            } catch (e: Exception) {
                _error.value = "فشل تحميل الصورة: ${e.message}"
            }
        }
    }

    fun selectFile(uri: Uri) {
        viewModelScope.launch {
            try {
                val context  = getApplication<Application>()
                val mimeType = context.contentResolver.getType(uri) ?: ""
                if (mimeType.startsWith("image/")) {
                    selectImage(uri)
                } else {
                    val fileName = uri.lastPathSegment?.substringAfterLast("/") ?: "ملف"
                    _error.value = "⚠️ الملفات غير الصورة غير مدعومة: $fileName"
                }
            } catch (e: Exception) {
                _error.value = "فشل تحميل الملف: ${e.message}"
            }
        }
    }

    fun clearSelectedImage() { _selectedImageBase64.value = null }
    fun clearError()         { _error.value = null }
}
