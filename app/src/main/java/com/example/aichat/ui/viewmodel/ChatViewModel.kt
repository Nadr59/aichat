package com.example.aichat.ui.viewmodel

import android.app.Application
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.aichat.data.local.ChatDatabase
import com.example.aichat.data.model.Conversation
import com.example.aichat.data.model.Message
import com.example.aichat.repository.ChatRepository
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
    // فتح محادثة موجودة
    // ============================================================

    fun openConversation(conversationId: Long) {
        _currentConversationId.value = conversationId
        viewModelScope.launch {
            dao.getMessages(conversationId).collect { msgs ->
                _messages.value = msgs
            }
        }
    }

    // ============================================================
    // محادثة جديدة
    // ============================================================

    fun newConversation() {
        _currentConversationId.value = null
        _messages.value = emptyList()
        _selectedImageBase64.value = null
        _error.value = null
    }

    // ============================================================
    // إرسال رسالة
    // ============================================================

    fun sendMessage(userText: String) {

        if (userText.isBlank() && _selectedImageBase64.value == null) return

        viewModelScope.launch {

            _isLoading.value = true
            _error.value = null

            try {

                // إنشاء محادثة جديدة إذا لم تكن موجودة
                val convId = _currentConversationId.value
                    ?: run {
                        val title = userText.take(50).ifBlank { "محادثة جديدة" }
                        val id = dao.insertConversation(
                            Conversation(title = title)
                        )
                        _currentConversationId.value = id
                        openConversation(id)
                        id
                    }

                val imageBase64 = _selectedImageBase64.value

                // حفظ رسالة المستخدم
                dao.insertMessage(
                    Message(
                        conversationId = convId,
                        role           = "user",
                        content        = userText,
                        imageBase64    = imageBase64
                    )
                )

                _selectedImageBase64.value = null

                // إرسال إلى النموذج
                val response = repository.sendMessage(
                    history     = _messages.value,
                    userMessage = userText,
                    imageBase64 = imageBase64
                )

                // حفظ رد النموذج
                dao.insertMessage(
                    Message(
                        conversationId = convId,
                        role           = "assistant",
                        content        = response
                    )
                )

                // تحديث عنوان المحادثة
                dao.updateConversation(
                    Conversation(
                        id        = convId,
                        title     = _messages.value
                            .firstOrNull { it.role == "user" }
                            ?.content
                            ?.take(50)
                            ?: "محادثة",
                        updatedAt = System.currentTimeMillis()
                    )
                )

            } catch (e: Exception) {
                _error.value = e.message ?: "حدث خطأ غير معروف"
            } finally {
                _isLoading.value = false
            }
        }
    }

    // ============================================================
    // حذف محادثة
    // ============================================================

    fun deleteConversation(conversation: Conversation) {
        viewModelScope.launch {
            dao.deleteMessages(conversation.id)
            dao.deleteConversation(conversation)
            if (_currentConversationId.value == conversation.id) {
                newConversation()
            }
        }
    }

    // ============================================================
    // تحديد صورة
    // ============================================================

    fun selectImage(uri: Uri) {
        viewModelScope.launch {
            try {
                val context = getApplication<Application>()
                val stream  = context.contentResolver.openInputStream(uri) ?: return@launch
                val bitmap  = BitmapFactory.decodeStream(stream)
                stream.close()

                // ضغط الصورة
                val output = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output)
                val bytes  = output.toByteArray()

                _selectedImageBase64.value = Base64.encodeToString(bytes, Base64.NO_WRAP)

            } catch (e: Exception) {
                _error.value = "فشل تحميل الصورة: ${e.message}"
            }
        }
    }

    fun clearSelectedImage() {
        _selectedImageBase64.value = null
    }

    fun clearError() {
        _error.value = null
    }
}
