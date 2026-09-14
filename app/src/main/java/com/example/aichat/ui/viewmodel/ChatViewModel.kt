package com.example.aichat.ui.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.aichat.data.local.ChatDatabase
import com.example.aichat.data.model.Conversation
import com.example.aichat.data.model.MemoryItem
import com.example.aichat.data.model.Message
import com.example.aichat.repository.ChatRepository
import com.example.aichat.repository.ImageProcessor
import com.example.aichat.repository.MemoryContextBuilder
import com.example.aichat.repository.MemoryRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val db = ChatDatabase.getInstance(application)
    private val dao = db.chatDao()
    private val memoryDao = db.memoryDao()

    val repository = ChatRepository(application)

    private val memoryRepository =
        MemoryRepository(memoryDao)

    private val memoryContextBuilder =
        MemoryContextBuilder()

    private val imageProcessor =
        ImageProcessor(application)

    val customRequestCount: StateFlow<Int> =
        repository.customRequestCount

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

    private val _currentConversationId =
        MutableStateFlow<Long?>(null)

    val currentConversationId: StateFlow<Long?> =
        _currentConversationId.asStateFlow()

    private val _messages =
        MutableStateFlow<List<Message>>(emptyList())

    val messages: StateFlow<List<Message>> =
        _messages.asStateFlow()

    private val _isLoading =
        MutableStateFlow(false)

    val isLoading: StateFlow<Boolean> =
        _isLoading.asStateFlow()

    private val _error =
        MutableStateFlow<String?>(null)

    val error: StateFlow<String?> =
        _error.asStateFlow()

    private val _selectedImageBase64 =
        MutableStateFlow<String?>(null)

    val selectedImageBase64: StateFlow<String?> =
        _selectedImageBase64.asStateFlow()

    // ============================================================
    // سياق الذاكرة
    // ============================================================

    private val _memoryContext =
        MutableStateFlow("")

    val memoryContext: StateFlow<String> =
        _memoryContext.asStateFlow()

    // ============================================================
    // الذاكرة
    // ============================================================

    val sharedMemories: StateFlow<List<MemoryItem>> =
        memoryRepository.getSharedMemories()
            .stateIn(
                viewModelScope,
                SharingStarted.Lazily,
                emptyList()
            )

    fun getConversationMemories(
        conversationId: Long
    ): Flow<List<MemoryItem>> {
        return memoryRepository.getConversationMemories(
            conversationId
        )
    }

    fun addMemory(
        content: String,
        category: String = "OTHER",
        isShared: Boolean = true,
        sourceMessageId: Long? = null
    ) {
        val cleanContent = content.trim()

        if (cleanContent.isBlank()) {
            return
        }

        viewModelScope.launch {

            memoryRepository.addMemory(
                content = cleanContent,
                sourceConversationId =
                    _currentConversationId.value,
                sourceMessageId = sourceMessageId,
                category = category,
                isShared = isShared
            )
        }
    }

    fun updateMemory(memory: MemoryItem) {

        viewModelScope.launch {

            memoryRepository.updateMemory(memory)
        }
    }

    fun deleteMemory(memory: MemoryItem) {

        viewModelScope.launch {

            memoryRepository.deleteMemory(memory)
        }
    }

    suspend fun searchSharedMemories(
        query: String
    ): List<MemoryItem> {

        return memoryRepository.searchSharedMemories(
            query
        )
    }

    suspend fun getMemoryById(
        memoryId: Long
    ): MemoryItem? {

        return memoryRepository.getMemoryById(
            memoryId
        )
    }

    fun deleteConversationMemories(
        conversationId: Long
    ) {

        viewModelScope.launch {

            memoryRepository.deleteConversationMemories(
                conversationId
            )
        }
    }

    /**
     * يبحث عن الذكريات المرتبطة بالسؤال الحالي
     * ثم يحولها إلى سياق نصي.
     *
     * في هذه المرحلة يتم تجهيز السياق فقط.
     * لا يتم تمريره إلى ChatRepository بعد.
     */
    private suspend fun prepareMemoryContext(
        userText: String
    ) {

        if (userText.isBlank()) {
            _memoryContext.value = ""
            return
        }

        val memories =
            memoryRepository.searchSharedMemories(
                userText
            )

        _memoryContext.value =
            memoryContextBuilder.build(
                memories
            )
    }

    // ============================================================
    // المحادثات
    // ============================================================

    fun openConversation(
        conversationId: Long
    ) {
        _currentConversationId.value = conversationId
        _memoryContext.value = ""
        startCollecting(conversationId)
    }

    fun newConversation() {

        messagesCollectJob?.cancel()
        messagesCollectJob = null

        _currentConversationId.value = null
        _messages.value = emptyList()
        _selectedImageBase64.value = null
        _memoryContext.value = ""
        _error.value = null
    }

    // ============================================================
    // مراقبة رسائل Room
    // ============================================================

    private fun startCollecting(
        conversationId: Long
    ) {
        messagesCollectJob?.cancel()

        messagesCollectJob =
            viewModelScope.launch {

                dao.getMessages(
                    conversationId
                ).collect { msgs ->

                    _messages.value = msgs
                }
            }
    }

    // ============================================================
    // إرسال
    // ============================================================

    fun sendMessage(
        userText: String
    ) {
        if (_isLoading.value) {
            return
        }

        val image =
            _selectedImageBase64.value

        if (
            userText.isBlank() &&
            image == null
        ) {
            return
        }

        sendChatMessage(userText)
    }

    // ============================================================
    // المحادثة النصية وتحليل الصور
    // ============================================================

    private fun sendChatMessage(
        userText: String
    ) {

        if (_isLoading.value) {
            return
        }

        _isLoading.value = true

        viewModelScope.launch {

            _error.value = null

            try {

                val imageBase64 =
                    _selectedImageBase64.value

                val convId =
                    _currentConversationId.value
                        ?: run {

                            val id =
                                dao.insertConversation(
                                    Conversation(
                                        title = userText
                                            .take(50)
                                            .ifBlank {
                                                "محادثة جديدة"
                                            }
                                    )
                                )

                            _currentConversationId.value = id

                            startCollecting(id)

                            id
                        }

                val historySnapshot =
                    dao.getMessagesOnce(
                        convId
                    )

                dao.insertMessage(
                    Message(
                        conversationId = convId,
                        role = "user",
                        content = userText,
                        imageBase64 = imageBase64,
                        messageType = "text"
                    )
                )

                _selectedImageBase64.value = null

                // تجهيز سياق الذاكرة فقط.
                // لن يتم تمريره إلى ChatRepository في هذه المرحلة.
                prepareMemoryContext(
                    userText
                )

                val response =
                    repository.sendMessage(
                        history = historySnapshot,
                        userMessage = userText,
                        imageBase64 = imageBase64
                    )

                dao.insertMessage(
                    Message(
                        conversationId = convId,
                        role = "assistant",
                        content = response,
                        messageType = "text"
                    )
                )

                val title =
                    historySnapshot
                        .firstOrNull {
                            it.role == "user"
                        }
                        ?.content
                        ?: userText

                dao.updateConversation(
                    Conversation(
                        id = convId,
                        title = title.take(50),
                        updatedAt =
                            System.currentTimeMillis()
                    )
                )

            } catch (e: Exception) {

                _error.value =
                    e.message
                        ?: "حدث خطأ غير معروف"

            } finally {

                _isLoading.value = false
            }
        }
    }

    // ============================================================
    // الصور
    // ============================================================

    fun selectImage(
        uri: Uri
    ) {

        viewModelScope.launch {

            try {

                val base64 =
                    imageProcessor.uriToBase64(
                        uri
                    )

                if (base64 == null) {

                    _error.value =
                        "تعذر قراءة الصورة"

                    return@launch
                }

                _selectedImageBase64.value =
                    base64

            } catch (e: Exception) {

                _error.value =
                    "فشل تحميل الصورة: ${e.message}"
            }
        }
    }

    fun selectBitmap(
        bitmap: Bitmap
    ) {

        viewModelScope.launch {

            try {

                _selectedImageBase64.value =
                    imageProcessor.bitmapToBase64(
                        bitmap
                    )

            } catch (e: Exception) {

                _error.value =
                    "فشل تحميل الصورة: ${e.message}"
            }
        }
    }

    fun selectFile(
        uri: Uri
    ) {

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

    fun deleteConversation(
        conversation: Conversation
    ) {

        viewModelScope.launch {

            dao.deleteMessages(
                conversation.id
            )

            dao.deleteConversation(
                conversation
            )

            if (
                _currentConversationId.value ==
                conversation.id
            ) {
                newConversation()
            }
        }
    }
}
