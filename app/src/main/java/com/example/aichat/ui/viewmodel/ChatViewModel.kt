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

    private val db = ChatDatabase.getInstance(application)
    private val dao = db.chatDao()

    val repository = ChatRepository(application)

    private var messagesCollectJob: Job? = null

    // ============================================================
    // State
    // ============================================================

    val conversations = dao.getAllConversations()
        .stateIn(
            viewModelScope,
            SharingStarted.Lazily,
            emptyList()
        )

    private val _currentConversationId = MutableStateFlow<Long?>(null)
    val currentConversationId: StateFlow<Long?> =
        _currentConversationId.asStateFlow()

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> =
        _messages.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> =
        _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> =
        _error.asStateFlow()

    private val _selectedImageBase64 = MutableStateFlow<String?>(null)
    val selectedImageBase64: StateFlow<String?> =
        _selectedImageBase64.asStateFlow()

    private val _isImageGenerationMode = MutableStateFlow(false)
    val isImageGenerationMode: StateFlow<Boolean> =
        _isImageGenerationMode.asStateFlow()

    // ============================================================
    // الوضع
    // ============================================================

    fun toggleImageGenerationMode() {
        _isImageGenerationMode.value =
            !_isImageGenerationMode.value

        _error.value = null
    }

    // ============================================================
    // المحادثات
    // ============================================================

    fun openConversation(conversationId: Long) {

        _currentConversationId.value = conversationId

        startCollecting(conversationId)
    }

    fun newConversation() {

        messagesCollectJob?.cancel()
        messagesCollectJob = null

        _currentConversationId.value = null

        _messages.value = emptyList()

        _selectedImageBase64.value = null

        _isImageGenerationMode.value = false

        _error.value = null
    }

    // ============================================================
    // مراقبة رسائل Room
    // ============================================================

    private fun startCollecting(conversationId: Long) {

        messagesCollectJob?.cancel()

        messagesCollectJob = viewModelScope.launch {

            dao.getMessages(conversationId).collect { msgs ->

                _messages.value = msgs
            }
        }
    }

    // ============================================================
    // إرسال
    // ============================================================

    fun sendMessage(userText: String) {

        if (_isLoading.value) return

        val image = _selectedImageBase64.value

        if (userText.isBlank() && image == null) {
            return
        }

        if (_isImageGenerationMode.value) {
            generateImage(userText)
        } else {
            sendChatMessage(userText)
        }
    }

    // ============================================================
    // المحادثة النصية
    // ============================================================

    private fun sendChatMessage(userText: String) {

        if (_isLoading.value) return

        viewModelScope.launch {

            _isLoading.value = true
            _error.value = null

            try {

                val imageBase64 = _selectedImageBase64.value

                // ------------------------------------------------
                // إنشاء المحادثة إذا لم تكن موجودة
                // ------------------------------------------------

                val convId =
                    _currentConversationId.value ?: run {

                        val id = dao.insertConversation(
                            Conversation(
                                title = userText
                                    .take(50)
                                    .ifBlank { "محادثة جديدة" }
                            )
                        )

                        _currentConversationId.value = id

                        // ⭐ مهم جداً:
                        // ابدأ مراقبة المحادثة فور إنشائها
                        startCollecting(id)

                        id
                    }

                // ------------------------------------------------
                // خذ تاريخ المحادثة قبل إضافة رسالة المستخدم
                // ------------------------------------------------

                val historySnapshot =
                    dao.getMessagesOnce(convId)

                // ------------------------------------------------
                // ⭐ أهم إصلاح
                //
                // لا نلغي messagesCollectJob هنا.
                //
                // Room Flow سيكتشف الرسالة فور إدخالها
                // وبالتالي ستظهر للمستخدم مباشرة.
                // ------------------------------------------------

                dao.insertMessage(
                    Message(
                        conversationId = convId,
                        role = "user",
                        content = userText,
                        imageBase64 = imageBase64,
                        messageType = "text"
                    )
                )

                // ------------------------------------------------
                // امسح الصورة المختارة بعد إرسال الرسالة
                // ------------------------------------------------

                _selectedImageBase64.value = null

                // ------------------------------------------------
                // طلب النموذج
                // ------------------------------------------------

                val response = repository.sendMessage(
                    history = historySnapshot,
                    userMessage = userText,
                    imageBase64 = imageBase64
                )

                // ------------------------------------------------
                // إضافة جواب النموذج
                // ------------------------------------------------

                dao.insertMessage(
                    Message(
                        conversationId = convId,
                        role = "assistant",
                        content = response,
                        messageType = "text"
                    )
                )

                // ------------------------------------------------
                // تحديث عنوان المحادثة
                // ------------------------------------------------

                val title =
                    historySnapshot
                        .firstOrNull { it.role == "user" }
                        ?.content
                        ?: userText

                dao.updateConversation(
                    Conversation(
                        id = convId,
                        title = title.take(50),
                        updatedAt = System.currentTimeMillis()
                    )
                )

            } catch (e: Exception) {

                _error.value =
                    e.message ?: "حدث خطأ غير معروف"

            } finally {

                _isLoading.value = false

                // لا حاجة لإلغاء وإعادة تشغيل الـ collector
                // لأنه يعمل طوال فترة المحادثة.
            }
        }
    }

    // ============================================================
    // توليد الصور
    // ============================================================

    private fun generateImage(prompt: String) {

        if (_isLoading.value) return

        viewModelScope.launch {

            _isLoading.value = true
            _error.value = null

            try {

                val convId =
                    _currentConversationId.value ?: run {

                        val id = dao.insertConversation(
                            Conversation(
                                title = prompt
                                    .take(50)
                                    .ifBlank { "توليد صورة" }
                            )
                        )

                        _currentConversationId.value = id

                        startCollecting(id)

                        id
                    }

                // ------------------------------------------------
                // رسالة المستخدم تظهر مباشرة
                // ------------------------------------------------

                dao.insertMessage(
                    Message(
                        conversationId = convId,
                        role = "user",
                        content = "🎨 $prompt",
                        messageType = "image_generation"
                    )
                )

                // ------------------------------------------------
                // توليد الصورة
                // ------------------------------------------------

                val result =
                    repository.generateImage(prompt)

                // ------------------------------------------------
                // النتيجة
                // ------------------------------------------------

                dao.insertMessage(
                    Message(
                        conversationId = convId,
                        role = "assistant",
                        content = prompt,
                        generatedImageUrl = result.url,
                        imageBase64 = result.base64,
                        messageType = "image_generation"
                    )
                )

            } catch (e: Exception) {

                _error.value =
                    e.message ?: "حدث خطأ غير معروف"

            } finally {

                _isLoading.value = false
            }
        }
    }

    // ============================================================
    // الصور
    // ============================================================

    fun selectImage(uri: Uri) {

        viewModelScope.launch {

            try {

                val context =
                    getApplication<Application>()

                val stream =
                    context.contentResolver
                        .openInputStream(uri)
                        ?: return@launch

                val bitmap =
                    BitmapFactory.decodeStream(stream)

                stream.close()

                if (bitmap == null) {
                    _error.value = "تعذر قراءة الصورة"
                    return@launch
                }

                val output =
                    ByteArrayOutputStream()

                bitmap.compress(
                    Bitmap.CompressFormat.JPEG,
                    85,
                    output
                )

                _selectedImageBase64.value =
                    Base64.encodeToString(
                        output.toByteArray(),
                        Base64.NO_WRAP
                    )

            } catch (e: Exception) {

                _error.value =
                    "فشل تحميل الصورة: ${e.message}"
            }
        }
    }

    fun selectBitmap(bitmap: Bitmap) {

        viewModelScope.launch {

            try {

                val output =
                    ByteArrayOutputStream()

                bitmap.compress(
                    Bitmap.CompressFormat.JPEG,
                    85,
                    output
                )

                _selectedImageBase64.value =
                    Base64.encodeToString(
                        output.toByteArray(),
                        Base64.NO_WRAP
                    )

            } catch (e: Exception) {

                _error.value =
                    "فشل تحميل الصورة: ${e.message}"
            }
        }
    }

    fun selectFile(uri: Uri) {

        viewModelScope.launch {

            try {

                val context =
                    getApplication<Application>()

                val mimeType =
                    context.contentResolver
                        .getType(uri)
                        ?: ""

                if (mimeType.startsWith("image/")) {

                    selectImage(uri)

                } else {

                    val name =
                        uri.lastPathSegment
                            ?.substringAfterLast("/")
                            ?: "ملف"

                    _error.value =
                        "⚠️ الملفات غير الصورة غير مدعومة: $name"
                }

            } catch (e: Exception) {

                _error.value =
                    "فشل تحميل الملف: ${e.message}"
            }
        }
    }

    // ============================================================
    // Helpers
    // ============================================================

    fun clearSelectedImage() {
        _selectedImageBase64.value = null
    }

    fun clearError() {
        _error.value = null
    }

    // ============================================================
    // حذف المحادثة
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
}
